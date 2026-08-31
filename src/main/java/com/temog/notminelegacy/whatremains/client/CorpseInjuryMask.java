package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Part;
import com.temog.notminelegacy.whatremains.client.DeathCauseInjuryProfile.Profile;
import com.temog.notminelegacy.whatremains.client.DeathCauseInjuryProfile.RegionBand;
import com.temog.notminelegacy.whatremains.client.DeathCauseInjuryProfile.ResolvedLocus;
import com.temog.notminelegacy.whatremains.entity.DeathCause;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import javax.annotation.Nullable;

/**
 * SOL-DEATHCAUSE-INJURY-DESIGN.md F1 step 4 — the corpse-specific (as opposed to {@link
 * DeathCauseInjuryProfile}'s corpse-agnostic) half of the injury system: turns one {@link
 * ResolvedLocus} into ranked, connected texel-index arrays a background bake can reveal by count, plus
 * the {@link CorpseAppearanceLayout.RevealPreference} the reveal pass should bias toward.
 * <p>
 * Cache at most 128 layouts keyed {@code (corpseUuid, appearanceSeed, slim, deathCause,
 * anchorFingerprint)} — deliberately SEPARATE from {@link CorpseAppearanceLayout}'s own {@code
 * (appearanceSeed, slim)}-keyed cache, so nothing cause-specific can move the shipped global hole/
 * blotch/reveal ranks that class already owns (F1 step 4's own explicit requirement).
 * <p>
 * <b>Stage-independent by design.</b> Nothing here reads {@link DecayStage}/{@code stageProgress}/
 * environment — those vary continuously while this mask is a one-time-per-corpse, LRU-cached object.
 * Every array this class exposes is a full connected RANK order over its region (mirroring how {@code
 * CorpseAppearanceLayout#holeRank}/blotch ranks work): the caller ({@code CorpseFleshBaker.bake})
 * decides, from the CURRENT stage/environment, how many leading entries of that rank to actually
 * reveal/paint this bake.
 * <p>
 * <b>Thread contract:</b> exactly {@code CorpsePatchStitcher}'s — only {@code int[]}/collections of
 * primitives and the already-worker-safe {@link CorpseAnatomy}/{@link CorpseFleshMask}/{@link
 * DeathCauseInjuryProfile} types. No {@code Minecraft}/{@code TextureManager}/GL/live-entity access.
 */
final class CorpseInjuryMask {

    private static final int MAX_LAYOUTS = 128;

    private static final Map<Key, CorpseInjuryMask> CACHE = new LinkedHashMap<>(16, 0.75F, true);

    private final Profile profile;
    private final float variety;
    private final List<Band> bands;
    private final int[] fixedCutoutTexels;
    private final int[][] entryShadowTexels;
    @Nullable
    private final CorpseAppearanceLayout.RevealPreference revealPreference;

    private CorpseInjuryMask(InjuryRenderInputs inputs, boolean slim) {
        DeathCause cause = inputs.deathCause();
        this.profile = DeathCauseInjuryProfile.profileFor(cause);
        long causeSeed = DeathCauseInjuryProfile.causeSeed(inputs.appearanceSeed(), cause);
        this.variety = DeathCauseInjuryProfile.variety(causeSeed);

        ResolvedLocus locus = DeathCauseInjuryProfile.resolveLocus(cause, inputs.appearanceSeed(), inputs.killingAnchor());
        CorpseAnatomy anatomy = CorpseAnatomy.get(slim);
        CorpseFleshMask mask = CorpseFleshMask.get(slim);
        List<RegionBand> regionBands = DeathCauseInjuryProfile.resolveBands(cause, locus, anatomy, mask, inputs.killingAnchor());

        this.fixedCutoutTexels = resolveFixedCutout(cause, profile, anatomy, mask, regionBands, causeSeed, inputs.killingAnchor());

        List<Band> built = new ArrayList<>(regionBands.size());
        for (int i = 0; i < regionBands.size(); i++) {
            RegionBand regionBand = regionBands.get(i);
            int[] region = i == 0 ? subtract(regionBand.texels(), fixedCutoutTexels) : regionBand.texels();
            long bandSeed = DeathCauseInjuryProfile.morphDrawSeed(causeSeed) ^ ((i + 1L) * 0x9E3779B97F4A7C15L);
            int[] rank = growConnected(anatomy, mask, region, bandSeed, i == 0 && profile.directional());
            built.add(new Band(rank, regionBand.bias()));
        }
        this.bands = List.copyOf(built);

        this.entryShadowTexels = resolveEntryShadows(anatomy, inputs.nonKillingAnchors());
        this.revealPreference = DeathCauseInjuryProfile.revealPreferenceFor(cause, locus);
    }

    static synchronized CorpseInjuryMask get(InjuryRenderInputs inputs, boolean slim) {
        Key key = new Key(inputs.corpseUuid(), inputs.appearanceSeed(), slim, inputs.deathCause(), inputs.anchorFingerprint());
        CorpseInjuryMask existing = CACHE.get(key);
        if (existing == null) {
            existing = new CorpseInjuryMask(inputs, slim);
            CACHE.put(key, existing);
            while (CACHE.size() > MAX_LAYOUTS) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
        return existing;
    }

    static synchronized void clearCache() {
        CACHE.clear();
    }

    Profile profile() {
        return profile;
    }

    float variety() {
        return variety;
    }

    /** Index 0 is always the primary/dominant band — see {@link DeathCauseInjuryProfile#resolveBands}. */
    List<Band> bands() {
        return bands;
    }

    /** Permanent, unattenuated trauma cutout texels (EXPLODED's 4, VOID's 2, RANGED's 1) — empty
     *  unless the cause actually has one. VOID's is gated by {@link Profile#cutoutDelayedToEarlyDecay()}
     *  at the call site, not here (this mask is stage-independent by design). */
    int[] fixedCutoutTexels() {
        return fixedCutoutTexels;
    }

    /** Up to {@link InjuryRenderInputs#MAX_NON_KILLING_ANCHORS} single-texel discs — E1's "one-texel
     *  source-relative entry shadow" for carried-over non-killing arrows. */
    int[][] entryShadowTexels() {
        return entryShadowTexels;
    }

    @Nullable
    CorpseAppearanceLayout.RevealPreference revealPreference() {
        return revealPreference;
    }

    // ── Fixed cutout placement ──────────────────────────────────────────────────────────────────────

    private static int[] resolveFixedCutout(DeathCause cause, Profile profile, CorpseAnatomy anatomy,
                                            CorpseFleshMask mask, List<RegionBand> bands, long causeSeed,
                                            @Nullable InjuryRenderInputs.Anchor killingAnchor) {
        if (cause == DeathCause.RANGED) {
            // CRITICAL CORRECTNESS: fixed socket ONLY with a captured killing projectile — a fallback-
            // without-anchor RANGED corpse gets no cutout at all (E1 / A1's "1 only with killing
            // projectile"). Placed at the EXACT rendered anchor (radius 0), not a randomly ranked pick.
            if (killingAnchor == null) return new int[0];
            int socket = DeathCauseInjuryProfile.rangedKillingSocket(anatomy, killingAnchor);
            return socket >= 0 ? new int[]{socket} : new int[0];
        }
        int count = profile.fixedCutoutCount();
        if (count <= 0 || bands.isEmpty()) return new int[0];
        int[] region = bands.get(0).texels();
        if (region.length == 0) return new int[0];
        long cutoutSeed = DeathCauseInjuryProfile.cutoutDrawSeed(causeSeed);
        int[] rank = growConnected(anatomy, mask, region, cutoutSeed, false);
        return Arrays.copyOf(rank, Math.min(count, rank.length));
    }

    private static int[][] resolveEntryShadows(CorpseAnatomy anatomy, List<InjuryRenderInputs.Anchor> anchors) {
        int[][] result = new int[anchors.size()][];
        for (int i = 0; i < anchors.size(); i++) {
            InjuryRenderInputs.Anchor anchor = anchors.get(i);
            result[i] = anatomy.anchorDisc(Part.values()[anchor.part()], anchor.px(), anchor.py(), anchor.pz(), 0);
        }
        return result;
    }

    // ── Connected growth engine ─────────────────────────────────────────────────────────────────────

    /**
     * Ranks an ENTIRE region (not just a stage-sized prefix — see this class's javadoc) into one
     * deterministic, mostly-connected order: repeatedly extends from either the last-added texel only
     * ({@code directional}, approximating a line/walk-shaped template such as SLASH/EDGE_SHEAR/the
     * LIGHTNING branch) or from any already-visited texel's neighbours ({@code !directional}, a
     * blob), always picking the lowest {@link CorpseFleshMask#texelHash} candidate — the same
     * deterministic tie-break every other seeded rank in this package uses. A compound region that
     * spans more than one top-level {@link Part} (e.g. BURNED's STERNUM+arm, VOID's SIDE_CHAIN) is
     * genuinely disconnected in {@link CorpseAnatomy}'s own neighbour graph — {@link CorpseAnatomy
     * #wireCap} never wires a limb's cap directly to BODY's side faces — so growth naturally exhausts
     * the anchor's own connected piece first, then a deterministic texelHash-ordered remainder fill
     * (matching {@code CorpseAppearanceLayout.appendBand}'s own precedent) appends whatever the
     * neighbour graph could not reach, keeping every returned index unique and every prefix of the
     * array free of isolated single-pixel artefacts within its own connected piece.
     */
    private static int[] growConnected(CorpseAnatomy anatomy, CorpseFleshMask mask, int[] region,
                                       long seed, boolean directional) {
        if (region.length == 0) return new int[0];
        Set<Integer> inRegion = new HashSet<>(region.length * 2);
        for (int index : region) inRegion.add(index);

        int anchor = region[0];
        long bestAnchorHash = Long.MAX_VALUE;
        for (int index : region) {
            long hash = CorpseFleshMask.texelHash(seed, mask.texel(index));
            if (hash < bestAnchorHash) {
                bestAnchorHash = hash;
                anchor = index;
            }
        }

        LinkedHashSet<Integer> visited = new LinkedHashSet<>();
        visited.add(anchor);
        int last = anchor;
        boolean progressed = true;
        while (visited.size() < region.length && progressed) {
            progressed = false;
            int next = -1;
            long best = Long.MAX_VALUE;
            if (directional) {
                for (int candidate : anatomy.neighbors(last)) {
                    if (!inRegion.contains(candidate) || visited.contains(candidate)) continue;
                    long hash = CorpseFleshMask.texelHash(seed, mask.texel(candidate));
                    if (hash < best) {
                        best = hash;
                        next = candidate;
                    }
                }
            }
            if (next == -1) {
                for (int visitedIndex : visited) {
                    for (int candidate : anatomy.neighbors(visitedIndex)) {
                        if (!inRegion.contains(candidate) || visited.contains(candidate)) continue;
                        long hash = CorpseFleshMask.texelHash(seed, mask.texel(candidate));
                        if (hash < best) {
                            best = hash;
                            next = candidate;
                        }
                    }
                }
            }
            if (next != -1) {
                visited.add(next);
                last = next;
                progressed = true;
            }
        }

        if (visited.size() < region.length) {
            List<Integer> remainder = new ArrayList<>();
            for (int index : region) {
                if (!visited.contains(index)) remainder.add(index);
            }
            remainder.sort(Comparator.comparingLong(index -> CorpseFleshMask.texelHash(seed, mask.texel(index))));
            visited.addAll(remainder);
        }

        int[] result = new int[visited.size()];
        int cursor = 0;
        for (int index : visited) result[cursor++] = index;
        return result;
    }

    private static int[] subtract(int[] region, int[] remove) {
        if (remove.length == 0) return region;
        Set<Integer> excluded = new HashSet<>(remove.length * 2);
        for (int index : remove) excluded.add(index);
        List<Integer> kept = new ArrayList<>(region.length);
        for (int index : region) {
            if (!excluded.contains(index)) kept.add(index);
        }
        int[] result = new int[kept.size()];
        for (int i = 0; i < result.length; i++) result[i] = kept.get(i);
        return result;
    }

    /** One ranked target region with its own C1 local-hole/pigment bias — index 0 of the owning list
     *  is always this cause's primary/dominant band. */
    record Band(int[] rank, float bias) {}

    private record Key(UUID corpseUuid, long appearanceSeed, boolean slim, DeathCause deathCause,
                       long anchorFingerprint) {}
}
