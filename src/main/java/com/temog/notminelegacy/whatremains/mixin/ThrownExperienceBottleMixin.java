package com.temog.notminelegacy.whatremains.mixin;

import com.temog.notminelegacy.xp.XpMath;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.events.XpBottlePayoutReconciler;
import com.temog.notminelegacy.whatremains.network.ServerboundXpBottleGesture;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single point where a thrown bottle's XP payout is decided (LB-03 plan D3/D8/D10).
 *
 * <p>Vanilla rolls {@code 3 + rand(5) + rand(5)} at impact and never looks at the stack it is
 * carrying, so a filled bottle's stored value would simply be discarded — on every throw path, not
 * just the dispenser's. Resolving here instead of at the throw covers the player, the dispenser and
 * any third-party thrower with one piece of code, and it is the only place that receives a stack
 * already narrowed to a single bottle.
 *
 * <p><b>V2 (LB-03):</b> the payout is no longer a single lump sum. Every resolved path below
 * returns the bottle's <em>manifest</em> — a list of legal single-orb values drawn from
 * {@link XpVessel#ORB_LADDER} — and the impact spawns one {@link ExperienceOrb} per chunk instead
 * of calling {@link ExperienceOrb#award}, so a huge stored value can never collapse into one
 * Mending-amplifying mega-orb (plan D8).
 *
 * <p>Three carried states, deliberately distinct:
 * <ul>
 *   <li><b>{@code Resolved:1b}</b> — the payout was decided by the thrower. Pays exactly the
 *       carried orb manifest ({@link XpVessel#getOrbManifest}), including empty: a creative throw
 *       shatters and drops nothing (plan D9).</li>
 *   <li><b>A stored claim without the marker</b> — a filled bottle from any other source. Pays the
 *       carried manifest, same as above.</li>
 *   <li><b>No claim at all</b> — an unresolved bottle. Rolls uniform 6..22 and partitions the roll
 *       over the orb ladder ({@link XpVessel#partitionIntoOrbs}), deliberately replacing vanilla's
 *       triangular 3..11 so a found bottle and a deliberately emptied one behave the same way.</li>
 * </ul>
 */
@Mixin(ThrownExperienceBottle.class)
public abstract class ThrownExperienceBottleMixin {

    /** Full normal-bottle capacity, the reference point for the impact burst's energy scale. */
    @Unique
    private static final double WHATREMAINS$BURST_REFERENCE_XP = 1024.0D;

    /** The burst is capped so an oversized vessel cannot flood the particle limiter. */
    @Unique
    private static final double WHATREMAINS$BURST_CAP = 2.0D;

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void whatremains$resolveStoredXp(HitResult hitResult, CallbackInfo ci) {
        // Master switch off: do NOT cancel. Vanilla's own onHit (3 + rand(5) + rand(5), no regard
        // for any carried stack) must run end to end, untouched.
        if (!XpVessel.enabled()) return;

        ThrownExperienceBottle self = (ThrownExperienceBottle) (Object) this;
        Level level = self.level();

        // Cancelling at HEAD skips vanilla's own super.onHit call, so the block/entity half of it is
        // reproduced here. Losing it would silently stop bottles triggering target blocks and the
        // PROJECTILE_LAND game event, on both sides.
        whatremains$dispatchHit(self, level, hitResult);

        if (level instanceof ServerLevel serverLevel) {
            int[] manifest = whatremains$resolveXp(self.getItem(), level.getRandom());
            int released = whatremains$sumOf(manifest);

            level.levelEvent(2002, self.blockPosition(), PotionUtils.getColor(Potions.WATER));
            if (released > 0) {
                whatremains$spawnManifest(serverLevel, self.position(), manifest);
                whatremains$impactBurst(serverLevel, self.position(), released);
            }
            self.discard();
        }

        ci.cancel();
    }

    /**
     * Reads the payout off the carried stack as an orb manifest (V2) rather than a bare total, so
     * the impact can spawn one {@link ExperienceOrb} per chunk. Every manifest returned here already
     * came through {@link XpVessel#getOrbManifest} or {@link XpVessel#partitionIntoOrbs}, both of
     * which normalise unconditionally, so no further clamping is needed at this call site even
     * though the backing tag is reachable through {@code /data} (exploit E-08).
     * <p>
     * {@code throwPaysStoredXp} false: every throw pays a fresh {@link XpVessel#rollUnresolvedThrowXp}
     * partition instead — a stored claim, resolved or not, is never paid out by a throw at all.
     */
    @Unique
    private static int[] whatremains$resolveXp(ItemStack carried, RandomSource random) {
        if (!WhatRemainsConfig.xpBottleThrowPaysStoredXp) {
            return XpMath.partitionIntoOrbs(XpVessel.rollUnresolvedThrowXp(random), random);
        }

        CompoundTag tag = carried.getTag();
        if (tag == null) {
            return XpMath.partitionIntoOrbs(XpVessel.rollUnresolvedThrowXp(random), random);
        }
        if (tag.getBoolean(ServerboundXpBottleGesture.TAG_RESOLVED)) return XpVessel.getOrbManifest(carried);
        // A tag that carries no claim at all (a renamed bottle, say) is still unresolved — treating
        // it as a zero claim would quietly destroy a found bottle's value. Both keys must be absent:
        // either one alone is still evidence of a real claim (a legacy/forged stack could in theory
        // carry StoredOrbs without StoredXp, or vice versa).
        if (!tag.contains(XpVessel.TAG_STORED) && !tag.contains(XpVessel.TAG_ORBS)) {
            return XpMath.partitionIntoOrbs(XpVessel.rollUnresolvedThrowXp(random), random);
        }
        return XpVessel.getOrbManifest(carried);
    }

    /**
     * Spawns one real {@link ExperienceOrb} per manifest chunk at the impact point (V2), instead of
     * one lump sum via {@link ExperienceOrb#award}. Every manifest reaching here is already
     * normalised by its producer ({@link XpVessel#getOrbManifest} / {@link XpVessel#partitionIntoOrbs}),
     * the same guarantee every payout path relies on, so this loop trusts it
     * rather than re-clamping.
     */
    @Unique
    private static void whatremains$spawnManifest(ServerLevel level, Vec3 at, int[] chunks) {
        for (int value : chunks) {
            if (value <= 0) continue;
            ExperienceOrb orb = new ExperienceOrb(level, at.x, at.y, at.z, value);
            orb.getPersistentData().putBoolean(XpBottlePayoutReconciler.PAYOUT_ORB_MARKER, true);
            level.addFreshEntity(orb);
        }
    }

    /** Sum of a manifest, saturating — mirrors {@code XpVessel}'s private helper of the same shape. */
    @Unique
    private static int whatremains$sumOf(int[] chunks) {
        long sum = 0L;
        for (int value : chunks) {
            if (value > 0) sum += value;
        }
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    /** Faithful stand-in for {@code Projectile#onHit}, which the HEAD cancel would otherwise skip. */
    @Unique
    private static void whatremains$dispatchHit(ThrownExperienceBottle self, Level level, HitResult hitResult) {
        HitResult.Type type = hitResult.getType();
        if (type == HitResult.Type.ENTITY) {
            // Projectile#onHitEntity is empty all the way down for a thrown bottle, so the game
            // event is the only observable half.
            level.gameEvent(GameEvent.PROJECTILE_LAND, hitResult.getLocation(),
                    GameEvent.Context.of(self, (BlockState) null));
        } else if (type == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = level.getBlockState(pos);
            state.onProjectileHit(level, state, blockHit, self);
            // Re-read deliberately: vanilla's own Projectile#onHit re-fetches here too, because
            // onProjectileHit can mutate the block's state (e.g. TargetBlock) before this context
            // is built.
            level.gameEvent(GameEvent.PROJECTILE_LAND, pos, GameEvent.Context.of(self, level.getBlockState(pos)));
        }
    }

    /**
     * Layers a capped energy burst over level event 2002's fixed 108-particle water splash, so the
     * shatter reads as "how much XP came out" without multiplying sounds or re-firing the event.
     */
    @Unique
    private static void whatremains$impactBurst(ServerLevel level, Vec3 at, int releasedXp) {
        double q = Math.min(WHATREMAINS$BURST_CAP, Math.sqrt(releasedXp / WHATREMAINS$BURST_REFERENCE_XP));
        if (q <= 0.0D) return;
        RandomSource random = level.getRandom();

        int shards = Mth.ceil(18.0D * q);
        for (int i = 0; i < shards; i++) {
            Vec3 origin = at.add(whatremains$insideSphere(random, 0.08D));
            Vec3 direction = whatremains$upperHemisphere(random);
            Vec3 velocity = direction.scale(0.24D + 0.08D * q).add(0.0D, 0.08D, 0.0D);
            whatremains$sendDirected(level, ParticleTypes.ENCHANTED_HIT, origin, velocity);
        }

        double lateral = 0.05D + 0.025D * q;
        int sparks = Mth.ceil(12.0D * q);
        for (int i = 0; i < sparks; i++) {
            Vec3 origin = at.add(
                    whatremains$spread(random, 0.20D),
                    0.12D + whatremains$spread(random, 0.12D),
                    whatremains$spread(random, 0.20D));
            Vec3 velocity = new Vec3(
                    whatremains$spread(random, lateral),
                    0.05D + random.nextDouble() * (0.05D + 0.03D * q),
                    whatremains$spread(random, lateral));
            whatremains$sendDirected(level, ParticleTypes.HAPPY_VILLAGER, origin, velocity);
        }
    }

    /** One packet per particle with {@code count = 0}, so the outward vectors are not Gaussianised. */
    @Unique
    private static void whatremains$sendDirected(ServerLevel level, ParticleOptions particle, Vec3 at, Vec3 velocity) {
        level.sendParticles(particle, at.x, at.y, at.z, 0, velocity.x, velocity.y, velocity.z, 1.0D);
    }

    @Unique
    private static Vec3 whatremains$insideSphere(RandomSource random, double radius) {
        double x;
        double y;
        double z;
        do {
            x = random.nextDouble() * 2.0D - 1.0D;
            y = random.nextDouble() * 2.0D - 1.0D;
            z = random.nextDouble() * 2.0D - 1.0D;
        } while (x * x + y * y + z * z > 1.0D);
        return new Vec3(x * radius, y * radius, z * radius);
    }

    @Unique
    private static Vec3 whatremains$upperHemisphere(RandomSource random) {
        double y = random.nextDouble();
        double planar = Math.sqrt(Math.max(0.0D, 1.0D - y * y));
        double angle = random.nextDouble() * (Math.PI * 2.0D);
        return new Vec3(Math.cos(angle) * planar, y, Math.sin(angle) * planar);
    }

    @Unique
    private static double whatremains$spread(RandomSource random, double halfExtent) {
        return (random.nextDouble() * 2.0D - 1.0D) * halfExtent;
    }
}
