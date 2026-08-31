package com.temog.notminelegacy.whatremains.mixin.compat.dnl;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.compat.dnl.DnlGreatBottleBridge;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two independent repairs/features on DungeonNowLoading's {@code GreatExperienceBottleEntity}
 * (LB-03 spec §6 / V2 plan M2), both {@code @Pseudo} target by source string (DNL isn't a
 * compile-time dependency), {@code remap = false} everywhere.
 *
 * <h2>1. Reload-bug fix (recon finding, independent of the V2 payout below)</h2>
 * {@code readAdditionalSaveData} reads a MISSING {@code "StoredExperience"} tag as 0
 * ({@code CompoundTag#getInt}'s absent-key default) instead of leaving the entity's just-synced
 * 100 stock default ({@code defineSynchedData}) alone. Left unfixed, a bottle entity reloaded from
 * NBT lacking the key (a bare {@code /summon}, or any save predating the field) silently becomes a
 * dud 0-XP bottle instead of paying DNL's stock yield. A correctness repair, not a WhatRemains
 * design decision — exposed as {@link GreatBottleConfig#FIX_DNL_RELOAD_BUG} purely in case a future
 * DNL release fixes it upstream (at which point it can be turned off to avoid a redundant, harmless
 * double-repair). <b>Independent of {@link GreatBottleConfig#greatBottleEnabled}</b> — the master switch governs
 * Great Bottle features; this is a bug fix, so it stays active even with the bridge otherwise
 * switched off. Uses a TAIL inject + a restore, deliberately NOT a {@code @Redirect} on the
 * vanilla {@code CompoundTag#getInt} call inside the target method — that would require reasoning
 * about remap on an {@code @At(target=...)} bytecode descriptor pointing at a vanilla method
 * invoked from within a mod-owned override, which is exactly the ambiguity CLAUDE.md's Mixin
 * pitfalls section warns about. A TAIL inject that simply restores the default when the key was
 * absent sidesteps that entirely.
 *
 * <h2>2. V2 orb-chunk payout (the LEAST invasive interception per the V2 plan critique M2)</h2>
 * A HEAD inject on {@code onHit} reads the orb manifest off the entity's carried
 * {@code ItemStack} (the vanilla {@code ThrowableItemProjectile} superclass already saves/syncs
 * that stack — no reflection needed to get it here, unlike the throw-time payout resolution),
 * spawns our own real {@link net.minecraft.world.entity.ExperienceOrb} chunks at the impact point,
 * and — only once a chunk actually landed — zeroes DNL's own synced amount via the shadowed
 * {@link #setExperienceAmount(int)} so its native {@code ExperienceOrb.award(level, pos, 0)} call
 * becomes a genuine no-op (the loop inside {@code ExperienceOrb#award} never executes for a
 * non-positive amount). This inject deliberately does <b>not cancel</b>: DNL's own {@code onHit}
 * still runs {@code super.onHit(...)} (block/entity hit dispatch), its level-event-2002 shatter FX,
 * and its own {@code discard()} — only the actual XP delivery is substituted. An empty/absent
 * manifest (an "unresolved" bottle that was never given a claim) is left alone entirely: no orbs
 * spawn here and the native amount is not zeroed, so DNL's stock (or whatever the throw-time
 * resolution already set) pays out exactly as it always has.
 */
@Pseudo
@Mixin(targets = "dev.hexnowloading.dungeonnowloading.entity.misc.GreatExperienceBottleEntity", remap = false)
public abstract class DnlGreatBottleEntityMixin {

    /** DNL's own stock yield — see {@code DNLItems.GREAT_EXPERIENCE_BOTTLE}'s constructor arg
     *  ({@code new GreatExperienceBottleItem(..., 100)}) and {@code defineSynchedData}'s own
     *  {@code entityData.define(EXPERIENCE_AMOUNT, 100)} default. */
    @Unique
    private static final int WHATREMAINS$DNL_STOCK_XP = 100;

    /** Mod-owned member — {@code remap = false} per CLAUDE.md's Mixin pitfalls section. Shadowed
     *  once, reused by both the reload-bug restore and the V2 payout zero-out below. */
    @Shadow(remap = false)
    public abstract void setExperienceAmount(int experienceAmount);

    // Production stores vanilla-overridden members under SRG names (javap-verified against the
    // pinned 2.2 jar); dev runtime-deobf presents official names. The two-name selector matches
    // whichever exists in the running environment — the injector's require is satisfied by either.
    @Inject(method = {"readAdditionalSaveData", "m_7378_"}, at = @At("TAIL"), remap = false)
    private void whatremains$restoreMissingStockDefault(CompoundTag compoundTag, CallbackInfo ci) {
        // Deliberately NOT gated on greatBottleEnabled — see class javadoc: this is a bug
        // repair, independent of the feature master switch.
        if (!GreatBottleConfig.greatBottleFixDnlReloadBug) return;
        if (!compoundTag.contains("StoredExperience")) {
            this.setExperienceAmount(WHATREMAINS$DNL_STOCK_XP);
        }
    }

    /**
     * {@code onHit} is declared directly on {@code GreatExperienceBottleEntity} itself (it
     * overrides {@code Projectile#onHit}, javap-verified against the pinned 2.2 jar — not merely
     * inherited, which would fail per CLAUDE.md's "@Inject into inherited methods" pitfall), and,
     * like {@code readAdditionalSaveData} above, is a VANILLA-OVERRIDDEN method on a
     * {@code @Pseudo} mod class: production stores it under its SRG name
     * ({@code m_6532_}, confirmed by the same javap check), dev runtime-deobf presents the source
     * name. The two-name selector matches whichever exists in the running environment.
     * <p>
     * {@code this} cannot be self-cast to the target class ({@code @Pseudo} — no compile-time type
     * exists for it), so it is cast instead to {@code ThrowableItemProjectile}, the nearest
     * vanilla ancestor that actually declares the public members needed here ({@code level()},
     * {@code getItem()}, {@code position()} — all real, ordinary Java calls handled by this
     * project's normal reobf pipeline, not Mixin selectors, so they need no remap gymnastics of
     * their own; see the class javadoc's "self-cast" pitfall entry for why an ancestor cast is the
     * correct move here instead of a doomed same-type cast).
     */
    @Inject(method = {"onHit", "m_6532_"}, at = @At("HEAD"), remap = false)
    private void whatremains$payoutOrbManifest(HitResult hitResult, CallbackInfo ci) {
        // Master-off must restore EXACTLY vanilla — do not even look at the carried stack.
        if (!DnlGreatBottleBridge.isActive()) return;

        ThrowableItemProjectile self = (ThrowableItemProjectile) (Object) this;
        // Mirrors DNL's own onHit guard (javap: instanceof ServerLevel before anything observable)
        // — this inject must stay a pure no-op on the client for the exact same reason.
        if (!(self.level() instanceof ServerLevel serverLevel)) return;

        ItemStack carried = self.getItem();
        int[] chunks = XpVessel.getOrbManifest(carried);
        if (chunks == null || chunks.length == 0) return; // unresolved — let DNL's own amount pay

        if (DnlGreatBottleBridge.spawnOrbsAt(serverLevel, self.position(), chunks)) {
            // Only once real orbs actually entered the world: zero DNL's synced amount so its own
            // ExperienceOrb.award(level, pos, 0) call (further down in the un-cancelled native
            // method) is a genuine no-op rather than a double payout.
            this.setExperienceAmount(0);
        }
    }
}
