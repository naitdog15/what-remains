package com.temog.notminelegacy.whatremains.client;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.google.common.hash.Hashing;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.temog.notminelegacy.whatremains.entity.DeathCause;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;

/**
 * Acquires owner skin pixels, bakes decay off-thread, and owns every generated texture until its
 * deferred release. All public methods are called on the client/render thread.
 */
public final class CorpseFleshBaker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CorpseFleshBaker INSTANCE = new CorpseFleshBaker();
    private static final int MAX_BAKED_TEXTURES = 128;
    private static final int MAX_SOURCE_IMAGES = 64;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final int MAX_UPLOADS_PER_FRAME = 4;
    private static final long EXPIRE_NANOS = TimeUnit.SECONDS.toNanos(120);

    // ── frozen_rime.md §3.1/§3.2 skin-recolour transform constants ──────────────────────────────
    private static final float FROST_TARGET_HUE_DEG = 208.0F;
    private static final float FROST_HUE_BLEND = 0.85F;
    /** Per-stage saturation cap, indexed by {@code DecayStage.ordinal()} (FRESH..ADVANCED_DECAY) —
     *  anything at or past ADVANCED_DECAY's index reuses its cap (index clamped by the caller in
     *  {@link #applyFrostSkinRecolor}). In practice SKELETAL/COLLAPSE never reach that clamp at all:
     *  {@link #resolve} nulls out before a bake is ever requested for either. */
    private static final float[] FROST_SAT_CAP = {0.28F, 0.24F, 0.20F, 0.18F, 0.14F};

    private static final ResourceLocation WITHER_OVERLAY_TEXTURE = ResourceLocation.parse(
            "whatremains:textures/entity/corpse_flesh/overlay/wither_scarred.png");

    private final Object lock = new Object();
    private final AtomicLong requestSequence = new AtomicLong();
    private final ThreadPoolExecutor workers;
    private final Map<UUID, OwnerSource> owners = new HashMap<>();
    private final Map<BakeKey, BakeTask> pending = new HashMap<>();
    private final ConcurrentLinkedQueue<BakeResult> completed = new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<BakeKey, BakedTexture> baked =
            new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<SourceKey, int[]> sourceImages =
            new LinkedHashMap<>(16, 0.75F, true);
    private final Map<ResourceLocation, int[]> assetImages = new HashMap<>();
    private final Set<SourceKey> failedSources = new HashSet<>();
    private final Set<String> loggedFailures = new HashSet<>();
    private final Set<ResourceLocation> missingAssets = new HashSet<>();
    private final List<DeferredRelease> deferredReleases = new ArrayList<>();

    private volatile int detailEpoch;
    private volatile long lifecycleGeneration;
    private long frameIndex;
    private long sourceGeneration;

    private CorpseFleshBaker() {
        PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "WhatRemains-FleshBaker");
            thread.setDaemon(true);
            return thread;
        };
        workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, queue, factory);
    }

    public static CorpseFleshBaker getInstance() {
        return INSTANCE;
    }

    /**
     * Mojang's UUID-hash slim/wide guess — the only model information available for a player's
     * DEFAULT skin, before any real skin data has loaded. Shared by {@link #resolve}'s {@code owner
     * == null} branch and {@link #isRightArmFullyRevealed}'s own {@code owner == null} fallback so
     * the two can never independently drift out of sync with each other; see {@link
     * #isRightArmFullyRevealed} for why a guess that turns out wrong (a real account's chosen skin
     * model is unrelated to its UUID) is still harmless.
     */
    private static boolean guessSlimFromUuid(UUID ownerUuid) {
        return "slim".equals(DefaultPlayerSkin.getSkinModelName(ownerUuid));
    }

    @Nullable
    public ResolvedFlesh resolve(GameProfile profile, UUID corpseUuid, CorpseRenderState state,
                                 double distanceSquared, EnvironmentalRenderInputs env,
                                 long appearanceSeed, InjuryRenderInputs injury) {
        UUID ownerUuid = profile.getId();
        // SKELETAL is now the final bare-exoskeleton stage (playtest feedback: a "skeletal" corpse
        // still had ADVANCED_DECAY's flesh baked onto it, which read as contradictory — "skeletal"
        // should mean no flesh, period). Deliberately never bakes: the corpse renders as pure
        // skeleton model + armor only, with no flesh texture composited over it at all. The existing
        // char-threshold skeleton-texture swap in CorpseRenderer still applies independently on top
        // of that bare model (a charred/wither-scarred look), since it never depended on flesh being
        // baked in the first place. ADVANCED_DECAY (the OLD SKELETAL's position/look) is deliberately
        // NOT added here — it still bakes flesh normally, using ENDPOINTS[4]'s unchanged values.
        if (ownerUuid == null || state.decayStage == DecayStage.COLLAPSE
                || state.decayStage == DecayStage.SKELETAL) return null;

        OwnerSource owner = owners.get(ownerUuid);
        if (owner == null) {
            ResourceLocation defaultSkin = DefaultPlayerSkin.getDefaultSkin(ownerUuid);
            boolean slim = guessSlimFromUuid(ownerUuid);
            owner = new OwnerSource(ownerUuid, defaultSkin,
                    "default:" + defaultSkin + ":" + detailEpoch, slim, null,
                    ++sourceGeneration);
            owners.put(ownerUuid, owner);
            requestRealSkin(profile, ownerUuid);
        }
        owner.lastCorpseUuid = corpseUuid;
        owner.lastState = state;
        owner.lastEnv = env;
        owner.lastDistanceSquared = distanceSquared;
        owner.lastAppearanceSeed = appearanceSeed;
        owner.lastInjury = injury;

        BakeKey key = keyFor(owner, corpseUuid, state, env);
        BakedTexture exact = baked.get(key);
        if (exact != null) {
            exact.touch(frameIndex);
            return new ResolvedFlesh(exact.location, owner.slim);
        }

        requestBake(owner, corpseUuid, state, env, distanceSquared, appearanceSeed, injury);
        BakedTexture nearest = nearestBake(owner, corpseUuid, state, env);
        if (nearest != null) {
            nearest.touch(frameIndex);
            return new ResolvedFlesh(nearest.location, owner.slim);
        }

        ResourceLocation fallback = fallbackTexture(state.decayStage, owner.slim, env.frostLevel());
        if (resourceExists(fallback)) return new ResolvedFlesh(fallback, owner.slim);
        return null;
    }

    /**
     * RECONCILIATION.md R5's "an active RIGHT_ARM full-part reveal forces the SKELETAL beside-hand
     * held-item pose immediately" rule, exposed for {@code CorpseHeldItemLayer}. Reuses whatever
     * {@code slim} flag this owner has already resolved (falling back to {@link
     * #guessSlimFromUuid(UUID)} — the exact same UUID-derived default {@link #resolve} itself uses
     * before a real skin has ever loaded, via the same shared helper rather than a second inline copy
     * that could drift out of sync) so this queries the exact same {@link CorpseAppearanceLayout}
     * instance the flesh bake itself uses — never a throwaway reconstruction under a guessed slim
     * value, which would otherwise pay {@code buildHoleRank}'s real per-construction cost on the
     * render thread every frame for no benefit.
     * <p>
     * Pre-resolution guess accuracy: before the real skin loads, this fallback and {@link #resolve}'s
     * own {@code owner == null} branch always agree with EACH OTHER (both call the same helper on the
     * same {@code ownerUuid}), even though the UUID-hash guess itself may not match the player's real
     * chosen skin model — Mojang's own per-account model choice is unrelated to their UUID. That
     * mismatch, if any, is harmless and self-correcting: once {@link #requestRealSkin}'s callback
     * lands, it replaces the {@code owners} entry with the real resolved {@code slim} and triggers a
     * rebake, so this method's {@code owner != null} branch and the flesh bake pick up the corrected
     * value together on the very next call — there is never a frame where the pose and the baked
     * flesh are computed against two DIFFERENT slim guesses, only a shared provisional one that later
     * converges with the real one.
     */
    public boolean isRightArmFullyRevealed(UUID ownerUuid, long appearanceSeed, DecayStage stage,
                                           float stageProgress, float externalRevealBoost,
                                           @Nullable CorpseAppearanceLayout.RevealPreference preference) {
        OwnerSource owner = owners.get(ownerUuid);
        boolean slim = owner != null ? owner.slim : guessSlimFromUuid(ownerUuid);
        CorpseFleshMask mask = CorpseFleshMask.get(slim);
        CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(appearanceSeed, slim, mask);
        CorpseFleshMask.RevealArchetype resolved =
                layout.resolveActiveArchetype(stage, stageProgress, externalRevealBoost, preference);
        return resolved != null && resolved.contains(CorpseFleshMask.Part.RIGHT_ARM);
    }

    /**
     * RECONCILIATION.md R3: purges every baked texture for one corpse (through the existing two-frame
     * -delayed {@link #deferRelease} path — never a direct render-thread release) so a runtime NBT
     * mutation of {@code AppearanceSeed}/{@code StuckProjectiles}/{@code DeathCause} on an
     * already-tracked corpse (the documented {@code /data merge entity} testing workflow) cannot leave
     * a permanently stale bake on screen. {@link #BakeKey} deliberately stays at exactly 14 fields and
     * carries none of those three inputs, so a mutation cannot naturally invalidate the cache via a
     * key mismatch — this explicit purge is the only fix. Also drops any in-flight {@code pending}
     * bake for the same corpse: since {@link BakeKey} does not vary with the mutated inputs either,
     * requestBake's own dedup (`pending.containsKey(key)`) would otherwise silently let a bake already
     * queued for the OLD inputs populate {@link #baked} right after this purge, undoing it.
     */
    public void invalidateCorpse(UUID corpseUuid) {
        Iterator<Map.Entry<BakeKey, BakedTexture>> iterator = baked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BakeKey, BakedTexture> entry = iterator.next();
            if (entry.getKey().corpseUuid.equals(corpseUuid)) {
                deferRelease(entry.getValue().location);
                iterator.remove();
            }
        }
        synchronized (lock) {
            Iterator<Map.Entry<BakeKey, BakeTask>> pendingIterator = pending.entrySet().iterator();
            while (pendingIterator.hasNext()) {
                Map.Entry<BakeKey, BakeTask> entry = pendingIterator.next();
                if (entry.getKey().corpseUuid.equals(corpseUuid)) {
                    workers.getQueue().remove(entry.getValue());
                    pendingIterator.remove();
                }
            }
        }
    }

    /** Called exactly once for each rendered world frame by the Forge render-stage event. */
    public void onRenderFrame() {
        frameIndex++;
        releaseDueTextures();

        for (int uploads = 0; uploads < MAX_UPLOADS_PER_FRAME; uploads++) {
            BakeResult result = completed.poll();
            if (result == null) break;
            synchronized (lock) {
                pending.remove(result.key);
            }
            OwnerSource owner = owners.get(result.key.ownerUuid);
            if (result.image == null) {
                if (owner != null && owner.generation == result.generation && result.epoch == detailEpoch) {
                    synchronized (lock) {
                        failedSources.add(result.sourceKey);
                    }
                    logOnce(result.sourceKey.revision, "Unable to bake corpse flesh from skin source",
                            result.failure);
                }
                continue;
            }
            if (owner == null || owner.generation != result.generation || result.epoch != detailEpoch) {
                result.image.close();
                continue;
            }

            DynamicTexture texture = new DynamicTexture(result.image);
            ResourceLocation location = Minecraft.getInstance().getTextureManager()
                    .register("whatremains/corpse_flesh", texture);
            BakedTexture replaced = baked.put(result.key,
                    new BakedTexture(location, System.nanoTime(), frameIndex));
            if (replaced != null) deferRelease(replaced.location);
        }

        expireAndTrimBakes();
    }

    public void onResourceReload() {
        detailEpoch++;
        clear(true);
    }

    public void onLogout() {
        clear(false);
    }

    private void requestRealSkin(GameProfile profile, UUID ownerUuid) {
        Minecraft.getInstance().getSkinManager().registerSkins(profile,
                (type, location, profileTexture) -> {
                    if (type != MinecraftProfileTexture.Type.SKIN) return;
                    OwnerSource old = owners.get(ownerUuid);
                    if (old == null) return;
                    String model = profileTexture.getMetadata("model");
                    String hash = profileTexture.getHash();
                    File cacheFile = skinCacheFile(profileTexture);
                    OwnerSource current = new OwnerSource(ownerUuid, location, hash,
                            "slim".equals(model), cacheFile, ++sourceGeneration);
                    current.lastCorpseUuid = old.lastCorpseUuid;
                    current.lastState = old.lastState;
                    current.lastEnv = old.lastEnv;
                    current.lastDistanceSquared = old.lastDistanceSquared;
                    current.lastAppearanceSeed = old.lastAppearanceSeed;
                    current.lastInjury = old.lastInjury;
                    owners.put(ownerUuid, current);
                    invalidateOwner(ownerUuid);
                    if (current.lastState != null) {
                        requestBake(current, current.lastCorpseUuid, current.lastState,
                                current.lastEnv, current.lastDistanceSquared, current.lastAppearanceSeed,
                                current.lastInjury);
                    }
                }, true);
    }

    @SuppressWarnings("deprecation")
    private File skinCacheFile(MinecraftProfileTexture profileTexture) {
        String cacheName = Hashing.sha1().hashUnencodedChars(profileTexture.getHash()).toString();
        String directory = cacheName.length() > 2 ? cacheName.substring(0, 2) : "xx";
        // skinsDirectory is widened by accesstransformer.cfg. Reusing vanilla's exact SHA-1 path
        // avoids a second download and sees the file before the skin callback returns.
        return new File(new File(Minecraft.getInstance().getSkinManager().skinsDirectory, directory),
                cacheName);
    }

    private void requestBake(OwnerSource owner, UUID corpseUuid, CorpseRenderState state,
                             EnvironmentalRenderInputs env, double distanceSquared, long appearanceSeed,
                             InjuryRenderInputs injury) {
        BakeKey key = keyFor(owner, corpseUuid, state, env);
        SourceKey sourceKey = sourceKey(owner);
        synchronized (lock) {
            if (baked.containsKey(key) || pending.containsKey(key) || failedSources.contains(sourceKey)) return;

            BakeTask task = new BakeTask(key, sourceKey, owner.snapshot(), state, env, distanceSquared,
                    requestSequence.getAndIncrement(), detailEpoch, lifecycleGeneration, appearanceSeed, injury);
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                BakeTask worst = workers.getQueue().stream()
                        .filter(BakeTask.class::isInstance).map(BakeTask.class::cast)
                        .max(Comparator.naturalOrder()).orElse(null);
                if (worst == null || task.compareTo(worst) >= 0) return;
                if (workers.getQueue().remove(worst)) pending.remove(worst.key);
            }
            pending.put(key, task);
            workers.execute(task);
        }
    }

    /**
     * Proximity fallback: the nearest bake at-or-below the current {@code visualStep} — safe
     * because age only ever advances. The environmental buckets split into two groups:
     * {@code charBucket}/{@code scorchBucket}/{@code calcifyBucket} are ONE-WAY trackers (enforced
     * independently in {@code CorpseEnvironmentLoader} and {@code CorpseEntity#nextTrackerLevel}) —
     * exactly like {@code visualStep}, an older cached bucket at-or-below the current one is a state
     * the corpse genuinely passed through and has only intensified since, so those three are allowed
     * "nearest at-or-below" too. {@code frostBucket}/{@code oceanBucket} are REVERSIBLE (a lower
     * cached bucket could be either stale-old or a level that has since risen again), so unlike the
     * one-way trackers there is no direction in which an older bake of those is always a safe
     * approximation of the current one — they still require an EXACT match. ({@code scorchStyle} is
     * a style key rather than an intensity, so it also still requires an exact match — a
     * desert-styled bake must never substitute for a nether-styled one.) An environment change
     * without a matching-or-safe cached bucket falls through to the plain fallback texture until a
     * fresh bake completes, rather than ever risking a wrong-environment texture on screen.
     */
    private BakedTexture nearestBake(OwnerSource owner, UUID corpseUuid, CorpseRenderState state,
                                     EnvironmentalRenderInputs env) {
        BakeKey target = keyFor(owner, corpseUuid, state, env);
        BakeKey nearestKey = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (Map.Entry<BakeKey, BakedTexture> entry : baked.entrySet()) {
            BakeKey key = entry.getKey();
            if (!key.corpseUuid.equals(target.corpseUuid) || !key.ownerUuid.equals(target.ownerUuid)
                    || !key.resolvedSkin.equals(target.resolvedSkin)
                    || !key.sourceRevision.equals(target.sourceRevision) || key.slim != target.slim
                    || key.detailEpoch != target.detailEpoch || key.visualStep > target.visualStep
                    || key.charBucket > target.charBucket || key.scorchBucket > target.scorchBucket
                    || !key.scorchStyle.equals(target.scorchStyle)
                    || key.calcifyBucket > target.calcifyBucket || key.frostBucket != target.frostBucket
                    || key.oceanBucket != target.oceanBucket) continue;
            int distance = target.visualStep - key.visualStep;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestKey = key;
            }
        }
        // Use get after iteration so the access-order map records this fallback as genuinely hot.
        return nearestKey == null ? null : baked.get(nearestKey);
    }

    private void invalidateOwner(UUID ownerUuid) {
        Iterator<Map.Entry<BakeKey, BakedTexture>> iterator = baked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BakeKey, BakedTexture> entry = iterator.next();
            if (entry.getKey().ownerUuid.equals(ownerUuid)) {
                deferRelease(entry.getValue().location);
                iterator.remove();
            }
        }
        synchronized (lock) {
            sourceImages.keySet().removeIf(key -> key.ownerUuid.equals(ownerUuid));
            failedSources.removeIf(key -> key.ownerUuid.equals(ownerUuid));
        }
    }

    private void clear(boolean reloading) {
        lifecycleGeneration++;
        synchronized (lock) {
            workers.getQueue().clear();
            pending.clear();
            sourceImages.clear();
            failedSources.clear();
            assetImages.clear();
            missingAssets.clear();
            loggedFailures.clear();
        }
        for (BakeResult result; (result = completed.poll()) != null;) {
            if (result.image != null) result.image.close();
        }
        for (BakedTexture texture : baked.values()) {
            Minecraft.getInstance().getTextureManager().release(texture.location);
        }
        baked.clear();
        for (DeferredRelease release : deferredReleases) {
            Minecraft.getInstance().getTextureManager().release(release.location);
        }
        deferredReleases.clear();
        owners.clear();
        CorpseFleshMask.clearCache();
        CorpseAppearanceLayout.clearCache();
        CorpseInjuryMask.clearCache();
        if (reloading) LOGGER.debug("Cleared corpse flesh cache for resource reload epoch {}", detailEpoch);
    }

    private void expireAndTrimBakes() {
        long now = System.nanoTime();
        Iterator<Map.Entry<BakeKey, BakedTexture>> iterator = baked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BakeKey, BakedTexture> entry = iterator.next();
            BakedTexture texture = entry.getValue();
            if (now - texture.lastUsedNanos > EXPIRE_NANOS && frameIndex - texture.lastUsedFrame >= 2) {
                deferRelease(texture.location);
                iterator.remove();
            }
        }
        while (baked.size() > MAX_BAKED_TEXTURES) {
            Map.Entry<BakeKey, BakedTexture> eldest = baked.entrySet().iterator().next();
            deferRelease(eldest.getValue().location);
            baked.remove(eldest.getKey());
        }
    }

    private void deferRelease(ResourceLocation location) {
        deferredReleases.add(new DeferredRelease(location, frameIndex + 2));
    }

    private void releaseDueTextures() {
        Iterator<DeferredRelease> iterator = deferredReleases.iterator();
        while (iterator.hasNext()) {
            DeferredRelease release = iterator.next();
            if (release.releaseFrame <= frameIndex) {
                Minecraft.getInstance().getTextureManager().release(release.location);
                iterator.remove();
            }
        }
    }

    private BakeKey keyFor(OwnerSource owner, UUID corpseUuid, CorpseRenderState state,
                           EnvironmentalRenderInputs env) {
        return new BakeKey(corpseUuid, owner.ownerUuid, owner.location, owner.revision,
                owner.slim, state.decayStage, state.visualStep, detailEpoch,
                charBucket(env.charLevel()), genericBucket(env.scorchLevel()), env.scorchStyle(),
                calcifyBucket(env.calcifyLevel()), genericBucket(env.frostLevel()),
                oceanBucket(env.waterloggedLevel(), env.waterloggedStyle()));
    }

    /**
     * 5-bucket quantisation matching lava_progressive_incineration.md §6 dependency 1's explicit
     * table: bucket 0 covers the "inert, below the char threshold" band so a corpse that only
     * briefly grazed lava spends no extra cache entries versus an unaffected corpse.
     * <p>
     * Package-private (not {@code private}): {@code CorpseHeldItemLayer}'s pose-row memo key also
     * needs a char bucket for the same reason {@link BakeKey} does — {@code externalRevealBoost} is
     * char-driven — and reuses this exact quantisation rather than inventing a second one that could
     * drift out of step with {@link BakeKey}'s own.
     */
    static int charBucket(float level) {
        if (level < 0.20F) return 0;
        if (level < 0.50F) return 1;
        if (level < 0.70F) return 2;
        if (level < 0.90F) return 3;
        return 4;
    }

    /** Generic 5-step quantisation reused for scorch (within a resolved style) and frost — neither
     *  design doc fixes an exact bucket count, so this mirrors char's own granularity. */
    private static int genericBucket(float level) {
        return Math.round(Math.max(0.0F, Math.min(1.0F, level)) * 4.0F);
    }

    /** 4-value bucket (none/light/moderate/heavy) matching cave_calcification.md's own three
     *  detail-sheet severity tiers 1:1 — see {@link #composeCalcifyDetail}. */
    private static int calcifyBucket(float level) {
        if (level < 0.15F) return 0;
        if (level < 0.50F) return 1;
        if (level < 0.80F) return 2;
        return 3;
    }

    /** ocean_bloat.md §6 dependency 4's proposed 8 steps, collapsed to bucket 0 whenever the
     *  resolved waterlogged style isn't ocean_bloat — the generic (non-ocean) waterlogged tracker
     *  never touches the baked pixels (only CorpseFleshLayer's render-time tint), so it needs no
     *  cache dimension at all. */
    private static int oceanBucket(float level, String style) {
        if (!EnvironmentalRenderInputs.WATERLOGGED_STYLE_OCEAN.equals(style) || level <= 0.0F) return 0;
        return 1 + Math.round(Math.max(0.0F, Math.min(1.0F, level)) * 7.0F);
    }

    private SourceKey sourceKey(OwnerSource owner) {
        return new SourceKey(owner.ownerUuid, owner.location, owner.revision, owner.slim, detailEpoch);
    }

    /**
     * Bake pipeline order (RECONCILIATION.md R4's single combined ordering across both 2026-08-23
     * env-corpse follow-on designs). Both R4 gaps are now filled — the patch-stitch pass at gap 3
     * (SOL-PATCH-STITCH-DESIGN.md F.1 step 9 / C.6) and the two injury passes at gaps 6/8
     * (SOL-DEATHCAUSE-INJURY-DESIGN.md F1 step 7). Keep this ordering — RECONCILIATION.md R4 and
     * patch-stitch F.4 both name pass ordering as the single highest regression risk to the shipped
     * environmental cases, and nobody should "tidy" this method into a different sequence:
     *
     * <pre>
     * 1. clone source, force base opaque, applyHsv, frost recolor
     * 2. age wet-rot detail crossfade
     * 3. CorpsePatchStitcher.applyPatchStitch — the seeded six-patch library (SOL-PATCH-STITCH-
     *    DESIGN.md B/C/D). Runs after wet-rot, before wither/environment detail (C.6) — placing it
     *    later would let patches paint over desert cracks, nether sulfur, calcite streaks, or frost
     *    facets (F.4's named highest regression risk). Uses environment-aware P/Q colours (already
     *    environment-shifted by CorpseRenderState.withEnvironment upstream) PLUS its own D.1/D.2
     *    per-archetype environment matrix, so a charred/frosted/waterlogged/calcified/withered corpse
     *    still reads as that case, not as generic warm flesh.
     * 4. wither overlay, scorch detail, calcify detail, frosted detail
     * 5. applyProceduralBlotches — BYTE-FOR-BYTE UNCHANGED, same order (revised death-cause design's
     *    F1 step 7 explicit requirement: every shipped pass through this point must not move).
     * 6. applyInjuryPigment + applyInjuryFixedCutouts — source-relative blend against whatever is
     *    already baked (environment already visible), plus EXPLODED/VOID/RANGED's permanent trauma
     *    cutout(s). Skipped entirely for WITHERED (Profile#paintsPigment()==false — the existing
     *    single wither-vein overlay already drawn in step 4 stays the only vein paint).
     * 7. applyHoles — existing global age/environment hole curve, retained exactly as shipped.
     * 8. applyInjuryAcceleratedHoles — C1's region-ahead formula, sampled against the SAME output
     *    global holes (7) already opened, so local acceleration counts toward (never blindly stacks
     *    on top of) the global target. Global-before-injury is a hard ordering requirement.
     * 9. applyFullPartReveal, now passed the resolved {@code CorpseInjuryMask.revealPreference()} (or
     *    {@code null} for NATURAL) — activation gate, cumulativeChance/activationScore and clearing
     *    behaviour are all unchanged; only the chosen ARCHETYPE can be cause-biased.
     * </pre>
     *
     * Step 3 MUST stay before step 5 (blotches)/7 (holes)/9 (reveal): patches only ever darken/retint
     * texels that are still opaque at this point, never restoring alpha, so running it later would
     * visually "heal" flesh decay already removed (C.6 / G's "Patches restore deleted flesh" failure
     * mode). Steps 6/8 MUST stay exactly where they are relative to 5/7/9 for the identical reason —
     * injury pigment must not paint over decay-removed flesh, and injury holes must count against (not
     * ignore) what global holes already opened.
     */
    private NativeImage bake(SourceSnapshot source, SourceKey sourceKey, BakeKey key,
                             CorpseRenderState state, EnvironmentalRenderInputs env,
                             int capturedEpoch, long appearanceSeed, InjuryRenderInputs injuryInputs) throws IOException {
        int[] output = getSourcePixels(source, sourceKey).clone();
        CorpseFleshMask mask = CorpseFleshMask.get(source.slim);
        CorpseAnatomy anatomy = CorpseAnatomy.get(source.slim);
        CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(appearanceSeed, source.slim, mask);

        for (int i = 0; i < mask.surfaceSize(); i++) {
            CorpseFleshMask.SurfaceTexel texel = mask.texel(i);
            output[texel.baseIndex()] = FastColor.ABGR32.opaque(output[texel.baseIndex()]);
        }
        applyHsv(output, state);

        float frostLevel = clamp01(env.frostLevel());
        if (frostLevel > 0.0F) {
            applyFrostSkinRecolor(output, state.decayStage, frostLevel);
        }

        // Age-driven wet-rot detail composite — cross-faded down by scorchLevel so the desert/
        // nether-ash recolor visibly takes over as scorch rises (desert_desiccation.md /
        // nether_ash_scorch.md §6's own dependency note). Calcify/frost/wither leave this alone and
        // simply layer their own passes on top.
        float scorchLevel = clamp01(env.scorchLevel());
        float wetRotWeight = 1.0F - scorchLevel;
        if (state.decayStage != DecayStage.FRESH) {
            DecayStage previous = DecayStage.values()[state.decayStage.ordinal() - 1];
            if (previous != DecayStage.FRESH) {
                compositeDetail(output, detailTexture(previous),
                        (1.0F - state.stageProgress) * wetRotWeight, capturedEpoch);
            }
            compositeDetail(output, detailTexture(state.decayStage), state.stageProgress * wetRotWeight,
                    capturedEpoch);
        }

        // Gap 3 (see this method's ordering javadoc): the seeded patch-stitch pass. Must run here —
        // after wet-rot detail, before wither/scorch/calcify/frost detail — per SOL-PATCH-STITCH-
        // DESIGN.md C.6 and RECONCILIATION.md R4.
        CorpsePatchStitcher.applyPatchStitch(output, mask, anatomy, layout, state, env);

        if (env.witherScarred()) {
            float veinStrength = 0.20F + 0.80F * (1.0F - state.bodyFullness);
            compositeDetail(output, WITHER_OVERLAY_TEXTURE, veinStrength, capturedEpoch);
        }

        composeScorchDetail(output, state.decayStage, state.stageProgress, scorchLevel,
                env.scorchStyle(), capturedEpoch);
        composeCalcifyDetail(output, clamp01(env.calcifyLevel()), capturedEpoch);
        if (frostLevel > 0.0F) {
            compositeDetail(output, frostedDetailTexture(state.decayStage), frostLevel, capturedEpoch);
        }

        applyProceduralBlotches(output, mask, layout, state);

        CorpseInjuryMask injury = CorpseInjuryMask.get(injuryInputs, source.slim);
        EnvironmentAttenuation attenuation = computeEnvironmentAttenuation(key, env, injuryInputs.deathCause());
        applyInjuryPigment(output, mask, injury, state, attenuation);
        applyInjuryFixedCutouts(output, mask, injury, state.decayStage);

        applyHoles(output, mask, layout, state.holeCoverage);
        applyInjuryAcceleratedHoles(output, mask, injury, state, attenuation.decayMultiplier());

        layout.applyFullPartReveal(output, state.decayStage, state.stageProgress,
                state.externalRevealBoost, injury.revealPreference());

        NativeImage image = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) image.setPixelRGBA(x, y, output[x + y * 64]);
        }
        return image;
    }

    /**
     * desert_sun (crossfades detail_desert/* between adjacent stages, mirroring the wet-rot
     * pattern — but skips FRESH entirely, since desert_desiccation.md deliberately ships no
     * {@code detail_desert/fresh.png}: desert's FRESH read is pure HSV blend) or nether_ash
     * (composites detail_ash_nether/*, including a direct FRESH call with no previous-stage
     * partner, since nether_ash_scorch.md ships a fresh.png and wants it visible at FRESH).
     */
    private void composeScorchDetail(int[] output, DecayStage stage, float stageProgress,
                                     float scorchLevel, String style, int capturedEpoch) {
        if (scorchLevel <= 0.0F || style == null || style.isEmpty()) return;

        if (EnvironmentalRenderInputs.SCORCH_STYLE_DESERT.equals(style)) {
            if (stage == DecayStage.FRESH) return;
            DecayStage previous = DecayStage.values()[stage.ordinal() - 1];
            if (previous != DecayStage.FRESH) {
                compositeDetail(output, desertDetailTexture(previous),
                        (1.0F - stageProgress) * scorchLevel, capturedEpoch);
            }
            compositeDetail(output, desertDetailTexture(stage), stageProgress * scorchLevel, capturedEpoch);
        } else if (EnvironmentalRenderInputs.SCORCH_STYLE_NETHER.equals(style)) {
            if (stage == DecayStage.FRESH) {
                compositeDetail(output, netherAshDetailTexture(DecayStage.FRESH), scorchLevel, capturedEpoch);
                return;
            }
            DecayStage previous = DecayStage.values()[stage.ordinal() - 1];
            if (previous != DecayStage.FRESH) {
                compositeDetail(output, netherAshDetailTexture(previous),
                        (1.0F - stageProgress) * scorchLevel, capturedEpoch);
            }
            compositeDetail(output, netherAshDetailTexture(stage), stageProgress * scorchLevel, capturedEpoch);
        }
    }

    /** Crossfades the three calcify severity sheets (light/moderate/heavy) across the full
     *  0..1 {@code calcifyLevel} range, the same two-adjacent-sheet blend shape the age ladder uses
     *  for decay stages — cave_calcification.md §6 dependency 5. */
    private void composeCalcifyDetail(int[] output, float calcifyLevel, int capturedEpoch) {
        if (calcifyLevel <= 0.0F) return;
        float third = 1.0F / 3.0F;
        if (calcifyLevel < third) {
            compositeDetail(output, calcifyDetailTexture("light"), calcifyLevel / third, capturedEpoch);
        } else if (calcifyLevel < 2.0F * third) {
            float local = (calcifyLevel - third) / third;
            compositeDetail(output, calcifyDetailTexture("light"), 1.0F - local, capturedEpoch);
            compositeDetail(output, calcifyDetailTexture("moderate"), local, capturedEpoch);
        } else {
            float local = (calcifyLevel - 2.0F * third) / third;
            compositeDetail(output, calcifyDetailTexture("moderate"), 1.0F - local, capturedEpoch);
            compositeDetail(output, calcifyDetailTexture("heavy"), local, capturedEpoch);
        }
    }

    /**
     * frozen_rime.md §2 layer 2 / §3.1 — a second, post-{@link #applyHsv} pass blending every
     * already-tinted opaque skin pixel toward a per-pixel HSV "frost target" (hue rotated 85% of
     * the way to 208°, saturation capped per stage, value floor-raised) with weight {@code
     * frostLevel}. {@code compositeDetail} alone only touches the decay-art overlay, not the base
     * skin pixels underneath it, so without this pass the skin between decay blotches would stay
     * the normal flesh tone even at {@code frostLevel 1.0}.
     */
    private static void applyFrostSkinRecolor(int[] output, DecayStage stage, float frostLevel) {
        float satCap = FROST_SAT_CAP[Math.min(stage.ordinal(), FROST_SAT_CAP.length - 1)];
        float[] hsb = new float[3];
        for (int i = 0; i < output.length; i++) {
            int pixel = output[i];
            int alpha = FastColor.ABGR32.alpha(pixel);
            if (alpha == 0) continue;
            int red = FastColor.ABGR32.red(pixel);
            int green = FastColor.ABGR32.green(pixel);
            int blue = FastColor.ABGR32.blue(pixel);
            Color.RGBtoHSB(red, green, blue, hsb);
            float hueDeg = hsb[0] * 360.0F;
            float delta = shortestHueDelta(hueDeg, FROST_TARGET_HUE_DEG);
            float newHueDeg = hueDeg + delta * FROST_HUE_BLEND;
            float newHue = (newHueDeg / 360.0F) % 1.0F;
            if (newHue < 0.0F) newHue += 1.0F;
            float newSat = hsb[1] <= satCap ? lerpF(hsb[1], satCap, 0.45F) : satCap;
            newSat = clampF(newSat, 0.05F, satCap);
            float newVal = clampF(0.55F + hsb[2] * 0.42F, 0.0F, 0.97F);
            int targetRgb = Color.HSBtoRGB(newHue, newSat, newVal);
            int targetRed = (targetRgb >> 16) & 0xFF;
            int targetGreen = (targetRgb >> 8) & 0xFF;
            int targetBlue = targetRgb & 0xFF;
            int finalRed = lerpChannel(red, targetRed, frostLevel);
            int finalGreen = lerpChannel(green, targetGreen, frostLevel);
            int finalBlue = lerpChannel(blue, targetBlue, frostLevel);
            output[i] = FastColor.ABGR32.color(alpha, finalBlue, finalGreen, finalRed);
        }
    }

    private static float shortestHueDelta(float fromDeg, float toDeg) {
        float delta = (toDeg - fromDeg) % 360.0F;
        if (delta > 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return delta;
    }

    private static float lerpF(float from, float to, float weight) {
        return from + (to - from) * weight;
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clampF(value, 0.0F, 1.0F);
    }

    private int[] getSourcePixels(SourceSnapshot source, SourceKey key) throws IOException {
        synchronized (lock) {
            int[] cached = sourceImages.get(key);
            if (cached != null) return cached;
        }

        NativeImage raw;
        if (source.cacheFile != null) {
            if (!source.cacheFile.isFile()) throw new IOException("Skin cache file is absent: " + source.cacheFile);
            try (InputStream input = new java.io.FileInputStream(source.cacheFile)) {
                raw = NativeImage.read(input);
            }
        } else {
            try (InputStream input = Minecraft.getInstance().getResourceManager().open(source.location)) {
                raw = NativeImage.read(input);
            }
        }
        int[] normalised = normaliseSkin(raw);
        synchronized (lock) {
            sourceImages.put(key, normalised);
            while (sourceImages.size() > MAX_SOURCE_IMAGES) {
                sourceImages.remove(sourceImages.keySet().iterator().next());
            }
        }
        return normalised;
    }

    /** Reimplementation of HttpTexture.processLegacySkin; raw is always consumed and closed. */
    static int[] normaliseSkin(NativeImage raw) throws IOException {
        int width = raw.getWidth();
        int height = raw.getHeight();
        if (width != 64 || (height != 32 && height != 64)) {
            raw.close();
            throw new IOException("Malformed player skin dimensions " + width + "x" + height);
        }

        boolean legacy = height == 32;
        NativeImage image = raw;
        if (legacy) {
            image = new NativeImage(64, 64, true);
            image.copyFrom(raw);
            raw.close();
            image.fillRect(0, 32, 64, 32, 0);
            image.copyRect(4, 16, 16, 32, 4, 4, true, false);
            image.copyRect(8, 16, 16, 32, 4, 4, true, false);
            image.copyRect(0, 20, 24, 32, 4, 12, true, false);
            image.copyRect(4, 20, 16, 32, 4, 12, true, false);
            image.copyRect(8, 20, 8, 32, 4, 12, true, false);
            image.copyRect(12, 20, 16, 32, 4, 12, true, false);
            image.copyRect(44, 16, -8, 32, 4, 4, true, false);
            image.copyRect(48, 16, -8, 32, 4, 4, true, false);
            image.copyRect(40, 20, 0, 32, 4, 12, true, false);
            image.copyRect(44, 20, -8, 32, 4, 12, true, false);
            image.copyRect(48, 20, -16, 32, 4, 12, true, false);
            image.copyRect(52, 20, -8, 32, 4, 12, true, false);
        }

        setNoAlpha(image, 0, 0, 32, 16);
        if (legacy) doNotchTransparencyHack(image, 32, 0, 64, 32);
        setNoAlpha(image, 0, 16, 64, 32);
        setNoAlpha(image, 16, 48, 48, 64);

        int[] pixels = new int[4096];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) pixels[x + y * 64] = image.getPixelRGBA(x, y);
        }
        image.close();
        return pixels;
    }

    private static void doNotchTransparencyHack(NativeImage image, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if (FastColor.ABGR32.alpha(image.getPixelRGBA(x, y)) < 128) return;
            }
        }
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                image.setPixelRGBA(x, y, FastColor.ABGR32.transparent(image.getPixelRGBA(x, y)));
            }
        }
    }

    private static void setNoAlpha(NativeImage image, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                image.setPixelRGBA(x, y, FastColor.ABGR32.opaque(image.getPixelRGBA(x, y)));
            }
        }
    }

    private void compositeDetail(int[] output, ResourceLocation location, float strength,
                                 int capturedEpoch) {
        if (strength <= 0.0F) return;
        int[] detail = loadAsset(location, capturedEpoch);
        if (detail == null) return;
        compositeImage(output, detail, strength);
    }

    private static void compositeImage(int[] output, @Nullable int[] detail, float strength) {
        if (detail == null || strength <= 0.0F) return;
        for (int i = 0; i < output.length; i++) {
            int source = output[i];
            if (FastColor.ABGR32.alpha(source) == 0) continue;
            float weight = FastColor.ABGR32.alpha(detail[i]) / 255.0F * strength;
            if (weight <= 0.0F) continue;
            int red = lerpChannel(FastColor.ABGR32.red(source), FastColor.ABGR32.red(detail[i]), weight);
            int green = lerpChannel(FastColor.ABGR32.green(source), FastColor.ABGR32.green(detail[i]), weight);
            int blue = lerpChannel(FastColor.ABGR32.blue(source), FastColor.ABGR32.blue(detail[i]), weight);
            output[i] = FastColor.ABGR32.color(FastColor.ABGR32.alpha(source), blue, green, red);
        }
    }

    private static void applyHsv(int[] output, CorpseRenderState state) {
        float[] hsb = new float[3];
        for (int i = 0; i < output.length; i++) {
            int pixel = output[i];
            int alpha = FastColor.ABGR32.alpha(pixel);
            if (alpha == 0) continue;
            Color.RGBtoHSB(FastColor.ABGR32.red(pixel), FastColor.ABGR32.green(pixel),
                    FastColor.ABGR32.blue(pixel), hsb);
            float hue = (hsb[0] + state.hueShiftDegrees / 360.0F) % 1.0F;
            float saturation = Math.min(1.0F, hsb[1] * state.saturationMultiplier);
            float value = Math.min(1.0F, hsb[2] * state.valueMultiplier);
            int rgb = Color.HSBtoRGB(hue, saturation, value);
            output[i] = FastColor.ABGR32.color(alpha, rgb & 0xFF, (rgb >> 8) & 0xFF,
                    (rgb >> 16) & 0xFF);
        }
    }

    private static void applyProceduralBlotches(int[] output, CorpseFleshMask mask,
                                                CorpseAppearanceLayout layout, CorpseRenderState state) {
        boolean[] occupied = new boolean[mask.surfaceSize()];
        int secondaryNeeded = state.secondaryBlotchRgb == 0 ? 0
                : Math.round(state.secondaryBlotchCoverage * mask.surfaceSize());
        applyBlotchRank(output, occupied, mask,
                layout.secondaryBlotchRank(state.secondaryBlotchMinDiameter,
                        state.secondaryBlotchMaxDiameter),
                state.secondaryBlotchRgb, secondaryNeeded);
        int primaryNeeded = Math.round(state.primaryBlotchCoverage * mask.surfaceSize());
        applyBlotchRank(output, occupied, mask,
                layout.primaryBlotchRank(state.blotchMinDiameter, state.blotchMaxDiameter),
                state.primaryBlotchRgb, primaryNeeded);
    }

    /**
     * Playtest fix (2026-08-23, user screenshots: "large, single shaded green patches" at MID/LATE
     * decay). This pass's ordering position is unchanged (still gap 5, still after patch-stitch/
     * environment detail, still before injury pigment/holes/reveal — see {@code bake()}'s ordering
     * javadoc); only the per-texel colour math below changed. Previously every occupied texel was
     * stamped with the exact same flat {@code rgb} via a hard, fully-opaque overwrite (old {@code
     * colourTexel}) — at MID_DECAY/LATE_DECAY's 24%/42% coverage that reads as one giant single-shade
     * sticker instead of necrotic mottling, and completely erases whatever skin detail (hair, folds,
     * clothing) was underneath. Each texel now blends toward a depth-driven shade — darker near its
     * blob's own centre, lighter toward the edge, using the same normalized distance {@link
     * CorpseAppearanceLayout.BlotchTexel#depth()} already tracks — at a partial (never quite 1.0)
     * weight, so real per-texel skin detail keeps showing through instead of being wiped flat.
     */
    private static void applyBlotchRank(int[] output, boolean[] occupied, CorpseFleshMask mask,
                                        CorpseAppearanceLayout.BlotchTexel[] rankOrder, int rgb, int needed) {
        int darkRgb = darkenRgb(rgb, 0.60F);
        int applied = 0;
        for (CorpseAppearanceLayout.BlotchTexel ranked : rankOrder) {
            if (applied >= needed) break;
            int index = ranked.index();
            if (occupied[index]) continue;
            CorpseFleshMask.SurfaceTexel texel = mask.texel(index);
            float depth = clamp01(ranked.depth());
            int shaded = lerpRgb(rgb, darkRgb, depth * 0.65F);
            float weight = lerpF(0.60F, 0.92F, depth);
            blendTexel(output, texel.baseIndex(), shaded, weight);
            blendTexel(output, texel.outerIndex(), shaded, weight);
            occupied[index] = true;
            applied++;
        }
    }

    private static int darkenRgb(int rgb, float factor) {
        int r = clampChannel(Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = clampChannel(Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = clampChannel(Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpRgb(int from, int to, float weight) {
        float w = clamp01(weight);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, w);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, w);
        int b = lerpChannel(from & 0xFF, to & 0xFF, w);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void applyHoles(int[] output, CorpseFleshMask mask, CorpseAppearanceLayout layout,
                                   float coverage) {
        int count = Math.min(layout.maximumHoleCount(), Math.round(coverage * mask.surfaceSize()));
        for (int rank = 0; rank < count; rank++) {
            CorpseFleshMask.SurfaceTexel texel = mask.texel(layout.holeIndex(rank));
            output[texel.baseIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
            output[texel.outerIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
        }
    }

    // ── Death-cause injury (SOL-DEATHCAUSE-INJURY-DESIGN.md, bake() gaps 6/8 — see the ordering
    // javadoc on bake() itself) ─────────────────────────────────────────────────────────────────────

    /** Section "Stage evolution shared by all profiles": coloured footprint multiplier, FRESH..
     *  ADVANCED_DECAY (index = {@code DecayStage.ordinal()}; SKELETAL/COLLAPSE never reach here —
     *  {@code resolve()} returns null at both before any bake is ever requested, per
     *  RECONCILIATION.md R7 and this stage's own no-flesh redefinition). */
    private static final float[] INJURY_STAGE_FOOTPRINT_MULT = {1.00F, 1.15F, 1.35F, 1.55F, 1.70F};
    /** Same section: pigment blend-weight multiplier, FRESH..ADVANCED_DECAY. */
    private static final float[] INJURY_STAGE_PIGMENT_MULT = {1.00F, 0.90F, 0.68F, 0.46F, 0.28F};

    /** D1's bucket-representative tables — read from {@link BakeKey}'s already-quantised bucket ints
     *  (F1 step 6: "injury attenuation uses bucket representatives, NOT raw tracker floats" — using
     *  the continuous tracker would let one {@link BakeKey} serve visibly different injury bakes). */
    private static final float[] CHAR_BUCKET_REPS = {0.00F, 0.35F, 0.60F, 0.80F, 0.97F};
    private static final float[] CALCIFY_BUCKET_REPS = {0.00F, 0.325F, 0.65F, 0.90F};

    // D1's per-case full-intensity multipliers (pigment, then local-decay).
    private static final float CHAR_PIGMENT_FULL = 0.25F, CHAR_DECAY_FULL = 0.55F;
    private static final float DESERT_PIGMENT_FULL = 0.55F, DESERT_DECAY_FULL = 0.65F;
    private static final float NETHER_PIGMENT_FULL = 0.45F, NETHER_DECAY_FULL = 0.60F;
    private static final float FROST_PIGMENT_FULL = 0.50F, FROST_DECAY_FULL = 0.65F;
    private static final float OCEAN_PIGMENT_FULL = 0.60F, OCEAN_DECAY_FULL = 0.75F;
    private static final float CALCIFY_PIGMENT_FULL = 0.50F, CALCIFY_DECAY_FULL = 0.65F;
    private static final float WITHER_PIGMENT_FULL = 0.60F, WITHER_DECAY_FULL = 0.75F;

    /** Package-private (not private) — see {@link #computeEnvironmentAttenuation(int, int, String,
     *  int, int, int, boolean, DeathCause)}'s own javadoc for why. */
    record EnvironmentAttenuation(float pigmentMultiplier, float decayMultiplier) {}

    /**
     * D1's "strongest-owner wins" numeric attenuation: {@code min} across every active environmental
     * case of {@code lerp(1, full[e], bucketRepresentative[e])} — never a product (D1 explicitly warns
     * a product would make stacked causes vanish). Also applies the two D2 cells that give an explicit
     * NUMBER rather than prose-only recolour guidance (BURNED×char>=0.5 and DROWNED×ocean — see this
     * class's own "documented interpretation gaps" note on {@link DeathCauseInjuryProfile} for why the
     * remaining ~90 D2 prose cells are deliberately not individually encoded).
     */
    private static EnvironmentAttenuation computeEnvironmentAttenuation(BakeKey key, EnvironmentalRenderInputs env,
                                                                        DeathCause cause) {
        return computeEnvironmentAttenuation(key.charBucket, key.scorchBucket, key.scorchStyle,
                key.calcifyBucket, key.frostBucket, key.oceanBucket, env.witherScarred(), cause);
    }

    /**
     * The actual D1 math, taking {@link BakeKey}'s bucket fields as plain primitives rather than the
     * key itself — package-private (not private) so a same-package verification harness can exercise
     * this directly, without needing the {@code Minecraft.getInstance()}-dependent asset/resource-
     * manager stack {@code bake()} itself requires, and without {@link BakeKey} (which stays exactly
     * {@code private}, per RECONCILIATION.md R3's unchanged 14-field contract) ever needing its
     * visibility relaxed just to be constructible from a test.
     */
    static EnvironmentAttenuation computeEnvironmentAttenuation(int charBucket, int scorchBucket, String scorchStyle,
                                                                 int calcifyBucket, int frostBucket, int oceanBucket,
                                                                 boolean witherScarred, DeathCause cause) {
        float charRep = CHAR_BUCKET_REPS[charBucket];
        float scorchRep = scorchBucket / 4.0F;
        float frostRep = frostBucket / 4.0F;
        float calcifyRep = CALCIFY_BUCKET_REPS[calcifyBucket];
        float oceanRep = oceanBucket <= 0 ? 0.0F : (oceanBucket - 1) / 7.0F;
        float witherRep = witherScarred ? 1.0F : 0.0F;

        float scorchPigmentFull = 1.0F;
        float scorchDecayFull = 1.0F;
        if (EnvironmentalRenderInputs.SCORCH_STYLE_DESERT.equals(scorchStyle)) {
            scorchPigmentFull = DESERT_PIGMENT_FULL;
            scorchDecayFull = DESERT_DECAY_FULL;
        } else if (EnvironmentalRenderInputs.SCORCH_STYLE_NETHER.equals(scorchStyle)) {
            scorchPigmentFull = NETHER_PIGMENT_FULL;
            scorchDecayFull = NETHER_DECAY_FULL;
        }

        float pigment = 1.0F;
        pigment = Math.min(pigment, lerpF(1.0F, CHAR_PIGMENT_FULL, charRep));
        pigment = Math.min(pigment, lerpF(1.0F, scorchPigmentFull, scorchRep));
        pigment = Math.min(pigment, lerpF(1.0F, FROST_PIGMENT_FULL, frostRep));
        pigment = Math.min(pigment, lerpF(1.0F, OCEAN_PIGMENT_FULL, oceanRep));
        pigment = Math.min(pigment, lerpF(1.0F, CALCIFY_PIGMENT_FULL, calcifyRep));
        pigment = Math.min(pigment, lerpF(1.0F, WITHER_PIGMENT_FULL, witherRep));

        float decay = 1.0F;
        decay = Math.min(decay, lerpF(1.0F, CHAR_DECAY_FULL, charRep));
        decay = Math.min(decay, lerpF(1.0F, scorchDecayFull, scorchRep));
        decay = Math.min(decay, lerpF(1.0F, FROST_DECAY_FULL, frostRep));
        decay = Math.min(decay, lerpF(1.0F, OCEAN_DECAY_FULL, oceanRep));
        decay = Math.min(decay, lerpF(1.0F, CALCIFY_DECAY_FULL, calcifyRep));
        decay = Math.min(decay, lerpF(1.0F, WITHER_DECAY_FULL, witherRep));

        // D2 "Redundant special" cells with an explicit number (not just prose recolour guidance).
        if (cause == DeathCause.BURNED && charRep >= 0.5F) {
            pigment = Math.min(pigment, 0.10F);
            decay = Math.min(decay, 0.35F);
        }
        if (cause == DeathCause.DROWNED && oceanRep > 0.0F) {
            pigment = Math.min(pigment, lerpF(1.0F, 0.35F, oceanRep));
            decay = Math.min(decay, lerpF(1.0F, 0.60F, oceanRep));
        }
        return new EnvironmentAttenuation(pigment, decay);
    }

    /**
     * Bake gap 6, pigment half: source-relative blend of every band's ranked, connected growth (see
     * {@code CorpseInjuryMask#bands()}), plus up to three non-killing-arrow "entry shadow" darkenings
     * (E1). Band 0 (the cause's primary/dominant target) receives {@link Profile#coreRgb()} on its
     * inner ~70% of revealed texels and {@link Profile#edgeRgb()} at {@code 0.55x} weight on the outer
     * ~30% (the "halo" the design's {@code effectiveWeight} formula names) — later bands, always a
     * single simple blob, get the same core/edge split scaled down to their own bias-proportional
     * target size. Skipped entirely when {@link Profile#paintsPigment()} is false (WITHERED only —
     * CRITICAL CORRECTNESS: "skip general injury colour... to avoid double veining" with the existing
     * wither overlay already composited in bake() step 4).
     */
    // Package-private (not private): a same-package verification harness constructs a synthetic
    // opaque int[4096] canvas and exercises this directly — no Minecraft.getInstance() dependency.
    static void applyInjuryPigment(int[] output, CorpseFleshMask mask, CorpseInjuryMask injury,
                                           CorpseRenderState state, EnvironmentAttenuation attenuation) {
        DeathCauseInjuryProfile.Profile profile = injury.profile();
        List<CorpseInjuryMask.Band> bands = injury.bands();
        if (profile.paintsPigment() && !bands.isEmpty()) {
            float stagePigmentWeight = INJURY_STAGE_PIGMENT_MULT[state.decayStage.ordinal()];
            float stageFootprintMult = INJURY_STAGE_FOOTPRINT_MULT[state.decayStage.ordinal()];
            float effectiveWeight = Math.min(0.72F,
                    profile.pigmentWeight() * stagePigmentWeight * injury.variety() * attenuation.pigmentMultiplier());
            if (effectiveWeight > 0.0F) {
                float primaryBias = bands.get(0).bias();
                for (int i = 0; i < bands.size(); i++) {
                    CorpseInjuryMask.Band band = bands.get(i);
                    int baseTarget = i == 0 ? profile.primaryFreshTarget()
                            : Math.round(profile.primaryFreshTarget() * (band.bias() / primaryBias));
                    int target = Math.min(band.rank().length,
                            Math.round(baseTarget * injury.variety() * stageFootprintMult));
                    paintBand(output, mask, band.rank(), target, profile.coreRgb(), profile.edgeRgb(), effectiveWeight);
                }
            }
        }

        float shadowWeight = 0.20F * INJURY_STAGE_PIGMENT_MULT[state.decayStage.ordinal()] * attenuation.pigmentMultiplier();
        if (shadowWeight > 0.0F) {
            for (int[] disc : injury.entryShadowTexels()) {
                for (int index : disc) {
                    CorpseFleshMask.SurfaceTexel texel = mask.texel(index);
                    darkenTexel(output, texel.baseIndex(), shadowWeight);
                    darkenTexel(output, texel.outerIndex(), shadowWeight);
                }
            }
        }
    }

    /** {@code target} leading ranked texels: the inner ~70% (by rank order, closest to the growth
     *  anchor) take {@code coreRgb} at {@code weight}; the outer ~30% (the "halo") take {@code edgeRgb}
     *  at {@code 0.55 * weight} — the design's own explicit halo-weight fraction. */
    private static void paintBand(int[] output, CorpseFleshMask mask, int[] rank, int target,
                                  int coreRgb, int edgeRgb, float weight) {
        if (target <= 0) return;
        int haloStart = Math.round(target * 0.7F);
        for (int r = 0; r < target; r++) {
            CorpseFleshMask.SurfaceTexel texel = mask.texel(rank[r]);
            boolean halo = r >= haloStart;
            int rgb = halo ? edgeRgb : coreRgb;
            float texelWeight = halo ? 0.55F * weight : weight;
            blendTexel(output, texel.baseIndex(), rgb, texelWeight);
            blendTexel(output, texel.outerIndex(), rgb, texelWeight);
        }
    }

    /** Bake gap 6, cutout half: EXPLODED's 4/ VOID's 2/ RANGED's (conditional) 1 permanent trauma
     *  texels. VOID's is additionally gated to EARLY_DECAY+ ({@link Profile#cutoutDelayedToEarlyDecay()}
     *  — A1/A2: "0 at FRESH, add one connected 2-texel notch at EARLY"); every other cause's cutout (if
     *  any) is present from FRESH onward, matching "physical wounds, not accelerated decay". */
    static void applyInjuryFixedCutouts(int[] output, CorpseFleshMask mask, CorpseInjuryMask injury,
                                                DecayStage stage) {
        int[] cutout = injury.fixedCutoutTexels();
        if (cutout.length == 0) return;
        if (injury.profile().cutoutDelayedToEarlyDecay() && stage == DecayStage.FRESH) return;
        for (int index : cutout) {
            CorpseFleshMask.SurfaceTexel texel = mask.texel(index);
            output[texel.baseIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
            output[texel.outerIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
        }
    }

    /**
     * Bake gap 8: section C1's region-ahead formula, one band at a time. Samples {@link
     * CorpseRenderState#holeCurve(int)} at both the corpse's real {@code visualStep} and an
     * accelerated {@code pLocal}-derived step so the local target is always expressed as "how much
     * FURTHER ahead than age alone would predict", never an independent absolute value — then reveals
     * additional ranked, not-yet-transparent texels (after global holes already ran) until that target
     * count is met, exactly as C1 specifies ("count already-transparent base texels... clear the next
     * connected, not-yet-transparent ranked texels").
     */
    static void applyInjuryAcceleratedHoles(int[] output, CorpseFleshMask mask, CorpseInjuryMask injury,
                                                     CorpseRenderState state, float environmentDecayMultiplier) {
        int visualStep = state.visualStep;
        float p = visualStep / 63.0F;
        float accelGate = clamp01((visualStep - 4) / 19.0F);
        float hp = CorpseRenderState.holeCurve(visualStep);
        for (CorpseInjuryMask.Band band : injury.bands()) {
            int[] rank = band.rank();
            if (rank.length == 0) continue;
            float b = band.bias() * injury.variety();
            float pLocal = clamp01(p + accelGate * b * (1.0F - p));
            int localStep = Math.round(pLocal * 63.0F);
            float hpLocal = CorpseRenderState.holeCurve(localStep);
            float desired = Math.min(0.68F,
                    clamp01(state.holeCoverage + Math.max(0.0F, hpLocal - hp) * environmentDecayMultiplier));
            int targetCount = Math.round(desired * rank.length);

            int alreadyTransparent = 0;
            for (int index : rank) {
                if (FastColor.ABGR32.alpha(output[mask.texel(index).baseIndex()]) == 0) alreadyTransparent++;
            }
            int need = targetCount - alreadyTransparent;
            int revealed = 0;
            for (int index : rank) {
                if (revealed >= need) break;
                CorpseFleshMask.SurfaceTexel texel = mask.texel(index);
                if (FastColor.ABGR32.alpha(output[texel.baseIndex()]) == 0) continue;
                output[texel.baseIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
                output[texel.outerIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
                revealed++;
            }
        }
    }

    private static void blendTexel(int[] output, int index, int targetRgb, float weight) {
        int pixel = output[index];
        int alpha = FastColor.ABGR32.alpha(pixel);
        if (alpha == 0 || weight <= 0.0F) return;
        int red = lerpChannel(FastColor.ABGR32.red(pixel), (targetRgb >> 16) & 0xFF, weight);
        int green = lerpChannel(FastColor.ABGR32.green(pixel), (targetRgb >> 8) & 0xFF, weight);
        int blue = lerpChannel(FastColor.ABGR32.blue(pixel), targetRgb & 0xFF, weight);
        output[index] = FastColor.ABGR32.color(alpha, blue, green, red);
    }

    /** Entry-shadow blend target: half-darkened source colour, not a fixed palette entry — a
     *  "shadow" reads as tonal, not as a second pigment family layered onto the flesh. */
    private static void darkenTexel(int[] output, int index, float weight) {
        int pixel = output[index];
        int alpha = FastColor.ABGR32.alpha(pixel);
        if (alpha == 0 || weight <= 0.0F) return;
        int red = FastColor.ABGR32.red(pixel);
        int green = FastColor.ABGR32.green(pixel);
        int blue = FastColor.ABGR32.blue(pixel);
        int targetRed = Math.round(red * 0.5F);
        int targetGreen = Math.round(green * 0.5F);
        int targetBlue = Math.round(blue * 0.5F);
        output[index] = FastColor.ABGR32.color(alpha,
                lerpChannel(blue, targetBlue, weight),
                lerpChannel(green, targetGreen, weight),
                lerpChannel(red, targetRed, weight));
    }

    @Nullable
    private int[] loadAsset(ResourceLocation location, int capturedEpoch) {
        synchronized (lock) {
            int[] cached = assetImages.get(location);
            if (cached != null) return cached;
            if (missingAssets.contains(location)) return null;
        }
        try (InputStream input = Minecraft.getInstance().getResourceManager().open(location)) {
            NativeImage image = NativeImage.read(input);
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                image.close();
                throw new IOException("Expected 64x64, found " + image.getWidth() + "x" + image.getHeight());
            }
            int[] pixels = new int[4096];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) pixels[x + y * 64] = image.getPixelRGBA(x, y);
            }
            image.close();
            synchronized (lock) {
                if (capturedEpoch == detailEpoch) assetImages.put(location, pixels);
            }
            return pixels;
        } catch (IOException exception) {
            synchronized (lock) {
                missingAssets.add(location);
            }
            logOnce("asset:" + location, "Missing or invalid corpse flesh asset " + location, exception);
            return null;
        }
    }

    private boolean resourceExists(ResourceLocation location) {
        synchronized (lock) {
            if (missingAssets.contains(location)) return false;
        }
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        if (resources.getResource(location).isPresent()) return true;
        synchronized (lock) {
            missingAssets.add(location);
        }
        logOnce("asset:" + location, "Missing corpse flesh fallback " + location, null);
        return false;
    }

    private static ResourceLocation detailTexture(DecayStage stage) {
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/detail/"
                + stageName(stage) + ".png");
    }

    private static ResourceLocation desertDetailTexture(DecayStage stage) {
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/detail_desert/"
                + stageName(stage) + ".png");
    }

    private static ResourceLocation netherAshDetailTexture(DecayStage stage) {
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/detail_ash_nether/"
                + stageName(stage) + ".png");
    }

    private static ResourceLocation frostedDetailTexture(DecayStage stage) {
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/detail/frosted/"
                + stageName(stage) + ".png");
    }

    private static ResourceLocation calcifyDetailTexture(String severity) {
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/detail/calcify/"
                + severity + ".png");
    }

    /** {@code frostLevel >= 0.5} swaps in the {@code frosted/} fallback family wholesale (a hard
     *  threshold, not a blend — {@code fallbackTexture()} returns one fixed {@code ResourceLocation}
     *  per frozen_rime.md §5, mirroring how {@code CorpseRenderer}'s own char bone-swap is a binary
     *  threshold rather than a continuous blend). */
    private static ResourceLocation fallbackTexture(DecayStage stage, boolean slim, float frostLevel) {
        String arm = slim ? "slim/" : "wide/";
        if (frostLevel >= 0.5F) {
            return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/fallback/"
                    + arm + "frosted/" + stageName(stage) + ".png");
        }
        return ResourceLocation.parse("whatremains:textures/entity/corpse_flesh/fallback/"
                + arm + stageName(stage) + ".png");
    }

    /**
     * ADVANCED_DECAY reuses the "skeletal" asset family verbatim — it's the OLD SKELETAL stage,
     * unrenamed assets and all, so no new PNGs are needed for it. True SKELETAL/COLLAPSE are
     * defensively mapped here too (also "skeletal") but this branch is never actually reached for
     * either: {@link #resolve} nulls out (never requests a bake) at both, so nothing ever calls
     * {@link #detailTexture}/{@link #fallbackTexture}/etc. with those two stages in practice.
     */
    private static String stageName(DecayStage stage) {
        return switch (stage) {
            case FRESH -> "fresh";
            case EARLY_DECAY -> "early_decay";
            case MID_DECAY -> "mid_decay";
            case LATE_DECAY -> "late_decay";
            case ADVANCED_DECAY, SKELETAL, COLLAPSE -> "skeletal";
        };
    }

    private void logOnce(String key, String message, @Nullable Throwable failure) {
        synchronized (lock) {
            if (!loggedFailures.add(key)) return;
        }
        if (failure == null) LOGGER.warn(message);
        else LOGGER.warn(message, failure);
    }

    private static int lerpChannel(int from, int to, float weight) {
        return Math.round(from + (to - from) * Math.min(1.0F, weight));
    }

    public record ResolvedFlesh(ResourceLocation texture, boolean slim) {}

    /**
     * {@code charBucket}/{@code scorchBucket}/{@code scorchStyle}/{@code calcifyBucket}/
     * {@code frostBucket}/{@code oceanBucket} are the quantised environmental-case buckets
     * (RECONCILIATION.md §2C / §7.1 shared prerequisite) — without them two same-age corpses in
     * different environments (or the same corpse before/after an environmental change) would share
     * one cached bake outright. See the bucket-quantisation helpers next to {@link #keyFor}.
     */
    private record BakeKey(UUID corpseUuid, UUID ownerUuid, ResourceLocation resolvedSkin,
                           String sourceRevision, boolean slim, DecayStage stage, int visualStep,
                           int detailEpoch, int charBucket, int scorchBucket, String scorchStyle,
                           int calcifyBucket, int frostBucket, int oceanBucket) {}
    private record SourceKey(UUID ownerUuid, ResourceLocation location, String revision,
                             boolean slim, int detailEpoch) {}
    private record SourceSnapshot(UUID ownerUuid, ResourceLocation location, String revision,
                                  boolean slim, @Nullable File cacheFile, long generation) {}
    private record BakeResult(BakeKey key, SourceKey sourceKey, long generation, int epoch,
                              @Nullable NativeImage image, @Nullable Throwable failure) {}
    private record DeferredRelease(ResourceLocation location, long releaseFrame) {}

    private static final class OwnerSource {
        private final UUID ownerUuid;
        private final ResourceLocation location;
        private final String revision;
        private final boolean slim;
        @Nullable private final File cacheFile;
        private final long generation;
        @Nullable private UUID lastCorpseUuid;
        @Nullable private CorpseRenderState lastState;
        private EnvironmentalRenderInputs lastEnv = EnvironmentalRenderInputs.NONE;
        private double lastDistanceSquared;
        /** So the real-skin-arrived callback in {@link #requestRealSkin} can repeat the exact same
         *  bake request — appearance seed is a value snapshot, not something re-read from a live
         *  entity (SOL-PATCH-STITCH-DESIGN.md F.1 step 7). */
        private long lastAppearanceSeed;
        /** Same value-snapshot discipline for the death-cause injury inputs. */
        private InjuryRenderInputs lastInjury = InjuryRenderInputs.NONE;

        private OwnerSource(UUID ownerUuid, ResourceLocation location, String revision, boolean slim,
                            @Nullable File cacheFile, long generation) {
            this.ownerUuid = ownerUuid;
            this.location = location;
            this.revision = revision;
            this.slim = slim;
            this.cacheFile = cacheFile;
            this.generation = generation;
        }

        private SourceSnapshot snapshot() {
            return new SourceSnapshot(ownerUuid, location, revision, slim, cacheFile, generation);
        }
    }

    private static final class BakedTexture {
        private final ResourceLocation location;
        private long lastUsedNanos;
        private long lastUsedFrame;

        private BakedTexture(ResourceLocation location, long lastUsedNanos, long lastUsedFrame) {
            this.location = location;
            this.lastUsedNanos = lastUsedNanos;
            this.lastUsedFrame = lastUsedFrame;
        }

        private void touch(long frame) {
            lastUsedNanos = System.nanoTime();
            lastUsedFrame = frame;
        }
    }

    private final class BakeTask implements Runnable, Comparable<BakeTask> {
        private final BakeKey key;
        private final SourceKey sourceKey;
        private final SourceSnapshot source;
        private final CorpseRenderState state;
        private final EnvironmentalRenderInputs env;
        private final double distanceSquared;
        private final long sequence;
        private final int epoch;
        private final long lifecycle;
        private final long appearanceSeed;
        private final InjuryRenderInputs injury;

        private BakeTask(BakeKey key, SourceKey sourceKey, SourceSnapshot source,
                         CorpseRenderState state, EnvironmentalRenderInputs env, double distanceSquared,
                         long sequence, int epoch, long lifecycle, long appearanceSeed, InjuryRenderInputs injury) {
            this.key = key;
            this.sourceKey = sourceKey;
            this.source = source;
            this.state = state;
            this.env = env;
            this.distanceSquared = distanceSquared;
            this.sequence = sequence;
            this.epoch = epoch;
            this.lifecycle = lifecycle;
            this.appearanceSeed = appearanceSeed;
            this.injury = injury;
        }

        @Override
        public void run() {
            try {
                NativeImage image = bake(source, sourceKey, key, state, env, epoch, appearanceSeed, injury);
                if (lifecycle != lifecycleGeneration) {
                    image.close();
                    return;
                }
                completed.add(new BakeResult(key, sourceKey, source.generation, epoch, image, null));
            } catch (Throwable failure) {
                if (lifecycle == lifecycleGeneration) {
                    completed.add(new BakeResult(key, sourceKey, source.generation, epoch, null, failure));
                }
            }
        }

        @Override
        public int compareTo(BakeTask other) {
            int distance = Double.compare(distanceSquared, other.distanceSquared);
            return distance != 0 ? distance : Long.compare(sequence, other.sequence);
        }
    }
}
