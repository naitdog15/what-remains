package com.temog.notminelegacy.whatremains.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.temog.notminelegacy.whatremains.registry.WRItems;

import net.minecraft.core.NonNullList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.environment.CorpseEnvironment;
import com.temog.notminelegacy.whatremains.environment.CorpseEnvironmentLoader;
import com.temog.notminelegacy.whatremains.environment.CorpseEnvironmentSampler;
import com.temog.notminelegacy.whatremains.environment.CorpseEnvironmentSpec;
import com.temog.notminelegacy.whatremains.menu.CorpseMenu;
import com.temog.notminelegacy.whatremains.xp.XpVessel;



/**
 * The corpse entity spawned when a player dies.
 * <p>
 * Stores the player's full inventory in a chest-like interface.
 * Invulnerable to most damage by default; can only be "buried" by hitting
 * it with a shovel a configurable number of times.
 * <p>
 * Ported and reimagined from DrCyano's Lootable Bodies (1.7.10).
 */
public class CorpseEntity extends Mob implements Container {

    // ── Inventory ───────────────────────────────────────────────────────────

    /** Number of dedicated armor slots at the start of the inventory. */
    public static final int ARMOR_SLOTS = 4;
    /** Index of the offhand slot. */
    public static final int OFFHAND_INDEX = 4;
    /** First index of a general slot. */
    public static final int GENERAL_START = 5;
    /** Number of general (lootable, decayable) inventory slots. */
    public static final int GENERAL_SLOTS = 36;
    /** Total base container slots: 4 armor + 1 offhand + 36 general. */
    public static final int BASE_SLOTS = GENERAL_START + GENERAL_SLOTS; // 41

    /**
     * Items a feeding vulture may remove from the corpse's general inventory. Keeping the tag as
     * data rather than hard-coding an item list lets packs extend carrion food without widening the
     * mutation domain beyond slots {@value #GENERAL_START}..40.
     */
    private static final TagKey<Item> VULTURE_CARRION_FOOD = ItemTags.create(
            new ResourceLocation("buildingbridges", "vulture_carrion_food"));
    /**
     * Items a corpse-scavenging crow may steal from the corpse's general inventory. Mirrors Alex's
     * Mobs' own {@code EntityCrow.isCrowEdible} filter — {@code stack.getItem().isEdible() ||
     * stack.is(AMTagRegistry.CROW_FOODSTUFFS)} — without importing {@code AMTagRegistry} or
     * anything else from {@code alexsmobs}; this BB-namespaced tag optionally includes
     * {@code #alexsmobs:crow_foodstuffs} by reference instead (see
     * {@code data/buildingbridges/tags/items/crow_steal_food.json}), the exact same "mirror the
     * other side's data identity, never its class" pattern {@link #VULTURE_CARRION_FOOD} already
     * uses for the vulture path.
     */
    private static final TagKey<Item> CROW_STEAL_FOOD = ItemTags.create(
            new ResourceLocation("buildingbridges", "crow_steal_food"));
    /** Horizontal distance used for gore drops. Naturalist searches a one-block-expanded pickup
     *  box every tick, so spawning beside the beak would make the visual drop instantly disappear. */
    private static final double VULTURE_GORE_DROP_DISTANCE = 2.25D;
    /** Brief pickup lock reinforces the spatial offset while the dropped item settles. */
    private static final int VULTURE_GORE_PICKUP_DELAY_TICKS = 40;

    private final NonNullList<ItemStack> inventory = NonNullList.withSize(BASE_SLOTS, ItemStack.EMPTY);

    /** Dynamic curios items — populated on player death when Curios is loaded. */
    private NonNullList<ItemStack> curiosItems = NonNullList.withSize(0, ItemStack.EMPTY);
    /** Curios slot type identifiers, parallel to curiosItems. */
    private final java.util.List<String> curiosSlotTypes = new ArrayList<>();

    /** Overflow buffer for items that don't fit the main grid. */
    private final Deque<ItemStack> auxInventory = new ArrayDeque<>();

    // ── Synced data ─────────────────────────────────────────────────────────

    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final EntityDataAccessor<String> DATA_OWNER_NAME =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    private static final EntityDataAccessor<Byte> DATA_MODEL_PARTS =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);

    /** How the player died ({@link DeathCause} ordinal) — captured once at spawn by
     *  {@code PlayerDeathHandler}, synced to the client for future cause-specific rendering
     *  (e.g. burned → charred). Data-only for now; no rendering logic reads this yet. */
    private static final EntityDataAccessor<Byte> DATA_DEATH_CAUSE =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);

    /**
     * Live, ongoing environmental exposure trackers — intensity {@code [0.0, 1.0]} packed as an
     * unsigned byte (see {@link #packLevel}/{@link #unpackLevel}), sampled and updated by
     * {@link #updateEnvironmentalExposure()}. Unlike {@link #DATA_DEATH_CAUSE} (a one-time fact
     * captured at spawn), these five keep changing for as long as the corpse exists — they're the
     * *fresh* signal a future visual-style resolver will prefer, falling back to {@code DeathCause}
     * only when nothing environmental applies. {@code DATA_CHAR_LEVEL}/{@code DATA_SCORCH_LEVEL}/
     * {@code DATA_CALCIFY_LEVEL} are one-way (never decrease); {@code DATA_FROST_LEVEL}/
     * {@code DATA_WATERLOGGED_LEVEL} are fully reversible.
     */
    private static final EntityDataAccessor<Byte> DATA_CHAR_LEVEL =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_FROST_LEVEL =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_WATERLOGGED_LEVEL =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);
    /** {@code desert_desiccation} × {@code nether_ash_scorch} — one-way, shared by both cases (see
     *  {@link #getScorchStyle()} for which of the two currently applies). */
    private static final EntityDataAccessor<Byte> DATA_SCORCH_LEVEL =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);
    /** {@code cave_calcification} — one-way. */
    private static final EntityDataAccessor<Byte> DATA_CALCIFY_LEVEL =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.BYTE);

    /**
     * Resolved {@code style_key} for a tracker that more than one {@code CorpseEnvironmentSpec} can
     * feed at once — {@code scorch} (desert vs. nether) and {@code waterlogged} (plain water vs.
     * ocean). Updated by {@link #updateResolvedStyle} every {@link #updateEnvironmentalExposure()}
     * pass via {@link CorpseEnvironmentLoader#resolveStyleKey}; see that method's javadoc for the
     * LATCHING semantics (only overwritten while something currently matches, never cleared).
     * {@code char}/{@code frost}/{@code calcify} don't get one of these: each currently has exactly
     * one feeding spec, so their style is a fixed constant a future renderer needs no sync for.
     */
    private static final EntityDataAccessor<String> DATA_SCORCH_STYLE =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> DATA_WATERLOGGED_STYLE =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    /** Total ticks the corpse has existed — drives all decay logic. */
    private static final EntityDataAccessor<Integer> DATA_CORPSE_AGE =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);

    /**
     * Stored XP from the dead player — a per-chunk gambled fraction of their total XP (XPB-08,
     * {@code XpVessel#rollDeathRecovery}), harsher and less lucky the higher their level was.
     * Whatever didn't survive the roll was destroyed outright at death, never routed here.
     */
    private static final EntityDataAccessor<Integer> DATA_STORED_XP =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);

    /** Bit-mask: bits 0-31 of the locked general-slot set. */
    private static final EntityDataAccessor<Integer> DATA_LOCKED_SLOTS_LO =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);
    /** Bit-mask: bits 32-35 of the locked general-slot set. */
    private static final EntityDataAccessor<Integer> DATA_LOCKED_SLOTS_HI =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);

    /** Semicolon-separated curios slot type identifiers (synced to client for icon rendering). */
    private static final EntityDataAccessor<String> DATA_CURIOS_TYPES =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);
    /** Number of curios slots (synced to client for menu construction). */
    private static final EntityDataAccessor<Integer> DATA_CURIOS_COUNT =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.INT);

    /** Per-corpse seed driving client-side procedural appearance variety (flesh mask holes,
     *  blotches, etc. — see {@code client/CorpseFleshMask}). Rolled fresh at spawn by {@code
     *  PlayerDeathHandler}; when never explicitly set (defaults to {@code 0L}) — a hand-authored
     *  {@code /summon}, or a corpse saved before this field existed — {@link #getAppearanceSeed()}
     *  instead derives a stable fallback from the entity's own UUID, so appearance stays consistent
     *  across relog either way with zero extra persisted state. */
    private static final EntityDataAccessor<Long> DATA_APPEARANCE_SEED =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.LONG);

    /** Render-only snapshot of the main-hand stack at the moment of death — see {@link
     *  #getDeathHeldItem()}. Never redirects the real MAINHAND slot, never joins the container,
     *  and never affects the real stack's normal journey into corpse inventory. */
    private static final EntityDataAccessor<ItemStack> DATA_DEATH_HELD_ITEM =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.ITEM_STACK);

    /** Cap on how many {@link StuckProjectile} entries a corpse carries — see {@link
     *  #setStuckProjectiles} for the truncation rule when a capture exceeds it. */
    public static final int MAX_STUCK_PROJECTILES = 12;

    /** NBT/sync key for the {@code ListTag} nested inside {@link #DATA_STUCK_PROJECTILES}'s wrapper
     *  compound. */
    private static final String STUCK_PROJECTILES_LIST_KEY = "L";

    /** Encoded {@link StuckProjectile} list — see {@link #getStuckProjectiles()}. Deliberately NOT
     *  vanilla's {@code getArrowCount()}/{@code setArrowCount()}: {@code LivingEntity#tick()} counts
     *  that down via {@code removeArrowTime} and would silently drain a corpse's visible arrows over
     *  a few in-game minutes, but a corpse's stuck projectiles are meant to persist for its entire
     *  (much longer) life. Holds a single {@code ListTag} of {@link StuckProjectile#save()}
     *  compounds under key {@link #STUCK_PROJECTILES_LIST_KEY}, rather than a bare list accessor,
     *  because {@code EntityDataSerializers} has no built-in list-of-compound serializer. */
    private static final EntityDataAccessor<CompoundTag> DATA_STUCK_PROJECTILES =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.COMPOUND_TAG);

    // ── Internal state ──────────────────────────────────────────────────────

    private GameProfile ownerProfile;

    /** Cached decode of {@link #DATA_STUCK_PROJECTILES} — decoding a {@code ListTag} on every
     *  render frame would be wasteful. {@code null} means "needs rebuilding"; invalidated in
     *  {@link #onSyncedDataUpdated} and lazily rebuilt by {@link #getStuckProjectiles()} on next
     *  read — the same null-and-lazily-rebuild shape as {@link #ownerProfile}'s own invalidation,
     *  except unguarded by side (client or server) since, unlike the owner profile, there is no
     *  separate richer server-side object to preserve here: {@link #entityData} already IS the
     *  authoritative source either side. Starts at an empty list rather than {@code null} so a
     *  freshly constructed corpse with no sync/NBT applied yet reads as empty instead of forcing an
     *  immediate (pointless) decode of the still-default entityData value. */
    private java.util.List<StuckProjectile> cachedStuckProjectiles = java.util.Collections.emptyList();

    private int shovelHits;
    private int vacuumTimer;
    private long deathTimestamp = Long.MAX_VALUE;
    private boolean deadMode;
    private boolean healthInitialized;

    /**
     * The player's XP level AT THE MOMENT OF DEATH — server-only, not synced (nothing client-side
     * needs it). Kept purely so {@link #dropStoredXpAsOrbs()} can position the corpse-destruction
     * payout roll on the same level curve the original death roll used, even though by the time the
     * corpse is destroyed the player may be a completely different level (or offline, or the corpse
     * may be destroyed by someone else entirely).
     */
    private int deathLevel;

    /**
     * Transient client-side flag: when true the renderer skips the face-down
     * rotation so the corpse can be drawn upright in the GUI preview.
     */
    public boolean renderUpright = false;

    /** Dirty flag — set when inventory changes; cleared after sync. */
    private boolean inventoryDirty;
    /** Tracks players currently viewing this container. */
    private final java.util.Set<Player> viewers = new java.util.HashSet<>();

    /**
     * Tracks how many general slots were active on last decay check.
     * Used to detect when a stage transition triggers new random locks.
     */
    private int previousActiveGeneralCount = GENERAL_SLOTS;

    // ── Constructor ─────────────────────────────────────────────────────────

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        // Keep engine-level invulnerability off so custom shovel/damage logic can run.
        this.setInvulnerable(false);
        this.setPersistenceRequired();
        this.setNoGravity(false);       // corpses still fall
    }

    public static AttributeSupplier.Builder createCorpseAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 50.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0);
    }

    // ── Data registration ───────────────────────────────────────────────────

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_OWNER_UUID, Optional.empty());
        this.entityData.define(DATA_OWNER_NAME, "");
        this.entityData.define(DATA_MODEL_PARTS, (byte) 0x7E); // all overlays except cape by default
        this.entityData.define(DATA_DEATH_CAUSE, (byte) DeathCause.NATURAL.ordinal());
        this.entityData.define(DATA_CHAR_LEVEL, (byte) 0);
        this.entityData.define(DATA_FROST_LEVEL, (byte) 0);
        this.entityData.define(DATA_WATERLOGGED_LEVEL, (byte) 0);
        this.entityData.define(DATA_SCORCH_LEVEL, (byte) 0);
        this.entityData.define(DATA_CALCIFY_LEVEL, (byte) 0);
        this.entityData.define(DATA_SCORCH_STYLE, "");
        this.entityData.define(DATA_WATERLOGGED_STYLE, "");
        this.entityData.define(DATA_CORPSE_AGE, 0);
        this.entityData.define(DATA_STORED_XP, 0);
        this.entityData.define(DATA_LOCKED_SLOTS_LO, 0);
        this.entityData.define(DATA_LOCKED_SLOTS_HI, 0);
        this.entityData.define(DATA_CURIOS_TYPES, "");
        this.entityData.define(DATA_CURIOS_COUNT, 0);
        this.entityData.define(DATA_APPEARANCE_SEED, 0L);
        this.entityData.define(DATA_DEATH_HELD_ITEM, ItemStack.EMPTY);
        this.entityData.define(DATA_STUCK_PROJECTILES, new CompoundTag());
    }

    // ── Owner management ────────────────────────────────────────────────────

    public void setOwnerProfile(GameProfile profile) {
        this.ownerProfile = profile;
        if (profile != null) {
            this.entityData.set(DATA_OWNER_UUID,
                    profile.getId() != null ? Optional.of(profile.getId()) : Optional.empty());
            this.entityData.set(DATA_OWNER_NAME,
                    profile.getName() != null ? profile.getName() : "");
            resolveOwnerProfileByName(profile);
        } else {
            this.entityData.set(DATA_OWNER_UUID, Optional.empty());
            this.entityData.set(DATA_OWNER_NAME, "");
        }
    }

    /**
     * When {@code profile} names a player by NAME only — exactly what a hand-authored
     * {@code /summon}/{@code /data merge} NBT tag looks like (e.g.
     * {@code {OwnerProfile:{Name:"PlayerName"}}}) — asynchronously resolves the real UUID + skin
     * the same way vanilla resolves a player-head's {@code SkullOwner} tag ({@link
     * SkullBlockEntity#updateGameprofile}), then re-applies the completed profile on the server
     * thread once ready so {@link com.temog.notminelegacy.whatremains.client.CorpseFleshBaker} has
     * a UUID to fetch a real skin with. No-op for an already-complete profile (the normal
     * death-spawn path via {@code PlayerDeathHandler} always hands over a live player's own
     * complete {@code GameProfile}), so this never re-triggers once resolved.
     */
    private void resolveOwnerProfileByName(GameProfile profile) {
        if (profile.getId() != null) return;
        String name = profile.getName();
        if (name == null || name.isEmpty()) return;
        if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        SkullBlockEntity.updateGameprofile(profile, resolved -> {
            if (resolved == null || resolved.getId() == null || isRemoved()) return;
            serverLevel.getServer().execute(() -> {
                if (!isRemoved()) setOwnerProfile(resolved);
            });
        });
    }

    public GameProfile getOwnerProfile() {
        if (ownerProfile == null) {
            String name = this.entityData.get(DATA_OWNER_NAME);
            Optional<UUID> uuid = this.entityData.get(DATA_OWNER_UUID);
            if (!name.isEmpty() || uuid.isPresent()) {
                ownerProfile = new GameProfile(uuid.orElse(null), name.isEmpty() ? null : name);
            }
        }
        return ownerProfile;
    }

    public void setDeathTimestamp(long timestamp) {
        this.deathTimestamp = timestamp;
    }

    // ── Skin customisation ───────────────────────────────────────────────────

    public void setModelCustomisation(byte mask) {
        this.entityData.set(DATA_MODEL_PARTS, mask);
    }

    public byte getModelCustomisation() {
        return this.entityData.get(DATA_MODEL_PARTS);
    }

    // ── Appearance seed ─────────────────────────────────────────────────────

    /** Explicitly set this corpse's appearance seed — so testers can force a specific look via
     *  {@code /summon}. Rolled fresh (from {@code level.random}) at spawn by
     *  {@code PlayerDeathHandler}; never touched again after that for a normal death. */
    public void setAppearanceSeed(long seed) {
        this.entityData.set(DATA_APPEARANCE_SEED, seed);
    }

    /** Per-corpse seed for procedural appearance variety. When never explicitly set (the synced
     *  value is still its {@code 0L} default — true for a hand-authored {@code /summon} with no
     *  {@code AppearanceSeed} tag, or a corpse saved before this field existed) falls back to a
     *  deterministic UUID-derived default instead, so appearance stays stable across relog with no
     *  extra persisted state either way. */
    public long getAppearanceSeed() {
        long seed = this.entityData.get(DATA_APPEARANCE_SEED);
        return seed != 0L ? seed : defaultAppearanceSeed();
    }

    /** UUID-derived fallback for {@link #getAppearanceSeed()} — mixes the UUID's two halves
     *  together, then runs the result through {@link StuckProjectile.SeedRandom#mix}, the same
     *  SplitMix64 finalizer {@code client/CorpseFleshMask}'s own {@code mix()} uses. */
    private long defaultAppearanceSeed() {
        UUID id = getUUID();
        long derived = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 23);
        return StuckProjectile.SeedRandom.mix(derived);
    }

    // ── Death-held item (render-only snapshot) ────────────────────────────────

    /** Render-only snapshot of the main-hand stack at the moment of death — see
     *  {@code PlayerDeathHandler}'s capture site for exactly when/how this is taken. Purely
     *  cosmetic: it never redirects {@link #getItemBySlot(EquipmentSlot)}, never joins the
     *  container, and has no bearing on the real stack's normal path into corpse inventory (which
     *  happens independently via the main-inventory vacuum loop). Cleared back to
     *  {@link ItemStack#EMPTY} once the real stack has actually been looted — see
     *  {@link #updateDeathHeldItemConsistency()}. */
    public ItemStack getDeathHeldItem() {
        return this.entityData.get(DATA_DEATH_HELD_ITEM);
    }

    public void setDeathHeldItem(ItemStack stack) {
        this.entityData.set(DATA_DEATH_HELD_ITEM, stack != null ? stack : ItemStack.EMPTY);
    }

    /**
     * Clears {@link #DATA_DEATH_HELD_ITEM} once the real stack it snapshots has actually been
     * looted from the corpse — a corpse shouldn't keep visually clutching a sword the player has
     * already taken. Cheap O(slots) scan over {@link #inventory} (indices 0-40) and
     * {@link #curiosItems}, run only once every 20 ticks from {@link #tick()}'s existing
     * decay/environment block rather than adding a new cadence of its own.
     */
    private void updateDeathHeldItemConsistency() {
        ItemStack held = getDeathHeldItem();
        if (held.isEmpty()) return;
        for (int i = 0; i < BASE_SLOTS; i++) {
            if (ItemStack.isSameItem(inventory.get(i), held)) return;
        }
        for (ItemStack stack : curiosItems) {
            if (ItemStack.isSameItem(stack, held)) return;
        }
        setDeathHeldItem(ItemStack.EMPTY);
    }

    // ── Stuck projectiles ───────────────────────────────────────────────────

    /** Never null; unmodifiable. Rebuilt lazily from {@link #DATA_STUCK_PROJECTILES} whenever
     *  {@link #cachedStuckProjectiles} was invalidated by {@link #onSyncedDataUpdated}. */
    public java.util.List<StuckProjectile> getStuckProjectiles() {
        if (cachedStuckProjectiles == null) {
            cachedStuckProjectiles = decodeStuckProjectiles(this.entityData.get(DATA_STUCK_PROJECTILES));
        }
        return cachedStuckProjectiles;
    }

    /** Caps at {@link #MAX_STUCK_PROJECTILES}, preferentially keeping the (at most one)
     *  killing-blow entry if truncation is needed — a heavily-pincushioned player shouldn't be able
     *  to bump their own fatal projectile out of the visible set. */
    public void setStuckProjectiles(java.util.List<StuckProjectile> projectiles) {
        java.util.List<StuckProjectile> capped = capStuckProjectiles(projectiles);
        CompoundTag encoded = new CompoundTag();
        if (!capped.isEmpty()) {
            ListTag listTag = new ListTag();
            for (StuckProjectile projectile : capped) {
                listTag.add(projectile.save());
            }
            encoded.put(STUCK_PROJECTILES_LIST_KEY, listTag);
        }
        this.entityData.set(DATA_STUCK_PROJECTILES, encoded);
        cachedStuckProjectiles = capped;
    }

    private static java.util.List<StuckProjectile> capStuckProjectiles(java.util.List<StuckProjectile> projectiles) {
        if (projectiles == null || projectiles.isEmpty()) return java.util.List.of();
        if (projectiles.size() <= MAX_STUCK_PROJECTILES) return java.util.List.copyOf(projectiles);

        StuckProjectile killingBlow = null;
        java.util.List<StuckProjectile> rest = new ArrayList<>(projectiles.size());
        for (StuckProjectile projectile : projectiles) {
            if (killingBlow == null && projectile.killingBlow()) {
                killingBlow = projectile;
            } else {
                rest.add(projectile);
            }
        }
        int restBudget = MAX_STUCK_PROJECTILES - (killingBlow != null ? 1 : 0);
        java.util.List<StuckProjectile> result = new ArrayList<>(MAX_STUCK_PROJECTILES);
        if (killingBlow != null) result.add(killingBlow);
        for (int i = 0; i < rest.size() && i < restBudget; i++) {
            result.add(rest.get(i));
        }
        return java.util.List.copyOf(result);
    }

    private static java.util.List<StuckProjectile> decodeStuckProjectiles(CompoundTag encoded) {
        if (!encoded.contains(STUCK_PROJECTILES_LIST_KEY, Tag.TAG_LIST)) {
            return java.util.List.of();
        }
        ListTag listTag = encoded.getList(STUCK_PROJECTILES_LIST_KEY, Tag.TAG_COMPOUND);
        java.util.List<StuckProjectile> result = new ArrayList<>(listTag.size());
        for (int i = 0; i < listTag.size(); i++) {
            result.add(StuckProjectile.load(listTag.getCompound(i)));
        }
        return java.util.List.copyOf(result);
    }

    // ── Death cause ───────────────────────────────────────────────────────────

    /** How the player died — see {@link DeathCause}. Set once, at spawn ({@code PlayerDeathHandler}),
     *  never touched again. */
    public void setDeathCause(DeathCause cause) {
        this.entityData.set(DATA_DEATH_CAUSE, (byte) (cause != null ? cause.ordinal() : DeathCause.NATURAL.ordinal()));
    }

    /** How the player died — see {@link DeathCause}. Defaults to {@link DeathCause#NATURAL} for
     *  corpses saved before this field existed. */
    public DeathCause getDeathCause() {
        return DeathCause.byOrdinal(this.entityData.get(DATA_DEATH_CAUSE));
    }

    // ── Environmental exposure (char/frost/waterlogged) ──────────────────────

    /** Packs an intensity {@code [0.0, 1.0]} into an unsigned byte (0-255). */
    private static byte packLevel(float value) {
        float clamped = Math.max(0.0F, Math.min(1.0F, value));
        return (byte) Math.round(clamped * 255.0F);
    }

    /** Unpacks a byte written by {@link #packLevel} back into {@code [0.0, 1.0]}. */
    private static float unpackLevel(byte raw) {
        return (raw & 0xFF) / 255.0F;
    }

    /** Permanent char (burn) exposure intensity {@code [0.0, 1.0]} — one-way, never decreases
     *  (see {@link #updateEnvironmentalExposure()}). Public for a future rendering task to read;
     *  data-only for now, exactly like {@link #getDeathCause()}. */
    public float getCharLevel() {
        return unpackLevel(this.entityData.get(DATA_CHAR_LEVEL));
    }

    private void setCharLevel(float value) {
        this.entityData.set(DATA_CHAR_LEVEL, packLevel(value));
    }

    /** Reversible frost exposure intensity {@code [0.0, 1.0]} — melts back down once conditions
     *  change. Public for a future rendering task to read. */
    public float getFrostLevel() {
        return unpackLevel(this.entityData.get(DATA_FROST_LEVEL));
    }

    private void setFrostLevel(float value) {
        this.entityData.set(DATA_FROST_LEVEL, packLevel(value));
    }

    /** Reversible waterlogged exposure intensity {@code [0.0, 1.0]} — dries back out once
     *  conditions change. Public for a future rendering task to read. */
    public float getWaterloggedLevel() {
        return unpackLevel(this.entityData.get(DATA_WATERLOGGED_LEVEL));
    }

    private void setWaterloggedLevel(float value) {
        this.entityData.set(DATA_WATERLOGGED_LEVEL, packLevel(value));
    }

    /** Permanent scorch (sun/ash) exposure intensity {@code [0.0, 1.0]} — one-way, never decreases,
     *  same enforcement shape as {@link #getCharLevel()}. Shared by {@code desert_desiccation} and
     *  {@code nether_ash_scorch}; see {@link #getScorchStyle()} for which one currently applies. */
    public float getScorchLevel() {
        return unpackLevel(this.entityData.get(DATA_SCORCH_LEVEL));
    }

    private void setScorchLevel(float value) {
        this.entityData.set(DATA_SCORCH_LEVEL, packLevel(value));
    }

    /** Permanent calcify (cave mineral-crust) exposure intensity {@code [0.0, 1.0]} — one-way, never
     *  decreases, same enforcement shape as {@link #getCharLevel()}. */
    public float getCalcifyLevel() {
        return unpackLevel(this.entityData.get(DATA_CALCIFY_LEVEL));
    }

    private void setCalcifyLevel(float value) {
        this.entityData.set(DATA_CALCIFY_LEVEL, packLevel(value));
    }

    /** Which {@code scorch}-feeding spec's style currently applies — {@code ""} until first resolved
     *  (never reachable while {@link #getScorchLevel()} is nonzero, since resolving is what raises it
     *  off 0.0), otherwise one of the loaded {@code scorch}-tracker specs' {@code style_key}s (e.g.
     *  {@code "desert_sun"}/{@code "nether_ash"}). See {@link CorpseEnvironmentLoader#resolveStyleKey}
     *  for the LATCHING resolution rule. Data-only for now — a future rendering task reads it, same
     *  as {@link #getDeathCause()}. */
    public String getScorchStyle() {
        return this.entityData.get(DATA_SCORCH_STYLE);
    }

    /** Which {@code waterlogged}-feeding spec's style currently applies (e.g. plain
     *  {@code "waterlogged"} vs. {@code "ocean_bloat"}) — same LATCHING rule as
     *  {@link #getScorchStyle()}. Data-only for now. */
    public String getWaterloggedStyle() {
        return this.entityData.get(DATA_WATERLOGGED_STYLE);
    }

    // ── Corpse age (ticks alive) ─────────────────────────────────────────────

    /** Total ticks this corpse has existed in the world. */
    public int getCorpseAgeTicks() {
        return this.entityData.get(DATA_CORPSE_AGE);
    }

    public void setCorpseAgeTicks(int ticks) {
        this.entityData.set(DATA_CORPSE_AGE, ticks);
    }

    /**
     * Flies currently landed on this corpse (BuildingBridges' corpse-fly bridge, if installed) —
     * event-driven via {@link #onFlyLanded()}/{@link #onFlyUnlanded()}, NOT a per-tick scan. Purely
     * server-side bookkeeping, never synced — only {@link #tick()}'s age accumulator reads it.
     */
    private int landedFlyCount;
    /** Fractional tick carry-over for the fly-accelerated decay rate — see {@link #tick()}. */
    private float corpseAgeAccumulator;

    // ── Vulture feeding leases (transient; never persisted) ───────────────

    /**
     * UUID leases shared by Naturalist and Soul Vultures. A set, rather than a bare counter, makes
     * release idempotent and lets {@link #pruneFeedingVultureLeases()} repair every lifecycle path
     * on which an AI goal's {@code stop()} callback is not guaranteed (death, removal and chunk
     * unload in particular). This collection is intentionally absent from NBT.
     */
    private final Set<UUID> feedingVultures = new LinkedHashSet<>();
    /**
     * Stable perch-spacing index for each live lease. Deriving an index from set iteration would
     * renumber later vultures whenever an earlier one left, making already-landed birds slide over
     * the corpse; this transient side map preserves an index for the whole lease instead.
     */
    private final Map<UUID, Integer> feedingVultureSlotIndices = new HashMap<>();
    /** Number of tagged food items actually removed by vultures; persisted to enforce the cap. */
    private int vultureConsumedFoodItems;
    /** Number of rotten-flesh gore entities actually spawned; persisted to prevent reload farming. */
    private int vultureGoreDrops;

    /**
     * "Attract-a-crowd" — round 17. Ticks of CONTINUOUS landed-fly presence accumulated toward
     * {@link #hasAttractedFlyCrowd}, advanced once per second by {@link #updateFlyCrowdProgress()}
     * from {@link #tick()}'s existing 20-tick block. Resets to 0 the moment {@link #landedFlyCount}
     * drops back to 0 before the threshold is reached — the seed fly has to actually stay engaged,
     * not just briefly touch down. Purely server-side, not synced (only
     * {@code WhatRemainsFlyCorpseEvents}' spawn roll reads {@link #hasAttractedFlyCrowd()}).
     */
    private int landedSuckTicks;
    /** How long (ticks) a fly must stay continuously landed before it "calls in" reinforcements —
     *  see {@link #updateFlyCrowdProgress()}. 600 ticks = 30 real seconds. */
    private static final int FLY_CROWD_SUCK_TICKS = 600;
    /** One-way latch — once a corpse has called in a crowd, it stays "known" to flies even if every
     *  one of them is later scared off, mirroring "word got out" rather than ongoing physical
     *  presence. Persisted (see NBT read/write) so a chunk unload/reload doesn't quietly forget it. */
    private boolean hasAttractedFlyCrowd;

    /**
     * Server-only, unsynced (nothing client-side needs it) cached decay-rate multiplier derived
     * from biome temperature + sky visibility. Recomputed once per second by
     * {@link #updateEnvironmentalExposure()}; applied to every tick's age-accumulator gain in
     * {@link #tick()} regardless, so it's never more than ~20 ticks stale. Defaults to 1.0
     * (neutral) until the first environmental sample runs.
     */
    private float cachedDecayRateMultiplier = 1.0F;

    /** Called by BuildingBridges when a fly settles onto this corpse. */
    public void onFlyLanded() {
        landedFlyCount++;
    }

    /** Called by BuildingBridges when a landed fly leaves (scared off, or the corpse became
     *  invalid/moved out of range). Safe to call even if nothing was ever landed. */
    public void onFlyUnlanded() {
        if (landedFlyCount > 0) {
            landedFlyCount--;
        }
    }

    /** Flies currently landed on this corpse — public counterpart of {@link #landedFlyCount} for
     *  {@code WhatRemainsFlyCorpseEvents}' "attract-a-crowd" seed/join spawn logic. */
    public int getLandedFlyCount() {
        return landedFlyCount;
    }

    /**
     * Atomically claims one of this corpse's shared feeding slots for either vulture species.
     * Re-reserving an already-held UUID succeeds without consuming a second slot, which is required
     * for the landing-time re-check in the state goals. Zero configured slots means feeding is
     * disabled. Lease mutation is server-only and the leases are deliberately never persisted.
     *
     * <p><b>Master-switch gate (defect fix):</b> {@link WhatRemainsConfig#enableVultureCorpseFeeding}'s
     * own config comment promises that turning it off prevents feeding-slot reservation entirely —
     * checked first, before any capacity/index work, so a bird can never hold a lease while the
     * switch is off.
     */
    public boolean reserveFeedingSlot(UUID feeder) {
        if (level().isClientSide || feeder == null) return false;
        if (!WhatRemainsConfig.enableVultureCorpseFeeding) return false;

        if (feedingVultures.contains(feeder)) return true;

        int capacity = WhatRemainsConfig.vultureMaxFeedersPerCorpse;
        if (capacity <= 0 || feedingVultures.size() >= capacity) return false;

        int slotIndex = 0;
        while (feedingVultureSlotIndices.containsValue(slotIndex)) {
            slotIndex++;
        }
        // A runtime config reduction may leave existing leases at higher stable indices. There is
        // still always a free index below capacity when size < capacity, but retain this guard so a
        // corrupted side map can never create an over-cap or overlapping reservation.
        if (slotIndex >= capacity) return false;

        if (!feedingVultures.add(feeder)) return true;
        feedingVultureSlotIndices.put(feeder, slotIndex);
        return true;
    }

    /**
     * Releases a vulture's feeding lease. Idempotence is intentional: goal shutdown and the corpse
     * pruning backstop may both observe the same departure, and neither should affect another bird.
     */
    public void releaseFeedingSlot(UUID feeder) {
        if (level().isClientSide || feeder == null) return;
        feedingVultures.remove(feeder);
        feedingVultureSlotIndices.remove(feeder);
    }

    /** True when {@code feeder} currently owns a transient feeding lease on this corpse. */
    public boolean isFeedingSlotHeld(UUID feeder) {
        return feeder != null && feedingVultures.contains(feeder);
    }

    /** Number of Naturalist and Soul Vultures currently sharing this corpse's feeding slots. */
    public int getFeedingVultureCount() {
        return feedingVultures.size();
    }

    /**
     * Stable, distinct perch-spacing index for an active feeder, or {@code -1} when the UUID does
     * not hold a lease. The index remains fixed until release even when another feeder leaves.
     */
    public int getFeedingSlotIndex(UUID feeder) {
        if (feeder == null || !feedingVultures.contains(feeder)) return -1;
        return feedingVultureSlotIndices.getOrDefault(feeder, -1);
    }

    /**
     * Prunes leaked reservations from the corpse's own tick, because entity AI goal shutdown is not
     * a lifecycle guarantee. {@link ServerLevel#getEntity(UUID)} returning {@code null} covers both
     * unloaded and absent entities; alive/removal and distance checks cover every remaining stale
     * lease. The acquisition range doubles as the lease range so a bird cannot keep accelerating a
     * corpse it is no longer close enough to target.
     */
    private void pruneFeedingVultureLeases() {
        if (level().isClientSide || feedingVultures.isEmpty()) return;
        if (!(level() instanceof ServerLevel serverLevel)) {
            feedingVultures.clear();
            feedingVultureSlotIndices.clear();
            return;
        }

        double leashRange = Math.max(0.0D, WhatRemainsConfig.vultureFeederLeashRange);
        double leashRangeSqr = leashRange * leashRange;
        feedingVultures.removeIf(feederUuid -> {
            Entity feeder = serverLevel.getEntity(feederUuid);
            boolean stale = feeder == null
                    || feeder.isRemoved()
                    || !feeder.isAlive()
                    || feeder.distanceToSqr(this) > leashRangeSqr;
            if (stale) {
                feedingVultureSlotIndices.remove(feederUuid);
            }
            return stale;
        });
    }

    /**
     * True while a valid server-side player actually has this corpse's menu open. The viewer set is
     * bound to this specific container by {@link #startOpen(Player)}; the additional menu/reach
     * checks reject stale entries immediately instead of waiting for the five-tick dirty sync path.
     */
    public boolean hasOpenCorpseMenu() {
        if (level().isClientSide) return false;
        return viewers.stream().anyMatch(viewer -> viewer != null
                && !viewer.isRemoved()
                && viewer.containerMenu instanceof CorpseMenu
                && viewer.containerMenu != viewer.inventoryMenu
                && stillValid(viewer));
    }

    /**
     * True while this corpse remains below its persistent vulture-food cap. A configured cap of
     * zero disables consumption, matching the config's documented zero semantics.
     */
    public boolean canConsumeMoreFood() {
        return vultureConsumedFoodItems < Math.max(0, WhatRemainsConfig.vultureMaxFoodItemsPerCorpse);
    }

    /**
     * The corpse's own last-line decay-stage guard for vulture feeding: {@code EARLY_DECAY}
     * through {@code ADVANCED_DECAY}, inclusive. {@code DecayStage} is whatremains' own class, so
     * checking it here is legal without importing anything from BuildingBridges (see the
     * module-dependency switchboard rule in CLAUDE.md — CorpseEntity must never hard-depend on
     * BuildingBridges). This intentionally duplicates only the decay-stage half of
     * BuildingBridges' {@code CorpseVultureAccessibility#isFeedable(CorpseEntity)}, which the
     * goal classes already call before ever reaching these methods and which additionally checks
     * submersion/sealing — those environmental checks have no whatremains-side equivalent and are
     * deliberately not re-implemented here.
     */
    private boolean isVultureFeedableStage() {
        DecayStage stage = getDecayStage();
        // TESTING ONLY — see WhatRemainsConfig.debugVulturesIgnoreDecayStage. Kept in step with the
        // goal-side CorpseVultureAccessibility.isFeedable check so the flag cannot let a vulture
        // land and then find the corpse silently refusing to be fed on.
        if (stage == DecayStage.FRESH && WhatRemainsConfig.debugVulturesIgnoreDecayStage) {
            return true;
        }
        return stage == DecayStage.EARLY_DECAY
                || stage == DecayStage.MID_DECAY
                || stage == DecayStage.LATE_DECAY
                || stage == DecayStage.ADVANCED_DECAY;
    }

    /**
     * Runs one completed feeding cycle's exact carrion-consumption contract and returns the actual
     * {@link ItemStack} that was removed (a single-count stack of whatever tagged food item was
     * rolled), or {@link ItemStack#EMPTY} when the chance roll failed, any safety predicate
     * rejected the attempt, or no tagged candidate existed. Mutation is server only. The chance
     * roll happens first; every safety predicate is then re-checked immediately before building a
     * fresh candidate list from active GENERAL slots 5..40 only. Armor, offhand, locked general
     * slots and every Curios slot are therefore excluded by construction, rather than relying on
     * {@link #isSlotActive(int)} (which intentionally considers those other domains active). The
     * persistent cap advances only when {@link #removeItem(int, int)} really returns an item, never
     * for failed rolls, empty candidate lists or failed removals.
     *
     * <p>Both {@link #consumeCarrionFood()} and {@link #consumeCarrionFoodItem()} are thin wrappers
     * over this single method so the roll/gate/removal contract can never drift between the two —
     * see their own javadoc for which callers use which.
     */
    private ItemStack consumeCarrionFoodInternal() {
        if (level().isClientSide) return ItemStack.EMPTY;

        if (random.nextDouble() >= WhatRemainsConfig.vultureFoodConsumeChance) return ItemStack.EMPTY;

        if (deadMode
                || isRemoved()
                || !isAlive()
                || !WhatRemainsConfig.enableVultureCorpseFeeding
                || !level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || !isVultureFeedableStage()
                || !canConsumeMoreFood()
                || hasOpenCorpseMenu()) {
            return ItemStack.EMPTY;
        }

        List<Integer> candidates = new ArrayList<>();
        for (int index = GENERAL_START; index < BASE_SLOTS; index++) {
            ItemStack stack = getItem(index);
            if (isSlotActive(index) && !stack.isEmpty() && stack.is(VULTURE_CARRION_FOOD)) {
                candidates.add(index);
            }
        }
        if (candidates.isEmpty()) return ItemStack.EMPTY;

        int index = candidates.get(random.nextInt(candidates.size()));
        ItemStack removed = removeItem(index, 1);
        if (removed.isEmpty()) return ItemStack.EMPTY;

        vultureConsumedFoodItems++;
        return removed;
    }

    /**
     * Boolean-collapsing wrapper over {@link #consumeCarrionFoodInternal()} for callers that only
     * need to know whether something was eaten, not what — used by {@code AMSoulVultureCorpseGoal}
     * (Soul Vulture has no beak-food visual, see {@code VulturePhase}'s own javadoc on that
     * deliberate scope decision, so it never needs the actual stack).
     *
     * @return true only when exactly one tagged food item was actually removed
     */
    public boolean consumeCarrionFood() {
        return !consumeCarrionFoodInternal().isEmpty();
    }

    /**
     * Beak-feeding variant of {@link #consumeCarrionFood()} used by
     * {@code NaturalistVultureCorpseGoal} to give a visible torn strip of meat before it is
     * animated away (plan (a)) — identical gate/roll/removal to {@link #consumeCarrionFood()}; the
     * only difference is returning the real removed {@link ItemStack} (type and count) instead of
     * collapsing it to a boolean, since the caller places it into the bird's own mainhand for
     * rendering. This mints nothing new: it is the exact same container removal
     * {@link #consumeCarrionFood()} already performs, already capped by
     * {@link WhatRemainsConfig#vultureMaxFoodItemsPerCorpse} (a persistent, per-corpse lifetime
     * counter) and gated on the {@code mobGriefing} gamerule — see that caller's own javadoc for
     * why holding the returned stack in a bird's beak (rather than discarding it immediately, as
     * the boolean-only path effectively does) can still never become a duplication farm.
     */
    public ItemStack consumeCarrionFoodItem() {
        return consumeCarrionFoodInternal();
    }

    /**
     * Runs the corpse-side half of a single crow-theft attempt: removes and returns exactly one
     * candidate item, or {@link ItemStack#EMPTY} when any guard rejects the attempt or no candidate
     * exists. A SIBLING of {@link #consumeCarrionFoodItem()} — not a modification of it and not a
     * shared helper, because the two contracts genuinely diverge (no roll, no cap, no enable-flag
     * here — see below) and forcing them through one method would just grow a pile of booleans no
     * reader could follow.
     *
     * <p><b>Deliberately no chance roll and no persistent per-corpse counter</b> (user decision
     * D8): this mirrors Alex's Mobs' own crop-theft goal, {@code CrowAICircleCrops}, which has
     * neither — every successful attempt removes an item, and the only rate limiter is the crow's
     * own post-attempt restart throttle ({@code CrowConfig.stealCooldownTicks}, entirely on the
     * caller's side in {@code AMCrowCorpseGoal}). Do NOT add a
     * {@code vultureConsumedFoodItems}-style lifetime counter here — that would invent a cap AM's
     * own logic never had.
     *
     * <p><b>Deliberately no enable-flag of its own</b>, unlike
     * {@link #consumeCarrionFoodInternal()}'s {@code WhatRemainsConfig.enableVultureCorpseFeeding}
     * check (plan addendum R2-6, overruling the base plan's originally specified second gate).
     * Two reasons: (a) crow theft has exactly one effect, reachable only from the crow's own goal,
     * so a second gate here would only be a way to silently half-disable a feature whose real
     * switch already lives one hop away — BuildingBridges' {@code CrowConfig.enableCorpseTheft},
     * checked by {@code AMCrowCorpseGoal} before this method is ever called; and (b) this class
     * lives in {@code whatremains} and must never read {@code buildingbridges} config (module-
     * dependency switchboard rule, CLAUDE.md) — so even if a corpse-side gate were wanted, it could
     * not consult {@code CrowConfig} to decide anything with it. It would be redundant by
     * construction.
     *
     * <p>Guards, in order: server-only mutation, corpse liveness ({@code deadMode}/removed/not
     * alive), the {@code mobGriefing} gamerule (AM's own {@code CrowAICircleCrops} requires it
     * too), then {@link #hasOpenCorpseMenu()} — not an AM rule, but the same whatremains invariant
     * {@link #consumeCarrionFoodInternal()} enforces: mutating a container a player currently has
     * open is a desync/dupe hazard.
     *
     * <p>Candidate scan is restricted to active GENERAL slots {@value #GENERAL_START}..40 only,
     * built fresh each call exactly like {@link #consumeCarrionFoodInternal()} — armor, offhand,
     * locked general slots and every Curios slot are excluded by construction, rather than relying
     * on {@link #isSlotActive(int)} alone (which intentionally treats those other domains as
     * active).
     *
     * <p>Filter (user decision D6): {@code stack.getItem().isEdible() || stack.is(CROW_STEAL_FOOD)}
     * — the same two conditions as AM's own {@code EntityCrow.isCrowEdible}, translated to a
     * BB-namespaced tag ({@link #CROW_STEAL_FOOD}) instead of importing {@code AMTagRegistry}.
     * This class must never import anything from {@code alexsmobs} or {@code buildingbridges}.
     *
     * <p>Removal takes exactly count 1, matching {@code EntityCrow.onGetItem}'s own
     * {@code duplicate.setCount(1)} — a crow only ever carries a single item in its beak.
     *
     * @return the single-count stack actually removed, or {@link ItemStack#EMPTY}
     */
    public ItemStack consumeCrowStealItem() {
        if (level().isClientSide) return ItemStack.EMPTY;

        if (deadMode
                || isRemoved()
                || !isAlive()
                || !level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)
                || hasOpenCorpseMenu()) {
            return ItemStack.EMPTY;
        }

        List<Integer> candidates = new ArrayList<>();
        for (int index = GENERAL_START; index < BASE_SLOTS; index++) {
            ItemStack stack = getItem(index);
            if (isSlotActive(index) && !stack.isEmpty()
                    && (stack.getItem().isEdible() || stack.is(CROW_STEAL_FOOD))) {
                candidates.add(index);
            }
        }
        if (candidates.isEmpty()) return ItemStack.EMPTY;

        int index = candidates.get(random.nextInt(candidates.size()));
        ItemStack removed = removeItem(index, 1);
        if (removed.isEmpty()) return ItemStack.EMPTY;
        return removed;
    }

    /**
     * Attempts one capped gore drop for a completed feeding cycle. Rotten flesh is the sole output.
     * It spawns more than two blocks horizontally from the corpse with a short pickup delay and a
     * slight outward impulse, keeping it outside Naturalist's one-block-expanded pickup box around
     * a perched vulture instead of letting that vulture reabsorb the drop on its next AI tick. The
     * persistent counter advances only after the entity was accepted into the server level.
     *
     * @return true only when a rotten-flesh item entity was actually spawned
     */
    public boolean dropVultureGore() {
        if (level().isClientSide
                || deadMode
                || isRemoved()
                || !isAlive()
                || !WhatRemainsConfig.enableVultureCorpseFeeding
                || !isVultureFeedableStage()
                || vultureGoreDrops >= Math.max(0, WhatRemainsConfig.vultureMaxGoreDropsPerCorpse)) {
            return false;
        }
        if (random.nextDouble() >= WhatRemainsConfig.vultureGoreDropChance) return false;

        double angle = random.nextDouble() * Math.PI * 2.0D;
        double outwardX = Math.cos(angle);
        double outwardZ = Math.sin(angle);
        ItemEntity gore = new ItemEntity(level(),
                getX() + outwardX * VULTURE_GORE_DROP_DISTANCE,
                getY() + 0.25D,
                getZ() + outwardZ * VULTURE_GORE_DROP_DISTANCE,
                new ItemStack(Items.ROTTEN_FLESH));
        gore.setPickUpDelay(VULTURE_GORE_PICKUP_DELAY_TICKS);
        gore.setDeltaMovement(outwardX * 0.08D, 0.12D, outwardZ * 0.08D);
        if (!level().addFreshEntity(gore)) return false;

        vultureGoreDrops++;
        return true;
    }

    /** "Attract-a-crowd" — round 17. True once a landed fly has stayed put long enough (see
     *  {@link #FLY_CROWD_SUCK_TICKS}) to have "called in" reinforcements; {@code
     *  WhatRemainsFlyCorpseEvents} gates its faster crowd-join spawn chance on this rather than the
     *  slower single-seed-fly chance it uses before this is true. */
    public boolean hasAttractedFlyCrowd() {
        return hasAttractedFlyCrowd;
    }

    /** Advances {@link #landedSuckTicks} once per second (called from {@link #tick()}'s existing
     *  20-tick block, so the {@code +20} step matches how often this actually runs) and latches
     *  {@link #hasAttractedFlyCrowd} once the threshold is reached. */
    private void updateFlyCrowdProgress() {
        if (hasAttractedFlyCrowd) return;
        if (landedFlyCount <= 0) {
            landedSuckTicks = 0;
            return;
        }
        landedSuckTicks += 20;
        if (landedSuckTicks >= FLY_CROWD_SUCK_TICKS) {
            hasAttractedFlyCrowd = true;
        }
    }

    /** Current decay stage derived from age. */
    public DecayStage getDecayStage() {
        return DecayStage.fromAge(getCorpseAgeTicks());
    }

    /** Progress [0..1] within the current decay stage. */
    public float getDecayProgressWithinStage() {
        return DecayStage.progressWithinStage(getCorpseAgeTicks());
    }

    /** True when the corpse has reached the COLLAPSE stage. */
    public boolean isReadyForFinalDrop() {
        return DecayStage.isReadyForFinalDrop(getCorpseAgeTicks());
    }

    // ── XP storage ───────────────────────────────────────────────────────────

    /** Get the XP points stored in this corpse. */
    public int getStoredXp() {
        return this.entityData.get(DATA_STORED_XP);
    }

    /** Set the XP points stored in this corpse (server-side). */
    public void setStoredXp(int xp) {
        this.entityData.set(DATA_STORED_XP, Math.max(0, xp));
    }

    /**
     * Drain up to {@code maxDrain} XP from the corpse.
     * @return the actual amount drained
     */
    public int drainXp(int maxDrain) {
        int stored = getStoredXp();
        int drained = Math.min(stored, maxDrain);
        setStoredXp(stored - drained);
        return drained;
    }

    /** The dead player's XP level at the moment of death — see {@link #deathLevel}'s own javadoc. */
    public int getDeathLevel() {
        return deathLevel;
    }

    /** Set once, at spawn ({@code PlayerDeathHandler}), never touched again. */
    public void setDeathLevel(int level) {
        this.deathLevel = Math.max(0, level);
    }

    /**
     * Rolls the corpse-destruction leftover-XP payout ({@link XpVessel#rollCorpseDestructionRecovery})
     * against whatever XP is STILL stored (the player may have already drained some via
     * {@link #vacuumXpFromCorpse}) and drops the survivors as real orbs at the corpse's position.
     * Whatever the roll doesn't keep is simply never spawned — destroyed, same rule as the original
     * death roll. Called from every corpse-removal path (shovel-bury, decay timeout, final collapse)
     * right before {@code discard()}.
     */
    private void dropStoredXpAsOrbs() {
        if (level().isClientSide) return;
        int stored = getStoredXp();
        if (stored <= 0) return;
        setStoredXp(0);

        int recovered = XpVessel.rollCorpseDestructionRecovery(stored, deathLevel, this.random);
        if (recovered <= 0) return;

        int[] chunks = XpVessel.canonicalPartition(recovered);
        XpVessel.spawnOrbsAt((net.minecraft.server.level.ServerLevel) level(), position(), chunks);
    }

    // ── Curios slot management (dynamic sizing) ────────────────────────────

    /** Allocate curios storage for the given count. Called on death capture. */
    public void initCuriosSlots(int count) {
        curiosItems = NonNullList.withSize(count, ItemStack.EMPTY);
        curiosSlotTypes.clear();
        for (int i = 0; i < count; i++) curiosSlotTypes.add("");
        this.entityData.set(DATA_CURIOS_COUNT, count);
        syncCuriosTypesData();
    }

    /**
     * Ensures {@link #curiosItems} and {@link #curiosSlotTypes} are at least
     * {@code minSize} long, preserving existing contents. This is critical on
     * the client where curiosItems starts at size 0 — the menu's slot sync
     * calls {@link #setItem} with curios indices before the list is sized.
     */
    private void ensureCuriosCapacity(int minSize) {
        if (curiosItems.size() < minSize) {
            NonNullList<ItemStack> grown = NonNullList.withSize(minSize, ItemStack.EMPTY);
            for (int i = 0; i < curiosItems.size(); i++) {
                grown.set(i, curiosItems.get(i));
            }
            curiosItems = grown;
        }
        while (curiosSlotTypes.size() < minSize) {
            curiosSlotTypes.add("");
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        // Client-side only: the server's own `ownerProfile` field is always the authoritative
        // object it explicitly set (possibly with skin Properties already attached) and must not
        // be discarded just because its own `entityData.set()` call re-fires this hook. On the
        // client, `getOwnerProfile()` lazily builds and caches a GameProfile from synced data the
        // FIRST time it's called — for a /summon'd name-only OwnerProfile, that first call happens
        // before the async name->UUID resolution (see resolveOwnerProfileByName) lands, caching a
        // UUID-less profile. Without invalidating here, that stale cache would stick forever even
        // after the server resolves the real UUID and syncs it down, and CorpseFleshBaker would
        // never get a UUID to bake a skin with.
        if (level().isClientSide && (DATA_OWNER_UUID.equals(key) || DATA_OWNER_NAME.equals(key))) {
            ownerProfile = null;
        }
        if (DATA_CURIOS_COUNT.equals(key)) {
            int syncedCount = this.entityData.get(DATA_CURIOS_COUNT);
            if (syncedCount > curiosItems.size()) {
                ensureCuriosCapacity(syncedCount);
            }
        }
        if (DATA_CURIOS_TYPES.equals(key)) {
            String raw = this.entityData.get(DATA_CURIOS_TYPES);
            if (!raw.isEmpty()) {
                String[] parts = raw.split(";", -1);
                ensureCuriosCapacity(parts.length);
                for (int i = 0; i < parts.length; i++) {
                    curiosSlotTypes.set(i, parts[i]);
                }
            }
        }
        // Invalidate the decoded cache on ANY change to the underlying synced/NBT-loaded data —
        // see cachedStuckProjectiles' own javadoc for why this is unguarded by side, unlike the
        // owner-UUID/name branch above.
        if (DATA_STUCK_PROJECTILES.equals(key)) {
            cachedStuckProjectiles = null;
        }

        // RECONCILIATION.md R3: a runtime NBT mutation of any of these three (the documented
        // `/data merge entity ... {AppearanceSeed:...}` / `{StuckProjectiles:[...]}` /
        // `{DeathCause:...}` testing workflow) changes an input CorpseFleshBaker.BakeKey deliberately
        // does NOT carry — see BakeKey's own 14-field contract — so it cannot invalidate a cached bake
        // via a key mismatch on its own. Without this, a corpse re-summoned with a new AppearanceSeed
        // (or given a new killing arrow, or a new DeathCause) after its flesh has already baked once
        // would keep showing the stale texture forever. This method is COMMON code (server calls it
        // too), so the client class it purges is only ever touched from behind BOTH an isClientSide
        // check AND a DistExecutor hop — the established pattern this codebase already uses for common
        // code that must reach a client-only type (e.g. PromptSpellbookNaming#handle) — never a bare
        // import of a client/ class from this unguarded common path.
        if (DATA_APPEARANCE_SEED.equals(key) || DATA_STUCK_PROJECTILES.equals(key) || DATA_DEATH_CAUSE.equals(key)) {
            if (level().isClientSide) {
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () ->
                        com.temog.notminelegacy.whatremains.client.CorpseFleshBaker.getInstance().invalidateCorpse(this.getUUID()));
            }
        }
    }

    /** Number of curios slots. */
    public int getCuriosSize() {
        return this.entityData.get(DATA_CURIOS_COUNT);
    }

    /** Get item from curios slot (0-based curios index). */
    public ItemStack getCuriosItem(int ci) {
        return ci >= 0 && ci < curiosItems.size() ? curiosItems.get(ci) : ItemStack.EMPTY;
    }

    /** Set item in curios slot. */
    public void setCuriosItem(int ci, ItemStack stack) {
        if (ci >= 0 && ci < curiosItems.size()) {
            curiosItems.set(ci, stack);
            setChanged();
        }
    }

    public void setCuriosSlotType(int ci, String type) {
        if (ci >= 0 && ci < curiosSlotTypes.size()) {
            curiosSlotTypes.set(ci, type != null ? type : "");
            syncCuriosTypesData();
        }
    }

    public String getCuriosSlotType(int ci) {
        // Check local list first, but only if it has a real (non-placeholder) value
        if (ci >= 0 && ci < curiosSlotTypes.size()) {
            String local = curiosSlotTypes.get(ci);
            if (!local.isEmpty()) return local;
        }
        // Fall back to synced data for client-side access
        String raw = this.entityData.get(DATA_CURIOS_TYPES);
        if (raw.isEmpty()) return "";
        String[] parts = raw.split(";", -1);
        return ci >= 0 && ci < parts.length ? parts[ci] : "";
    }

    private void syncCuriosTypesData() {
        this.entityData.set(DATA_CURIOS_TYPES, String.join(";", curiosSlotTypes));
    }

    // ── Inventory slot locking (random decay-based) ─────────────────────────

    /**
     * Target number of general slots that remain ACTIVE at each decay stage.
     * Armor slots (0-3) are never locked.
     */
    public static int getTargetActiveGeneralSlots(DecayStage stage) {
        return switch (stage) {
            case FRESH          -> GENERAL_SLOTS;  // 36 — all general slots open
            case EARLY_DECAY    -> 30;
            case MID_DECAY      -> 24;
            case LATE_DECAY     -> 18;
            // ADVANCED_DECAY is the OLD SKELETAL stage (same fraction/position, renamed — see
            // DecayStage's header comment): keeps its old "final lootable count" target unchanged.
            // NEW SKELETAL (true bare exoskeleton) has nothing further to lose loot-wise — slot
            // locking tracks flesh/container integrity, not literal flesh presence — so it stays at
            // the same floor rather than dropping further toward COLLAPSE's full wipe.
            case ADVANCED_DECAY -> 12;
            case SKELETAL       -> 12;
            case COLLAPSE       -> 0;
        };
    }

    // ── Locked-slot bit-mask helpers ─────────────────────────────────────────

    /** Returns true if general-slot index (0-35) is currently locked. */
    public boolean isGeneralSlotLocked(int generalIndex) {
        if (generalIndex < 0 || generalIndex >= GENERAL_SLOTS) return true;
        if (generalIndex < 32) {
            return (this.entityData.get(DATA_LOCKED_SLOTS_LO) & (1 << generalIndex)) != 0;
        } else {
            return (this.entityData.get(DATA_LOCKED_SLOTS_HI) & (1 << (generalIndex - 32))) != 0;
        }
    }

    private void setGeneralSlotLocked(int generalIndex, boolean locked) {
        if (generalIndex < 32) {
            int mask = this.entityData.get(DATA_LOCKED_SLOTS_LO);
            if (locked) mask |= (1 << generalIndex);
            else        mask &= ~(1 << generalIndex);
            this.entityData.set(DATA_LOCKED_SLOTS_LO, mask);
        } else {
            int mask = this.entityData.get(DATA_LOCKED_SLOTS_HI);
            int bit = generalIndex - 32;
            if (locked) mask |= (1 << bit);
            else        mask &= ~(1 << bit);
            this.entityData.set(DATA_LOCKED_SLOTS_HI, mask);
        }
    }

    private int countActiveGeneralSlots() {
        int count = 0;
        for (int i = 0; i < GENERAL_SLOTS; i++) {
            if (!isGeneralSlotLocked(i)) count++;
        }
        return count;
    }

    /** True if the container slot at {@code index} is currently usable. */
    public boolean isSlotActive(int index) {
        // Armor (0-3) and offhand (4) are always active
        if (index >= 0 && index < GENERAL_START) return true;
        // Curios slots (>= BASE_SLOTS) are always active
        if (index >= BASE_SLOTS) return true;
        // General slots: check bit-mask
        int general = index - GENERAL_START;
        if (general < 0 || general >= GENERAL_SLOTS) return false;
        return !isGeneralSlotLocked(general);
    }

    /**
     * Locks random active general slots to match the target for the current
     * decay stage. Ejects items from newly-locked slots.
     */
    private void processDecaySlotTransitions() {
        DecayStage stage = getDecayStage();
        int targetActive = getTargetActiveGeneralSlots(stage);
        int currentActive = countActiveGeneralSlots();

        if (currentActive <= targetActive) return;

        int toLock = currentActive - targetActive;
        lockRandomSlots(toLock);
    }

    private void lockRandomSlots(int count) {
        List<Integer> activeGeneral = new ArrayList<>();
        for (int i = 0; i < GENERAL_SLOTS; i++) {
            if (!isGeneralSlotLocked(i)) {
                activeGeneral.add(i);
            }
        }
        // RandomSource → use java.util.Random seeded from entity's random for shuffling
        java.util.Random javaRandom = new java.util.Random(random.nextLong());
        Collections.shuffle(activeGeneral, javaRandom);
        int toLock = Math.min(count, activeGeneral.size());
        for (int i = 0; i < toLock; i++) {
            int genIdx = activeGeneral.get(i);
            setGeneralSlotLocked(genIdx, true);
            // Eject any item in the slot that just locked
            int containerIdx = genIdx + GENERAL_START;
            ItemStack stack = inventory.get(containerIdx);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack, 0.25f);
                inventory.set(containerIdx, ItemStack.EMPTY);
            }
        }
        setChanged();
    }

    /**
     * Rescues any item found sitting in a LOCKED general slot.
     *
     * <p>Nothing should ever put one there — {@link #lockRandomSlots(int)} ejects a slot's contents
     * at the moment it locks, and {@link #shiftInventory()}/{@link #vacuumItem(ItemStack)} now
     * refuse to write into locked slots. This exists for the two cases that guard alone cannot
     * cover: corpses saved by an earlier build that DID strand items this way, and any future
     * writer that forgets the rule. Without it, an already-stranded item stays invisible and
     * un-takeable ({@code CorpseMenu.GeneralSlot} gates {@code mayPickup} on
     * {@link #isSlotActive(int)}) until the corpse fully collapses.
     *
     * <p>Recovery is preferred over ejection: the stack is relocated into an active slot or the
     * overflow buffer if there is room, and only thrown to the ground when there genuinely is not —
     * which is exactly what locking the slot would have done to it in the first place.
     */
    private void reconcileLockedSlots() {
        for (int general = 0; general < GENERAL_SLOTS; general++) {
            if (!isGeneralSlotLocked(general)) continue;
            int index = general + GENERAL_START;
            ItemStack stranded = inventory.get(index);
            if (stranded.isEmpty()) continue;

            inventory.set(index, ItemStack.EMPTY);
            ItemStack leftover = relocateIntoActiveStorage(stranded);
            if (!leftover.isEmpty()) {
                spawnAtLocation(leftover, 0.25f);
            }
            setChanged();
        }
    }

    /**
     * Moves {@code stack} into active general slots (stacking first, then empty ones), then the
     * overflow buffer. Deliberately does NOT run {@link #applyItemDamage(ItemStack)} — this is an
     * internal relocation of an item the corpse already holds, not a fresh death deposit.
     *
     * @return whatever could not be placed (empty if all of it fit)
     */
    private ItemStack relocateIntoActiveStorage(ItemStack stack) {
        for (int i = GENERAL_START; i < BASE_SLOTS && !stack.isEmpty(); i++) {
            if (!isSlotActive(i)) continue;
            ItemStack slot = inventory.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, stack)) {
                int space = Math.min(slot.getMaxStackSize(), getMaxStackSize()) - slot.getCount();
                if (space > 0) {
                    int transfer = Math.min(space, stack.getCount());
                    slot.grow(transfer);
                    stack.shrink(transfer);
                }
            }
        }
        for (int i = GENERAL_START; i < BASE_SLOTS && !stack.isEmpty(); i++) {
            if (!isSlotActive(i)) continue;
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, stack.copy());
                stack.setCount(0);
            }
        }
        int maxAux = WhatRemainsConfig.corpseInventorySize - BASE_SLOTS;
        if (!stack.isEmpty() && maxAux > 0 && auxInventory.size() < maxAux) {
            auxInventory.addLast(stack.copy());
            stack.setCount(0);
        }
        return stack;
    }

    /**
     * Refuses insertion into a locked general slot.
     *
     * <p>{@code CorpseMenu}'s own slots already gate on {@link #isSlotActive(int)}, so this changes
     * nothing for a player using the GUI. It closes the one remaining external door: a corpse is an
     * entity {@link Container}, and vanilla hoppers resolve entity containers at their target
     * position, so a hopper aimed at a corpse could otherwise push items straight into a locked
     * slot — reintroducing the exact stranding {@link #reconcileLockedSlots()} exists to repair.
     */
    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return isSlotActive(index);
    }

    /** Drops all remaining items — used at COLLAPSE stage. Deliberately ignores slot locking:
     *  everything the corpse still physically holds drops, including anything a pre-fix save
     *  stranded in a locked slot that {@link #reconcileLockedSlots()} could not find room for. */
    public void dropAllRemainingItems() {
        dropAllItems();
    }

    // ── Rotation helpers ────────────────────────────────────────────────────

    public void setCorpseRotation(float yaw) {
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
    }

    private void syncConfiguredHealth() {
        var maxHealthAttr = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }

        double targetMaxHealth = Math.max(1.0D, WhatRemainsConfig.corpseHP);
        if (Math.abs(maxHealthAttr.getBaseValue() - targetMaxHealth) > 0.001D) {
            maxHealthAttr.setBaseValue(targetMaxHealth);
            if (this.getHealth() > targetMaxHealth || this.getHealth() <= 0.0F) {
                this.setHealth((float) targetMaxHealth);
            }
        } else if (!healthInitialized && this.getHealth() <= 0.0F) {
            this.setHealth((float) targetMaxHealth);
        }

        healthInitialized = true;
    }

    // ── Environmental exposure sampling (char/frost/waterlogged + decay-rate multiplier) ───────

    /**
     * Samples this corpse's current physical surroundings (fluid contact, biome temperature, sky
     * visibility), updates the three persistent exposure trackers against every currently-loaded
     * {@code CorpseEnvironmentLoader} spec, and recomputes {@link #cachedDecayRateMultiplier}.
     * Called once per second from {@link #tick()}'s existing {@code tickCount % 20 == 0} block —
     * each corpse's own {@code tickCount} already staggers this naturally across corpses (they're
     * created on different ticks), so no separate cross-entity staggering scheme is needed here.
     */
    private void updateEnvironmentalExposure() {
        CorpseEnvironment env = CorpseEnvironmentSampler.sample(this);

        setCharLevel(nextTrackerLevel(CorpseEnvironmentSpec.TRACKER_CHAR, env, getCharLevel(), true));
        setFrostLevel(nextTrackerLevel(CorpseEnvironmentSpec.TRACKER_FROST, env, getFrostLevel(), false));
        setWaterloggedLevel(nextTrackerLevel(CorpseEnvironmentSpec.TRACKER_WATERLOGGED, env, getWaterloggedLevel(), false));
        setScorchLevel(nextTrackerLevel(CorpseEnvironmentSpec.TRACKER_SCORCH, env, getScorchLevel(), true));
        setCalcifyLevel(nextTrackerLevel(CorpseEnvironmentSpec.TRACKER_CALCIFY, env, getCalcifyLevel(), true));

        updateResolvedStyle(DATA_SCORCH_STYLE, CorpseEnvironmentSpec.TRACKER_SCORCH, env);
        updateResolvedStyle(DATA_WATERLOGGED_STYLE, CorpseEnvironmentSpec.TRACKER_WATERLOGGED, env);

        cachedDecayRateMultiplier = computeDecayRateMultiplier(env);
    }

    /**
     * Refreshes {@code styleAccessor} from {@link CorpseEnvironmentLoader#resolveStyleKey} — but
     * only when that call actually resolves SOMETHING (a non-empty style). When nothing currently
     * matches, the field is left exactly as-is: a one-way tracker's chosen style must survive the
     * corpse later leaving the environment that produced it, exactly like the numeric level itself
     * never resetting (see the field's own javadoc). Harmless no-op for a corpse that has never
     * matched anything on this tracker yet — the field simply stays at its {@code ""} default.
     */
    private void updateResolvedStyle(EntityDataAccessor<String> styleAccessor, String tracker, CorpseEnvironment env) {
        String resolved = CorpseEnvironmentLoader.resolveStyleKey(tracker, env);
        if (!resolved.isEmpty()) {
            this.entityData.set(styleAccessor, resolved);
        }
    }

    /**
     * Scans every loaded spec targeting {@code tracker}: if ANY of them currently matches the
     * sampled environment, accumulates at the highest {@code accumulate_rate} among the matching
     * specs; otherwise decays at the highest {@code decay_rate} among ALL specs targeting this
     * tracker (matching or not — decay is "return to baseline while nothing applies", not tied to
     * any one non-matching spec). Rates are intensity-per-real-tick, so each is multiplied by 20 to
     * convert to intensity-per-check (this method runs once per 20 ticks).
     * <p>
     * {@code oneWay == true} (the {@code char} tracker only) additionally clamps the result to
     * never fall below {@code current} — independent, tick-side enforcement of the same rule
     * {@code CorpseEnvironmentLoader} already enforces loader-side by forcing every char-feeding
     * spec's {@code decay_rate} to {@code 0.0}. Neither enforcement point relies on the other.
     */
    private static float nextTrackerLevel(String tracker, CorpseEnvironment env, float current, boolean oneWay) {
        boolean anyMatch = false;
        double accumulateRate = 0.0;
        double decayRate = 0.0;

        for (CorpseEnvironmentSpec spec : CorpseEnvironmentLoader.all()) {
            if (!spec.tracker().equals(tracker)) continue;
            decayRate = Math.max(decayRate, spec.decayRate());
            if (spec.matches(env)) {
                anyMatch = true;
                accumulateRate = Math.max(accumulateRate, spec.accumulateRate());
            }
        }

        float next = anyMatch
                ? current + (float) (accumulateRate * 20.0)
                : current - (float) (decayRate * 20.0);
        next = Math.max(0.0F, Math.min(1.0F, next));

        if (oneWay) {
            next = Math.max(next, current);
        }
        return next;
    }

    /**
     * Piecewise-linear decay-rate multiplier from biome temperature ({@code Biome#getBaseTemperature()})
     * plus a flat penalty when the corpse can't see the sky ({@code Level#canSeeSky()}), stacked
     * multiplicatively. The cold-side and hot-side segments share {@code decayNeutralTemperature}
     * as their common endpoint, so they meet at exactly {@code 1.0x} with no discontinuity when
     * {@code biomeTemperature == decayNeutralTemperature}.
     */
    private static float computeDecayRateMultiplier(CorpseEnvironment env) {
        double neutral = WhatRemainsConfig.decayNeutralTemperature;
        double coldRef = WhatRemainsConfig.decayColdReferenceTemperature;
        double coldMult = WhatRemainsConfig.decayColdRateMultiplier;
        double hotRef = WhatRemainsConfig.decayHotReferenceTemperature;
        double hotMult = WhatRemainsConfig.decayHotRateMultiplier;
        double noSkyMult = WhatRemainsConfig.decayNoSkyRateMultiplier;

        double temp = env.biomeTemperature();
        double tempMultiplier;
        if (temp <= coldRef) {
            tempMultiplier = coldMult;
        } else if (temp >= hotRef) {
            tempMultiplier = hotMult;
        } else if (temp <= neutral) {
            double span = Math.max(neutral - coldRef, 1.0E-6);
            double fraction = (temp - coldRef) / span;
            tempMultiplier = coldMult + fraction * (1.0 - coldMult);
        } else {
            double span = Math.max(hotRef - neutral, 1.0E-6);
            double fraction = (temp - neutral) / span;
            tempMultiplier = 1.0 + fraction * (hotMult - 1.0);
        }

        double skyMultiplier = env.canSeeSky() ? 1.0 : noSkyMult;
        return (float) (tempMultiplier * skyMultiplier);
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();

        // Corpse should always be affected by gravity.
        if (this.isNoGravity()) {
            this.setNoGravity(false);
        }

        if (!level().isClientSide && (!healthInitialized || tickCount % 20 == 0)) {
            syncConfiguredHealth();
        }

        // ── Age tracking (server only) ──────────────────────────────────────
        if (!level().isClientSide) {
            // Goal.stop() is not guaranteed when a feeding vulture dies, is removed or unloads.
            // Prune before computing decay so a leaked UUID can never accelerate this corpse for
            // even one extra tick after the server can no longer resolve a valid nearby feeder.
            pruneFeedingVultureLeases();

            // Flies landed on the corpse and vultures holding feeding leases accelerate its own
            // decay. Fractional gains are accumulated here and only flushed to the synced int age
            // field once a whole tick has built up, avoiding a float-typed synced accessor. Flies
            // remain event-driven; vulture UUID validation is the deliberate per-tick backstop that
            // prevents lifecycle leaks in their reservation-based contribution.
            //
            // Master-switch gate (defect fix): WhatRemainsConfig#enableVultureCorpseFeeding's own
            // config comment promises that turning it off prevents accelerated decay from feeding
            // vultures. The TERM itself is gated here (rather than zeroing the counter it reads)
            // so getFeedingVultureCount() stays a truthful count of held leases for any other
            // reader, even while the switch suppresses this particular effect. In practice
            // reserveFeedingSlot() already refuses new leases while the switch is off, so this is
            // belt-and-braces against a lease held before a hot config reload flipped it off mid-game.
            corpseAgeAccumulator += cachedDecayRateMultiplier
                    * (1.0F
                    + landedFlyCount * (float) WhatRemainsConfig.flyDecayBonusPerFly
                    + (WhatRemainsConfig.enableVultureCorpseFeeding
                            ? getFeedingVultureCount() * (float) WhatRemainsConfig.vultureDecayBonusPerVulture
                            : 0.0F));
            int wholeTicks = (int) corpseAgeAccumulator;
            if (wholeTicks > 0) {
                setCorpseAgeTicks(getCorpseAgeTicks() + wholeTicks);
                corpseAgeAccumulator -= wholeTicks;
            }

            // Process decay-based slot locking + environmental exposure sampling once per second
            if (tickCount % 20 == 0) {
                processDecaySlotTransitions();
                // Runs immediately after the locking pass so a slot that just locked is already
                // reconciled, and so corpses saved by a pre-fix build get repaired on their first
                // loaded second rather than staying silently stranded until COLLAPSE.
                reconcileLockedSlots();
                updateEnvironmentalExposure();
                updateFlyCrowdProgress();
                updateDeathHeldItemConsistency();
            }

            // Final collapse at COLLAPSE stage
            if (isReadyForFinalDrop()) {
                dropAllRemainingItems();
                dropStoredXpAsOrbs();
                discard();
                return;
            }
        }

        // Legacy decay logic (server only, once per second) — kept for config compat
        if (!level().isClientSide && WhatRemainsConfig.enableDecay && tickCount % 20 == 0) {
            if (WhatRemainsConfig.decayOnlyWhenEmpty) {
                if (!isInventoryEmpty()) {
                    deathTimestamp = level().getGameTime();
                }
            }
            if ((level().getGameTime() - deathTimestamp) > WhatRemainsConfig.decayTimeTicks) {
                dropAllItems();
                dropStoredXpAsOrbs();
                discard();
                return;
            }
        }

        // Vacuum loose items
        if (vacuumTimer < WhatRemainsConfig.vacuumTicks && !level().isClientSide && hasSpaceLeft()) {
            int r = WhatRemainsConfig.vacuumRadius;
            AABB box = new AABB(
                    getX() - r, getY() - r, getZ() - r,
                    getX() + r, getY() + r, getZ() + r);
            List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, box);
            for (ItemEntity ie : items) {
                ItemStack leftover = vacuumItem(ie.getItem().copy());
                ie.discard();
                if (leftover != null && !leftover.isEmpty()) {
                    spawnAtLocation(leftover, 0.0f);
                }
            }
            vacuumTimer++;
        }

        // Condense inventory
        shiftInventory();

        // Flat corpse bodies don't always receive vanilla push calls reliably,
        // so run a lightweight contact-pass to apply push when players overlap.
        if (!level().isClientSide) {
            applyContactPushFromPlayers();
        }

        // Sync open container menus every 5 ticks, only when inventory changed
        if (!level().isClientSide && inventoryDirty && tickCount % 5 == 0) {
            viewers.removeIf(viewer -> viewer == null
                    || viewer.isRemoved()
                    || viewer.containerMenu == viewer.inventoryMenu
                    || !stillValid(viewer));
            for (Player viewer : viewers) {
                if (viewer instanceof net.minecraft.server.level.ServerPlayer sp
                        && sp.containerMenu instanceof CorpseMenu) {
                    sp.containerMenu.broadcastChanges();
                }
            }
            inventoryDirty = false;
        }

        // Dead-mode: drop everything and remove
        if (deadMode) {
            dropAllItems();
            dropStoredXpAsOrbs();
            discard();
            return;
        }

        // Fell out of the world in non-End dimensions
        if (getY() < level().getMinBuildHeight() - 64) {
            if (level().dimension() != Level.END) {
                BlockPos surface = new BlockPos(blockPosition().getX(), level().getSeaLevel(), blockPosition().getZ());
                teleportTo(surface.getX() + 0.5, surface.getY() + 1.0, surface.getZ() + 0.5);
            } else {
                discard();
            }
        }
    }

    // ── AI overrides (corpses don't move) ───────────────────────────────────

    @Override
    protected void registerGoals() {
        // No AI goals — corpses are stationary.
    }

    @Override
    public boolean removeWhenFarAway(double dist) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public float getPickRadius() {
        return 0.15F;
    }

    /** An entity with a bounding-box volume below this fraction of the corpse's own is too small
     *  to physically interact with it at all — too light to budge it (see {@link #push(Entity)})
     *  and, via BuildingBridges' {@code CorpseSmallEntityCollisionMixin}, too small to be blocked
     *  by it as a solid obstacle either (so it doesn't need to route around one). Public/static so
     *  that mixin can reuse the exact same threshold rather than duplicating it. */
    public static final double MIN_INTERACTOR_VOLUME_FRACTION = 0.25D;

    public static double boundingBoxVolume(Entity entity) {
        AABB box = entity.getBoundingBox();
        return box.getXsize() * box.getYsize() * box.getZsize();
    }

    /** True when {@code other} is too small (by bounding-box volume) to push or be blocked by
     *  this corpse — see {@link #MIN_INTERACTOR_VOLUME_FRACTION}. */
    public boolean isTooSmallToInteract(Entity other) {
        return boundingBoxVolume(other) < boundingBoxVolume(this) * MIN_INTERACTOR_VOLUME_FRACTION;
    }

    @Override
    public void push(Entity other) {
        if (other == null || this.noPhysics || other.noPhysics) {
            return;
        }
        if (this.isPassengerOfSameVehicle(other)) {
            return;
        }
        if (isTooSmallToInteract(other)) {
            return;
        }

        super.push(other);
        if (!this.isPushable()) {
            return;
        }

        // Add a small deterministic impulse so "walk into corpse" visibly moves it.
        Vec3 delta = new Vec3(this.getX() - other.getX(), 0.0D, this.getZ() - other.getZ());
        double lenSq = delta.lengthSqr();
        if (lenSq > 1.0E-6D) {
            Vec3 impulse = delta.normalize().scale(0.08D);
            this.move(MoverType.SELF, impulse);
            this.setDeltaMovement(this.getDeltaMovement().add(impulse));
            this.hasImpulse = true;
        }
    }

    private void applyContactPushFromPlayers() {
        AABB contactBox = this.getBoundingBox().inflate(0.12D, 0.02D, 0.12D);
        List<Player> players = this.level().getEntitiesOfClass(Player.class, contactBox,
                player -> player.isAlive() && !player.isSpectator());
        if (players.isEmpty()) {
            return;
        }

        Vec3 totalImpulse = Vec3.ZERO;
        for (Player player : players) {
            Vec3 away = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
            if (away.lengthSqr() < 1.0E-6D) {
                double yawRad = Math.toRadians(player.getYRot());
                away = new Vec3(-Math.sin(yawRad), 0.0D, Math.cos(yawRad));
            }
            totalImpulse = totalImpulse.add(away.normalize().scale(0.05D));
        }

        if (totalImpulse.lengthSqr() > 1.0E-6D) {
            // Clamp to avoid huge acceleration in multiplayer crowding.
            if (totalImpulse.lengthSqr() > 0.01D) {
                totalImpulse = totalImpulse.normalize().scale(0.10D);
            }
            this.move(MoverType.SELF, totalImpulse);
            this.setDeltaMovement(this.getDeltaMovement().add(totalImpulse.scale(0.45D)));
            this.hasImpulse = true;
        }
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean canBreatheUnderwater() {
        return true;
    }

    @Override
    public int getExperienceReward() {
        return 0;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    // ── Damage handling ─────────────────────────────────────────────────────

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        if (isRemoved()) return false;

        // Shovel hit detection — always works regardless of invulnerability
        Entity attacker = source.getEntity();
        if (attacker instanceof Player player) {
            ItemStack held = player.getMainHandItem();
            if (held.getItem() instanceof ShovelItem) {
                shovelHits++;
                // Play dig sound feedback
                level().playSound(null, blockPosition(), SoundEvents.GRAVEL_HIT,
                        SoundSource.BLOCKS, 0.5f, 0.8f + level().random.nextFloat() * 0.4f);
                if (shovelHits >= WhatRemainsConfig.shovelHitsToBury) {
                    deadMode = true;
                }
                return true;
            }
        }

        // Wall suffocation → eject from wall
        if (source.is(DamageTypes.IN_WALL)) {
            jumpOutOfWall();
        }

        // Fell out of the world
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            deadMode = true;
            return true;
        }

        // Check per-source configuration
        if (WhatRemainsConfig.invulnerable) return false;

        boolean allowed = false;
        if (WhatRemainsConfig.hurtByAll)                                           allowed = true;
        if (WhatRemainsConfig.hurtByWeapons && attacker instanceof LivingEntity)   allowed = true;
        if (WhatRemainsConfig.hurtByFire && source.is(DamageTypes.IN_FIRE))        allowed = true;
        if (WhatRemainsConfig.hurtByFire && source.is(DamageTypes.ON_FIRE))        allowed = true;
        if (WhatRemainsConfig.hurtByFire && source.is(DamageTypes.LAVA))           allowed = true;
        if (WhatRemainsConfig.hurtByExplosions && source.is(DamageTypes.EXPLOSION)) allowed = true;
        if (WhatRemainsConfig.hurtByExplosions && source.is(DamageTypes.PLAYER_EXPLOSION)) allowed = true;
        if (WhatRemainsConfig.hurtByFall && source.is(DamageTypes.FALL))           allowed = true;
        if (WhatRemainsConfig.hurtByCactus && source.is(DamageTypes.CACTUS))       allowed = true;
        if (WhatRemainsConfig.hurtBySuffocation && source.is(DamageTypes.IN_WALL)) allowed = true;
        if (WhatRemainsConfig.hurtByOther && !allowed)                             allowed = true;

        if (!allowed) return false;

        boolean result = super.hurt(source, amount);
        if (getHealth() <= 0) {
            deadMode = true;
        }
        return result;
    }

    // ── Wall-ejection logic ─────────────────────────────────────────────────

    private void jumpOutOfWall() {
        BlockPos pos = blockPosition();
        // Check cardinal directions + up/down for an open space
        BlockPos[] neighbors = {
                pos.above(), pos.east(), pos.west(), pos.north(), pos.south(), pos.below()
        };
        for (BlockPos n : neighbors) {
            BlockState state = level().getBlockState(n);
            if (!state.isSuffocating(level(), n)) {
                teleportTo(n.getX() + 0.5, n.getY() + 0.015625, n.getZ() + 0.5);
                return;
            }
        }
        // Random displacement as last resort
        double dx = (random.nextDouble() - 0.5) * 3.0;
        double dy = random.nextDouble() * 1.5;
        double dz = (random.nextDouble() - 0.5) * 3.0;
        teleportTo(getX() + dx, getY() + dy, getZ() + dz);
    }

    // ── Inventory condensation ──────────────────────────────────────────────

    /**
     * Pulls overflow items from {@link #auxInventory} into free general slots.
     *
     * <p><b>Only ACTIVE general slots are eligible.</b> This used to fill any empty general slot,
     * locked ones included, which stranded the item: {@code CorpseMenu.GeneralSlot} gates both
     * {@code mayPlace} and {@code mayPickup} on {@link #isSlotActive(int)}, so an item sitting in a
     * locked slot is invisible and un-takeable in the GUI. It is not destroyed — {@link
     * #dropAllItems()} iterates every slot regardless of lock state, so it still drops at COLLAPSE
     * — but until then it is simply gone as far as the player is concerned, and under
     * {@code decayOnlyWhenEmpty} it also permanently re-arms the legacy despawn timer (see
     * {@link #isInventoryEmpty()}'s caller) because the corpse never reads as empty.
     *
     * <p>Slot locking itself already ejects whatever occupied a slot at the moment it locked (see
     * {@link #lockRandomSlots(int)}), so re-filling that slot from a different code path was always
     * working against the decay design rather than implementing it.
     */
    private void shiftInventory() {
        if (auxInventory.isEmpty()) return;
        boolean moved = false;
        // Pull from overflow into any empty ACTIVE general slot — never a locked one.
        for (int i = GENERAL_START; i < BASE_SLOTS; i++) {
            if (!isSlotActive(i)) continue;
            if (inventory.get(i).isEmpty() && !auxInventory.isEmpty()) {
                inventory.set(i, auxInventory.pop());
                moved = true;
            }
        }
        if (moved) {
            setChanged();
        }
    }

    // ── Item vacuum ─────────────────────────────────────────────────────────

    /**
     * Attempts to add an item to this corpse's inventory.
     *
     * <p><b>Only ACTIVE general slots are eligible</b>, for both the stacking and the empty-slot
     * pass — see {@link #shiftInventory()} for why writing into a locked slot strands the item.
     * The stacking pass skips locked slots too, not merely as symmetry: growing a stack that
     * already sits in a locked slot (from a save written before this fix) would quietly pour more
     * items into the unreachable pile.
     *
     * @return leftover items that couldn't fit, or {@code null} if all absorbed.
     */
    public ItemStack vacuumItem(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        item = applyItemDamage(item);

        // Try stacking into existing ACTIVE general slots first
        for (int i = GENERAL_START; i < BASE_SLOTS; i++) {
            if (!isSlotActive(i)) continue;
            ItemStack slot = inventory.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, item)) {
                int space = Math.min(slot.getMaxStackSize(), getMaxStackSize()) - slot.getCount();
                if (space > 0) {
                    int transfer = Math.min(space, item.getCount());
                    slot.grow(transfer);
                    item.shrink(transfer);
                    setChanged();
                    if (item.isEmpty()) return null;
                }
            }
        }

        // Then try empty ACTIVE general slots — never a locked one.
        for (int i = GENERAL_START; i < BASE_SLOTS; i++) {
            if (!isSlotActive(i)) continue;
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, item);
                setChanged();
                return null;
            }
        }

        // Overflow buffer
        int maxAux = WhatRemainsConfig.corpseInventorySize - BASE_SLOTS;
        if (maxAux > 0 && auxInventory.size() < maxAux) {
            // Try stacking into aux first
            for (ItemStack bufItem : auxInventory) {
                if (ItemStack.isSameItemSameTags(bufItem, item)) {
                    int space = Math.min(bufItem.getMaxStackSize(), getMaxStackSize()) - bufItem.getCount();
                    if (space > 0) {
                        int transfer = Math.min(space, item.getCount());
                        bufItem.grow(transfer);
                        item.shrink(transfer);
                        setChanged();
                        if (item.isEmpty()) return null;
                    }
                }
            }
            auxInventory.addLast(item);
            setChanged();
            return null;
        }

        return item.copy();
    }

    /**
     * Whether the vacuum has anywhere to actually put an item.
     *
     * <p>Counts only ACTIVE empty general slots. This has to agree with {@link #vacuumItem} about
     * what "space" means: once that method stopped writing into locked slots, a {@code hasSpaceLeft}
     * that still counted them would let the vacuum loop run against a corpse with no real room,
     * discarding each nearby {@code ItemEntity} and immediately re-spawning the leftover — visible
     * item flicker and a needless entity churn, for no gain.
     */
    private boolean hasSpaceLeft() {
        for (int i = GENERAL_START; i < BASE_SLOTS; i++) {
            if (isSlotActive(i) && inventory.get(i).isEmpty()) return true;
        }
        int maxAux = Math.max(WhatRemainsConfig.corpseInventorySize - BASE_SLOTS, 0);
        return auxInventory.size() < maxAux;
    }

    private boolean isInventoryEmpty() {
        for (int i = 0; i < BASE_SLOTS; i++) {
            if (!inventory.get(i).isEmpty()) return false;
        }
        for (ItemStack ci : curiosItems) {
            if (!ci.isEmpty()) return false;
        }
        return auxInventory.isEmpty();
    }

    // ── Item damage on death ────────────────────────────────────────────────

    public static ItemStack applyItemDamage(ItemStack stack) {
        int dmg = WhatRemainsConfig.itemDamageOnDeath;
        if (dmg == 0 || stack.isEmpty()) return stack;
        if (stack.isDamageableItem() && !stack.isStackable() && stack.getMaxDamage() > 0) {
            int newDamage = stack.getDamageValue() + dmg;
            stack.setDamageValue(Math.min(newDamage, stack.getMaxDamage() - 1));
        }
        return stack;
    }

    // ── Drop all items ──────────────────────────────────────────────────────

    private void dropAllItems() {
        for (int i = BASE_SLOTS - 1; i >= 0; i--) {
            ItemStack stack = inventory.get(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack, 0.0f);
                inventory.set(i, ItemStack.EMPTY);
            }
        }
        for (int i = 0; i < curiosItems.size(); i++) {
            ItemStack stack = curiosItems.get(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack, 0.0f);
                curiosItems.set(i, ItemStack.EMPTY);
            }
        }
        for (ItemStack stack : auxInventory) {
            if (stack != null && !stack.isEmpty()) {
                spawnAtLocation(stack, 0.0f);
            }
        }
        auxInventory.clear();
        setChanged();
    }

    // ── Player interaction (open chest GUI / XP bottle fill) ──────────────

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || deadMode || isRemoved()) {
            return InteractionResult.PASS;
        }

        if (!level().isClientSide) {
            ItemStack held = player.getItemInHand(hand);

            // SNEAK + secondary-interact with a non-full xp_bottle: vacuum stored XP out of the
            // corpse instead of opening the GUI. Checked first — sneaking with a vessel in hand is
            // an unambiguous vacuum-intent signal, so it must never fall through to the chest screen.
            if (player.isShiftKeyDown() && XpVessel.enabled() && XpVessel.isXpBottle(held)
                    && !XpVessel.isFull(held)) {
                return vacuumXpFromCorpse(player, hand, held);
            }

            // XP bottle fill: right-click corpse with a glass bottle
            if (held.is(net.minecraft.world.item.Items.GLASS_BOTTLE) && getStoredXp() > 0) {
                return fillBottleFromCorpse(player, hand, held);
            }
        }

        if (!level().isClientSide) {
            net.minecraftforge.network.NetworkHooks.openScreen(
                    (net.minecraft.server.level.ServerPlayer) player,
                    new SimpleMenuProvider(
                            (windowId, playerInv, p) -> new CorpseMenu(windowId, playerInv, this),
                            getDisplayName()),
                    buf -> buf.writeInt(this.getId()));
            level().playSound(null, blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER,
                    SoundSource.BLOCKS, 0.5f, 1.0f);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    /**
     * Fills a glass bottle with XP from this corpse, producing a filled, ready-to-use
     * {@code whatremains:xp_bottle} (XPB-06: the crafted vessel item is revived and is once again
     * the OUTPUT here — recovering your own lost XP is an earned action, not the self-serve
     * deposit gesture this round gates, so it hands out an already-filled vessel with no crafting
     * step required).
     * <p>
     * V2: {@link XpVessel#capacity()} is level-derived (55 XP at the shipped XPB-06 default of
     * level 5), so this needed no code change at all — it already asked the core accessor for the
     * live figure rather than a local constant. {@link XpVessel#setStoredXp} also now writes the
     * orb manifest alongside the scalar, so the produced bottle pays out as real orbs when thrown
     * with no extra work here. Conservation is unaffected either way: only up to {@code capacity}
     * is ever drained, and whatever the corpse held beyond that stays in it for a later bottle or
     * the corpse's own decay handling.
     */
    private InteractionResult fillBottleFromCorpse(Player player, InteractionHand hand, ItemStack bottleStack) {
        int capacity = XpVessel.capacity();
        int drained = drainXp(capacity);
        if (drained <= 0) return InteractionResult.PASS;

        bottleStack.shrink(1);

        // Count-1 by construction, and it stays that way: a partial vessel's stack limit is 1, so
        // Inventory#add's remaining-space check can never merge it onto another partial bottle.
        ItemStack filledBottle = new ItemStack(WRItems.XP_BOTTLE.get());
        XpVessel.setStoredXp(filledBottle, drained);

        if (!player.getInventory().add(filledBottle)) {
            player.drop(filledBottle, false);
        }

        level().playSound(null, blockPosition(), SoundEvents.BOTTLE_FILL,
                SoundSource.PLAYERS, 1.0f, 1.0f);
        return InteractionResult.SUCCESS;
    }

    /**
     * SNEAK + secondary-interact with a non-full {@code whatremains:xp_bottle}: vacuums stored XP
     * out of the corpse into the held bottle, mirroring {@link XpVessel#vacuumOrbsInto}'s in-world
     * orb vacuum as closely as possible — the same {@code SoundEvents.BOTTLE_FILL} cue at the same
     * pitch formula, and a real, briefly-visible {@link ExperienceOrb} spawned at the corpse rather
     * than a silent number change, so the gesture reads the same regardless of whether the XP is
     * coming from a live orb or a corpse's stored balance.
     * <p>
     * Draws at most {@code corpseVacuumXpDrainPerAction} XP (default 200) per action — also bounded
     * by however much is actually stored and by the bottle's own remaining room — so draining a
     * large balance takes several sneak-clicks by design, exactly as asked.
     * <p>
     * Always consumes the interaction once sneaking with a non-full vessel is detected, even when
     * there's nothing to drain (corpse already empty): the sneak input is a clear signal of intent
     * that must never accidentally fall through to opening the chest screen instead.
     */
    private InteractionResult vacuumXpFromCorpse(Player player, InteractionHand hand, ItemStack heldBottle) {
        int stored = getStoredXp();
        int room = XpVessel.capacity(heldBottle) - XpVessel.getStoredXp(heldBottle);
        int perAction = Math.max(0, WhatRemainsConfig.corpseVacuumXpDrainPerAction);
        int amount = Math.min(Math.min(stored, room), perAction);

        if (amount <= 0) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.18F, 1.35F);
            return InteractionResult.SUCCESS;
        }

        int drained = drainXp(amount);
        if (drained <= 0) return InteractionResult.SUCCESS;

        // Split-before-mutate, same discipline as the in-world vacuum's own deposit/vacuum paths:
        // a partial claim must never be written onto a shared (count > 1) stack.
        boolean split = heldBottle.getCount() > 1;
        ItemStack target = split ? heldBottle.copyWithCount(1) : heldBottle;
        XpVessel.setStoredXp(target, XpVessel.getStoredXp(target) + drained);
        if (split) {
            ItemStack remainder = heldBottle.copy();
            remainder.shrink(1);
            player.setItemInHand(hand, target);
            if (!player.getInventory().add(remainder)) {
                player.drop(remainder, false);
            }
        }

        // A real orb briefly appears at the corpse before being claimed — same visual/audio
        // language as the in-world orb vacuum, not a silent number change. Discarded immediately
        // after spawning so its own tick() (where vanilla's touch-pickup logic lives) never runs,
        // which is what stops vanilla from ever crediting it to the player's live XP bar instead.
        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) level();
        ExperienceOrb flashOrb = new ExperienceOrb(serverLevel, getX(), getY() + 0.5, getZ(), drained);
        serverLevel.addFreshEntity(flashOrb);
        flashOrb.discard();

        float fillAfter = XpVessel.fillFraction(target);
        level().playSound(null, blockPosition(), SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS,
                0.42F, 0.86F + 0.24F * fillAfter);
        return InteractionResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        GameProfile profile = getOwnerProfile();
        if (profile != null && profile.getName() != null) {
            return Component.translatable("entity.whatremains.corpse.named", profile.getName());
        }
        return Component.translatable("entity.whatremains.corpse");
    }

    // ── NBT save/load ───────────────────────────────────────────────────────

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        // Main inventory
        ListTag invTag = new ListTag();
        for (int i = 0; i < BASE_SLOTS; i++) {
            CompoundTag slotTag = new CompoundTag();
            if (!inventory.get(i).isEmpty()) {
                inventory.get(i).save(slotTag);
            }
            invTag.add(slotTag);
        }
        tag.put("Inventory", invTag);

        // Curios inventory
        if (!curiosItems.isEmpty()) {
            ListTag curiosTag = new ListTag();
            for (int i = 0; i < curiosItems.size(); i++) {
                CompoundTag ct = new CompoundTag();
                ct.putString("Type", i < curiosSlotTypes.size() ? curiosSlotTypes.get(i) : "");
                if (!curiosItems.get(i).isEmpty()) {
                    curiosItems.get(i).save(ct);
                }
                curiosTag.add(ct);
            }
            tag.put("CuriosInventory", curiosTag);
        }

        // Aux overflow
        if (!auxInventory.isEmpty()) {
            ListTag auxTag = new ListTag();
            for (ItemStack stack : auxInventory) {
                if (stack != null && !stack.isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    stack.save(itemTag);
                    auxTag.add(itemTag);
                }
            }
            tag.put("AuxInventory", auxTag);
        }

        tag.putInt("VacuumTimer", vacuumTimer);
        tag.putInt("ShovelHits", shovelHits);
        tag.putLong("DeathTimestamp", deathTimestamp);

        // Owner
        if (ownerProfile != null) {
            CompoundTag ownerTag = new CompoundTag();
            NbtUtils.writeGameProfile(ownerTag, ownerProfile);
            tag.put("OwnerProfile", ownerTag);
        }

        tag.putFloat("CorpseYaw", getYRot());
        tag.putByte("ModelParts", getModelCustomisation());
        tag.putByte("DeathCause", (byte) getDeathCause().ordinal());
        tag.putByte("CharLevel", this.entityData.get(DATA_CHAR_LEVEL));
        tag.putByte("FrostLevel", this.entityData.get(DATA_FROST_LEVEL));
        tag.putByte("WaterloggedLevel", this.entityData.get(DATA_WATERLOGGED_LEVEL));
        tag.putByte("ScorchLevel", this.entityData.get(DATA_SCORCH_LEVEL));
        tag.putByte("CalcifyLevel", this.entityData.get(DATA_CALCIFY_LEVEL));
        tag.putString("ScorchStyle", this.entityData.get(DATA_SCORCH_STYLE));
        tag.putString("WaterloggedStyle", this.entityData.get(DATA_WATERLOGGED_STYLE));
        tag.putInt("CorpseAge", getCorpseAgeTicks());
        tag.putInt("StoredXp", getStoredXp());
        tag.putInt("DeathLevel", deathLevel);
        tag.putInt("LockedSlotsLo", this.entityData.get(DATA_LOCKED_SLOTS_LO));
        tag.putInt("LockedSlotsHi", this.entityData.get(DATA_LOCKED_SLOTS_HI));
        tag.putInt("PreviousActiveGeneral", previousActiveGeneralCount);
        tag.putBoolean("HasAttractedFlyCrowd", hasAttractedFlyCrowd);

        // Only completed vulture effects persist. Active UUID leases and their spacing indices are
        // intentionally transient: after a reload both species must reacquire the corpse normally.
        tag.putInt("VultureConsumedFoodItems", vultureConsumedFoodItems);
        tag.putInt("VultureGoreDrops", vultureGoreDrops);

        tag.putLong("AppearanceSeed", this.entityData.get(DATA_APPEARANCE_SEED));

        if (!getDeathHeldItem().isEmpty()) {
            CompoundTag heldTag = new CompoundTag();
            getDeathHeldItem().save(heldTag);
            tag.put("DeathHeldItem", heldTag);
        }

        // Reuse the already-built ListTag straight out of the synced wrapper compound rather than
        // re-encoding from the (possibly not-yet-decoded) cached list — byte-identical to whatever
        // was last synced, at no extra cost.
        CompoundTag stuckData = this.entityData.get(DATA_STUCK_PROJECTILES);
        if (stuckData.contains(STUCK_PROJECTILES_LIST_KEY, Tag.TAG_LIST)) {
            tag.put("StuckProjectiles", stuckData.getList(STUCK_PROJECTILES_LIST_KEY, Tag.TAG_COMPOUND));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        // Main inventory
        if (tag.contains("Inventory", Tag.TAG_LIST)) {
            ListTag invTag = tag.getList("Inventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < BASE_SLOTS && i < invTag.size(); i++) {
                CompoundTag slotTag = invTag.getCompound(i);
                if (!slotTag.isEmpty()) {
                    inventory.set(i, ItemStack.of(slotTag));
                } else {
                    inventory.set(i, ItemStack.EMPTY);
                }
            }
        }

        // Curios inventory
        if (tag.contains("CuriosInventory", Tag.TAG_LIST)) {
            ListTag curiosTag = tag.getList("CuriosInventory", Tag.TAG_COMPOUND);
            int count = curiosTag.size();
            curiosItems = NonNullList.withSize(count, ItemStack.EMPTY);
            curiosSlotTypes.clear();
            for (int i = 0; i < count; i++) {
                CompoundTag ct = curiosTag.getCompound(i);
                curiosSlotTypes.add(ct.getString("Type"));
                if (ct.contains("id")) { // has item data
                    curiosItems.set(i, ItemStack.of(ct));
                }
            }
            this.entityData.set(DATA_CURIOS_COUNT, count);
            syncCuriosTypesData();
        }

        // Aux overflow
        if (tag.contains("AuxInventory", Tag.TAG_LIST)) {
            ListTag auxTag = tag.getList("AuxInventory", Tag.TAG_COMPOUND);
            auxInventory.clear();
            for (int i = 0; i < auxTag.size(); i++) {
                ItemStack stack = ItemStack.of(auxTag.getCompound(i));
                if (!stack.isEmpty()) {
                    auxInventory.addLast(stack);
                }
            }
        }

        vacuumTimer = tag.getInt("VacuumTimer");
        shovelHits = tag.getInt("ShovelHits");
        deathTimestamp = tag.getLong("DeathTimestamp");

        if (tag.contains("OwnerProfile")) {
            ownerProfile = NbtUtils.readGameProfile(tag.getCompound("OwnerProfile"));
            if (ownerProfile != null) {
                setOwnerProfile(ownerProfile);
            }
        }

        if (tag.contains("CorpseYaw")) {
            setCorpseRotation(tag.getFloat("CorpseYaw"));
        }

        if (tag.contains("ModelParts")) {
            setModelCustomisation(tag.getByte("ModelParts"));
        }
        if (tag.contains("DeathCause", Tag.TAG_STRING)) {
            setDeathCause(DeathCause.byName(tag.getString("DeathCause")));
        } else if (tag.contains("DeathCause")) {
            setDeathCause(DeathCause.byOrdinal(tag.getByte("DeathCause")));
        }
        // Tag absent -> stays at the synced default of 0, same backward-compat pattern as
        // ModelParts/DeathCause above (corpses saved before these fields existed).
        if (tag.contains("CharLevel")) {
            this.entityData.set(DATA_CHAR_LEVEL, tag.getByte("CharLevel"));
        }
        if (tag.contains("FrostLevel")) {
            this.entityData.set(DATA_FROST_LEVEL, tag.getByte("FrostLevel"));
        }
        if (tag.contains("WaterloggedLevel")) {
            this.entityData.set(DATA_WATERLOGGED_LEVEL, tag.getByte("WaterloggedLevel"));
        }
        if (tag.contains("ScorchLevel")) {
            this.entityData.set(DATA_SCORCH_LEVEL, tag.getByte("ScorchLevel"));
        }
        if (tag.contains("CalcifyLevel")) {
            this.entityData.set(DATA_CALCIFY_LEVEL, tag.getByte("CalcifyLevel"));
        }
        if (tag.contains("ScorchStyle")) {
            this.entityData.set(DATA_SCORCH_STYLE, tag.getString("ScorchStyle"));
        }
        if (tag.contains("WaterloggedStyle")) {
            this.entityData.set(DATA_WATERLOGGED_STYLE, tag.getString("WaterloggedStyle"));
        }
        if (tag.contains("HasAttractedFlyCrowd")) {
            hasAttractedFlyCrowd = tag.getBoolean("HasAttractedFlyCrowd");
        }
        if (tag.contains("CorpseAge")) {
            setCorpseAgeTicks(tag.getInt("CorpseAge"));
        }
        if (tag.contains("StoredXp")) {
            setStoredXp(tag.getInt("StoredXp"));
        }
        if (tag.contains("DeathLevel")) {
            deathLevel = tag.getInt("DeathLevel");
        }
        if (tag.contains("LockedSlotsLo")) {
            this.entityData.set(DATA_LOCKED_SLOTS_LO, tag.getInt("LockedSlotsLo"));
        }
        if (tag.contains("LockedSlotsHi")) {
            this.entityData.set(DATA_LOCKED_SLOTS_HI, tag.getInt("LockedSlotsHi"));
        }
        if (tag.contains("PreviousActiveGeneral")) {
            previousActiveGeneralCount = tag.getInt("PreviousActiveGeneral");
        } else if (tag.contains("PreviousActiveSlots")) {
            // Legacy migration
            previousActiveGeneralCount = Math.min(tag.getInt("PreviousActiveSlots"), GENERAL_SLOTS);
        }

        // Missing tags are the backward-compatible zero default. Clamp malformed negative values
        // so hand-edited NBT cannot manufacture extra consumption or gore allowance.
        vultureConsumedFoodItems = Math.max(0, tag.getInt("VultureConsumedFoodItems"));
        vultureGoreDrops = Math.max(0, tag.getInt("VultureGoreDrops"));

        // Tag absent -> stays at the synced default of 0L, which getAppearanceSeed() treats as
        // "never explicitly set" and falls back to a UUID-derived default for — same backward-compat
        // shape as CharLevel/ModelParts/etc. above (corpses saved before this field existed).
        if (tag.contains("AppearanceSeed")) {
            this.entityData.set(DATA_APPEARANCE_SEED, tag.getLong("AppearanceSeed"));
        }

        if (tag.contains("DeathHeldItem")) {
            this.entityData.set(DATA_DEATH_HELD_ITEM, ItemStack.of(tag.getCompound("DeathHeldItem")));
        }

        if (tag.contains("StuckProjectiles", Tag.TAG_LIST)) {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put(STUCK_PROJECTILES_LIST_KEY, tag.getList("StuckProjectiles", Tag.TAG_COMPOUND));
            this.entityData.set(DATA_STUCK_PROJECTILES, wrapper);
        }
    }

    // ── Equipment slots — return actual armor from inventory slots 0-3 ──────

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return List.of(
                inventory.get(3), // FEET  (index 0 in armor iteration = boots)
                inventory.get(2), // LEGS  (index 1 = leggings)
                inventory.get(1), // CHEST (index 2 = chestplate)
                inventory.get(0)  // HEAD  (index 3 = helmet)
        );
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD    -> inventory.get(0);
            case CHEST   -> inventory.get(1);
            case LEGS    -> inventory.get(2);
            case FEET    -> inventory.get(3);
            case OFFHAND -> inventory.get(OFFHAND_INDEX);
            default      -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        switch (slot) {
            case HEAD    -> inventory.set(0, stack);
            case CHEST   -> inventory.set(1, stack);
            case LEGS    -> inventory.set(2, stack);
            case FEET    -> inventory.set(3, stack);
            case OFFHAND -> inventory.set(OFFHAND_INDEX, stack);
            default      -> {}
        }
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    // ── Container interface ─────────────────────────────────────────────────

    @Override
    public int getContainerSize() {
        return BASE_SLOTS + Math.max(curiosItems.size(), getCuriosSize());
    }

    @Override
    public boolean isEmpty() {
        return isInventoryEmpty();
    }

    @Override
    public ItemStack getItem(int index) {
        if (index >= 0 && index < BASE_SLOTS) return inventory.get(index);
        int ci = index - BASE_SLOTS;
        if (ci >= 0 && ci < curiosItems.size()) return curiosItems.get(ci);
        return ItemStack.EMPTY;
    }

    /**
     * True when {@code index} is an armor slot (0-3) currently holding a Curse of Binding item and
     * the lock is enabled — refusing extraction here (not just in {@code CorpseMenu}'s {@code Slot#
     * mayPickup}) closes the hopper path: this class implements plain {@link Container}, and
     * {@code HopperBlockEntity#getContainerAt} falls back to any nearby {@code Container}-
     * implementing entity when there's no block container to pull from, which reaches straight into
     * {@link #removeItem}/{@link #removeItemNoUpdate} without ever going through a {@code Slot} or
     * this entity's GUI menu at all. Deliberately NOT consulted by {@link #dropAllItems()} — that
     * method writes directly to {@link #inventory} rather than calling either of these, which is
     * exactly why burying/decaying/collapsing the corpse still frees a bound piece.
     */
    private boolean isCorpseBoundArmorSlot(int index) {
        if (!WhatRemainsConfig.enableCurseOfBindingLocksToCorpse) return false;
        if (index < 0 || index >= ARMOR_SLOTS) return false;
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BINDING_CURSE, inventory.get(index)) > 0;
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        if (isCorpseBoundArmorSlot(index)) return ItemStack.EMPTY;

        NonNullList<ItemStack> list;
        int li;
        if (index >= 0 && index < BASE_SLOTS) { list = inventory; li = index; }
        else {
            int ci = index - BASE_SLOTS;
            if (ci < 0 || ci >= curiosItems.size()) return ItemStack.EMPTY;
            list = curiosItems; li = ci;
        }
        ItemStack stack = list.get(li);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (stack.getCount() <= count) {
            list.set(li, ItemStack.EMPTY);
            setChanged();
            return stack;
        }
        ItemStack split = stack.split(count);
        if (stack.isEmpty()) list.set(li, ItemStack.EMPTY);
        setChanged();
        return split;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        if (isCorpseBoundArmorSlot(index)) return ItemStack.EMPTY;

        if (index >= 0 && index < BASE_SLOTS) {
            ItemStack stack = inventory.get(index);
            inventory.set(index, ItemStack.EMPTY);
            return stack;
        }
        int ci = index - BASE_SLOTS;
        if (ci >= 0 && ci < curiosItems.size()) {
            ItemStack stack = curiosItems.get(ci);
            curiosItems.set(ci, ItemStack.EMPTY);
            return stack;
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index >= 0 && index < BASE_SLOTS) {
            inventory.set(index, stack);
        } else {
            int ci = index - BASE_SLOTS;
            if (ci >= 0) {
                ensureCuriosCapacity(ci + 1);
                curiosItems.set(ci, stack);
            } else {
                return;
            }
        }
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public void setChanged() {
        inventoryDirty = true;
    }

    @Override
    public void startOpen(Player player) {
        if (!level().isClientSide) viewers.add(player);
    }

    @Override
    public void stopOpen(Player player) {
        viewers.remove(player);
    }

    @Override
    public boolean stillValid(Player player) {
        if (deadMode || isRemoved()) return false;
        // Tighter than vanilla's 8-block chest reach (64.0): a corpse should require the player to
        // stay close, especially so drifting away underwater closes the GUI promptly.
        return player.distanceToSqr(this) <= 16.0;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < BASE_SLOTS; i++) {
            inventory.set(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < curiosItems.size(); i++) {
            curiosItems.set(i, ItemStack.EMPTY);
        }
        auxInventory.clear();
        setChanged();
    }

    // ── Sounds ──────────────────────────────────────────────────────────────

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SKELETON_HURT;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() {
        return SoundEvents.SKELETON_DEATH;
    }

    @Override
    public int getAmbientSoundInterval() {
        return Integer.MAX_VALUE;
    }
}
