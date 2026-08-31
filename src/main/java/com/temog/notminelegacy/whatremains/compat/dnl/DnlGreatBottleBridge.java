package com.temog.notminelegacy.whatremains.compat.dnl;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/**
 * DungeonNowLoading-specific Great Bottle gesture orchestration, reflective projectile creation,
 * and throw-manifest resolution. Shared storage, capacity, stackability, deposit, and vacuum
 * behaviour all live in {@link XpVessel}; this class supplies only the DNL interaction flow that
 * has no normal Bottle o' Enchanting equivalent.
 *
 * <p>The bridge is active only while DungeonNowLoading itself is present and the independently
 * configurable Great Bottle feature is enabled. DNL remains reflection-only: no DNL-owned class
 * appears in this module's compile-time type surface.
 */
public final class DnlGreatBottleBridge {

    public static final String DNL_MODID = "dungeonnowloading";

    /** Largest legal single-orb value, from vanilla's own orb tier ladder. */
    private static final int MAX_ORB_VALUE = XpVessel.ORB_LADDER[XpVessel.ORB_LADDER.length - 1];

    /** Shared empty manifest for zero-payout creative throws. */
    private static final int[] NO_CHUNKS = new int[0];

    private DnlGreatBottleBridge() {}

    // ── Gating ──────────────────────────────────────────────────────────────

    /**
     * True only when DNL is loaded, the SERVER spec has baked, and the Great Bottle master switch
     * is enabled. The explicit pre-bake guard preserves the original fail-closed early-query
     * behaviour used by the item mixin and client gesture handler.
     */
    public static boolean isActive() {
        if (!GreatBottleConfig.SPEC.isLoaded()) return false;
        return ModList.get().isLoaded(DNL_MODID) && GreatBottleConfig.greatBottleEnabled;
    }

    // ── Gesture orchestration ───────────────────────────────────────────────

    /** @return true if the gesture was accepted (drives the packet handler's rate-limit stamp) */
    public static boolean handleDeposit(ServerPlayer player, InteractionHand hand, int slot) {
        if (!isActive()) return false;
        ItemStack held = player.getItemInHand(hand);
        if (!XpVessel.isGreatBottle(held)) return false;
        if (slot != liveSlot(player, hand)) return false;

        if (XpVessel.isFull(held)) {
            reject(player);
            return false;
        }

        if (!GreatBottleConfig.greatBottleAllowDeposit) {
            reject(player);
            return false;
        }

        boolean creative = player.getAbilities().instabuild;
        if (!creative && XpVessel.playerTotalXp(player) <= 0L) {
            reject(player);
            return false;
        }

        boolean split = held.getCount() > 1;
        ItemStack target = split ? held.copyWithCount(1) : held;

        int moved = XpVessel.depositIntoBottle(
                player, target, GreatBottleConfig.greatBottleStoreUntilPreviousLevel);
        if (moved <= 0) {
            reject(player);
            return false;
        }

        if (split) {
            ItemStack remainder = held.copy();
            remainder.shrink(1);
            player.setItemInHand(hand, target);
            giveOrDrop(player, remainder);
        }

        ServerLevel level = player.serverLevel();
        float fAfter = XpVessel.fillFraction(target);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.42F, 0.86F + 0.24F * fAfter);
        return true;
    }

    /**
     * Captures nearby XP orbs into a Great Bottle. The client probes first, but every condition is
     * re-derived from authoritative server state here.
     *
     * @return true if the gesture was accepted (drives the packet handler's rate-limit stamp)
     */
    public static boolean handleVacuum(ServerPlayer player, InteractionHand hand, int slot) {
        if (!isActive()) return false;
        ItemStack held = player.getItemInHand(hand);
        if (!XpVessel.isGreatBottle(held)) return false;
        if (slot != liveSlot(player, hand)) return false;
        if (XpVessel.isFull(held)) return false;

        if (!GreatBottleConfig.greatBottleAllowOrbVacuum) {
            reject(player);
            return false;
        }

        boolean split = held.getCount() > 1;
        ItemStack target = split ? held.copyWithCount(1) : held;

        int captured = XpVessel.vacuumOrbsInto(player, target);
        if (captured <= 0) {
            reject(player);
            return false;
        }

        if (split) {
            ItemStack remainder = held.copy();
            remainder.shrink(1);
            player.setItemInHand(hand, target);
            giveOrDrop(player, remainder);
        }

        ServerLevel level = player.serverLevel();
        float fAfter = XpVessel.fillFraction(target);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.BOTTLE_FILL, SoundSource.PLAYERS, 0.42F, 0.86F + 0.24F * fAfter);
        return true;
    }

    /** @return true if the gesture was accepted (drives the packet handler's rate-limit stamp) */
    public static boolean handleThrow(ServerPlayer player, InteractionHand hand, int slot) {
        if (!isActive()) return false;
        ItemStack held = player.getItemInHand(hand);
        if (!XpVessel.isGreatBottle(held)) return false;
        if (slot != liveSlot(player, hand)) return false;
        return throwGreatBottle(player, hand, held);
    }

    private static void reject(ServerPlayer player) {
        player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.18F, 1.35F);
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().add(stack);
        if (stack.isEmpty()) return;
        ItemEntity dropped = player.drop(stack, false);
        if (dropped != null) {
            dropped.setNoPickUpDelay();
            dropped.setTarget(player.getUUID());
        }
    }

    private static int liveSlot(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
    }

    // ── Throw resolution ────────────────────────────────────────────────────

    /**
     * Rolls this bridge's independently configured Great Drop payout: a uniform inclusive roll
     * from {@code unresolvedThrowRollMin} to {@code unresolvedThrowRollMax} (default 60..220 XP,
     * XPB-04 — a decuple of the normal bottle's own doubled 6..22 raw-XP default).
     */
    private static int unresolvedThrowXp(RandomSource random) {
        return XpVessel.rollGreatBottleUnresolvedThrowXp(random);
    }

    /**
     * Resolves the orb manifest a thrown Great Bottle pays. A valid stored claim keeps its exact
     * recorded manifest; an unresolved bottle or a disabled stored-payout gate receives a fresh
     * Great Drop partition. Creative zero-payout remains a separate explicit policy.
     * <p>
     * XPB-07: a partial stack with count &gt; 1 is no longer treated as illegal/stripped — see
     * {@link XpVessel#maxStackFor} and {@code XpBottleNormalizer}'s class javadoc. Every unit in a
     * legally-sized stack honestly cost being deposited/vacuumed/found to its exact value, so it
     * pays that real value when thrown, the same as a full stack already did.
     */
    private static int[] resolveThrowManifest(ServerPlayer player, ItemStack heldStack, boolean creative) {
        RandomSource random = player.getRandom();
        if (creative && GreatBottleConfig.greatBottleCreativeThrowPaysZero) return NO_CHUNKS;

        if (!GreatBottleConfig.greatBottleThrowPaysStoredXp) {
            return XpVessel.partitionIntoOrbs(unresolvedThrowXp(random), random);
        }

        if (!XpVessel.hasStoredXpTag(heldStack)) {
            return XpVessel.partitionIntoOrbs(unresolvedThrowXp(random), random);
        }

        return XpVessel.getOrbManifest(heldStack);
    }

    /**
     * Creates DNL's projectile reflectively, writes the already-resolved manifest onto its carried
     * item copy, and reproduces DNL's native throw envelope.
     */
    private static boolean throwGreatBottle(ServerPlayer player, InteractionHand hand, ItemStack held) {
        ServerLevel level = player.serverLevel();
        boolean creative = player.getAbilities().instabuild;
        int[] chunks = resolveThrowManifest(player, held, creative);
        int payout = sumOf(chunks);

        Entity spawned = DnlReflection.createThrown(level, player);
        if (spawned == null) return false;

        ItemStack projectileStack = held.copyWithCount(1);
        XpVessel.setOrbManifest(projectileStack, chunks);
        if (spawned instanceof ThrowableItemProjectile throwable) {
            throwable.setItem(projectileStack);
        }
        if (spawned instanceof Projectile projectile) {
            projectile.shootFromRotation(player, player.getXRot(), player.getYRot(), -20.0F, 0.7F, 1.0F);
        }
        DnlReflection.setExperienceAmount(spawned, payout);
        level.addFreshEntity(spawned);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.EXPERIENCE_BOTTLE_THROW, SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        player.awardStat(Stats.ITEM_USED.get(held.getItem()));
        if (!creative) held.shrink(1);
        return true;
    }

    // ── Position-based impact payout ────────────────────────────────────────

    /**
     * Spawns one real {@link ExperienceOrb} per chunk at an arbitrary projectile impact position.
     * This is intentionally separate from XpVessel's player-centred gesture operations.
     *
     * @return true when at least one orb entered the world
     */
    public static boolean spawnOrbsAt(ServerLevel level, Vec3 position, int[] chunks) {
        if (chunks == null || chunks.length == 0) return false;

        boolean spawned = false;
        int count = 0;
        for (int value : chunks) {
            if (count >= XpVessel.MAX_CHUNKS) break;
            if (value <= 0) continue;
            count++;

            ExperienceOrb orb = new ExperienceOrb(level, position.x, position.y, position.z,
                    Math.min(value, MAX_ORB_VALUE));
            if (level.addFreshEntity(orb)) spawned = true;
        }
        return spawned;
    }

    /** Sum of an already-normalised manifest. */
    private static int sumOf(int[] chunks) {
        if (chunks == null) return 0;
        long sum = 0L;
        for (int value : chunks) {
            if (value > 0) sum += value;
        }
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
