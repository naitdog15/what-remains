package com.temog.notminelegacy.whatremains.events;

import com.temog.notminelegacy.xp.XpMath;
import java.util.ArrayList;
import java.util.List;

import com.temog.notminelegacy.compat.ModCompat;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.compat.WRCuriosCompat;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.DeathCause;
import com.temog.notminelegacy.whatremains.entity.StuckProjectile;
import com.temog.notminelegacy.whatremains.registry.WREntities;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;



/**
 * Listens for player death events and spawns a {@link CorpseEntity}
 * containing the dying player's inventory.
 */
@Mod.EventBusSubscriber(modid = "whatremains", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerDeathHandler {

    private PlayerDeathHandler() {}

    /**
     * Deliberately NOT {@link EventPriority#HIGHEST}. Death-prevention/item-preservation mods
     * (verified against Twilight Forest's Charm of Life / Charm of Keeping, {@code
     * twilightforest.events.CharmEvents}, both registered at {@code HIGHEST}) directly read-and-
     * clear this SAME live {@code player.getInventory()} at this SAME event. Both sides mutate one
     * live mutable list synchronously and in-thread, so whichever listener runs first claims a
     * slot and the other simply never sees it -- no duplication risk either way -- but if this ran
     * first it would empty the inventory before Twilight Forest's own item lookup got a chance to
     * find its charm, silently breaking Charm of Life's death-cancel and Charm of Keeping's kept-
     * item selection. Running at {@code LOW} guarantees any mod using the conventional "grab
     * first" {@code HIGHEST}/{@code HIGH}/{@code NORMAL} tiers gets first refusal: if it cancels
     * the event outright (Charm of Life), Forge skips this method entirely since it doesn't set
     * {@code receiveCanceled}; if it only claims specific slots (Charm of Keeping), this method
     * only ever sees whatever is left over -- which is exactly what should end up in the corpse.
     * Corpse creation has no reason to run early: vanilla's own drop logic happens strictly after
     * the entire event's listener dispatch completes, not gated by any one listener's priority.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Level level = player.level();
        if (level.isClientSide) return;

        // Respect keepInventory gamerule
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;

        // Drop any stash left over from an earlier death before this death's keep decisions start
        // writing to it -- see ItemRetentionHandler's class javadoc on why that matters.
        ItemRetentionHandler.clearStash(player);

        float rotation = player.getYHeadRot();

        // Snapshot the main-hand stack for the corpse's render-only "still clutching this" visual,
        // captured up-front because the main-inventory vacuum loop below (which the REAL stack
        // travels through into corpse inventory, same as always) clears the backing array -- reading
        // after that loop would see ItemStack.EMPTY regardless of what was actually held.
        ItemStack deathHeldItemSnapshot = player.getMainHandItem().copy();

        CorpseEntity corpse = WREntities.CORPSE.get().create(level);
        if (corpse == null) return;

        corpse.moveTo(player.getX(), player.getY(), player.getZ(), rotation, 0.0f);
        corpse.setDeathTimestamp(level.getGameTime());

        // Transfer armor directly into dedicated armor slots (0=head, 1=chest, 2=legs, 3=feet)
        EquipmentSlot[] armorOrder = {
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET,
        };
        for (int i = 0; i < armorOrder.length; i++) {
            ItemStack armor = player.getItemBySlot(armorOrder[i]);
            if (armor.isEmpty()) continue;

            // Armor never takes itemDamageOnDeath on the normal corpse path either (only
            // vacuumItem'd general-inventory stacks do), so a KEEP outcome applies no extra
            // damage here -- parity means "whatever the corpse path would have done", not
            // "always damage it".
            switch (resolveDeathOutcome(armor, level.random)) {
                case DESTROY -> player.setItemSlot(armorOrder[i], ItemStack.EMPTY);
                case KEEP -> {
                    ItemRetentionHandler.stash(player, armor.copy(), armorOrder[i]);
                    player.setItemSlot(armorOrder[i], ItemStack.EMPTY);
                }
                case CORPSE -> {
                    corpse.setItem(i, armor.copy());
                    player.setItemSlot(armorOrder[i], ItemStack.EMPTY);
                }
            }
        }

        // Transfer curios/accessories into dynamic curios slots
        // (Aether accessories use Curios as backend, so collectAllSlots captures both)
        if (ModCompat.isCuriosLoaded()) {
            var curiosItems = WRCuriosCompat.collectAllSlots(player);
            int curiosCount = curiosItems.size();
            if (curiosCount > 0) {
                corpse.initCuriosSlots(curiosCount);
                for (int i = 0; i < curiosCount; i++) {
                    var entry = curiosItems.get(i);
                    corpse.setCuriosSlotType(i, entry.slotType());
                    if (!entry.item().isEmpty()) {
                        corpse.setCuriosItem(i, entry.item());
                    }
                }
            }
            // Clear curios inventory so Curios' own death handler has nothing to drop
            WRCuriosCompat.clearCurios(player);
        }

        // Transfer offhand into dedicated offhand slot
        for (int i = 0; i < player.getInventory().offhand.size(); i++) {
            ItemStack offhand = player.getInventory().offhand.get(i);
            if (offhand.isEmpty()) continue;

            // Same no-extra-damage parity as the armor loop above -- the offhand slot is never
            // run through applyItemDamage on the normal corpse path either.
            switch (resolveDeathOutcome(offhand, level.random)) {
                case DESTROY -> player.getInventory().offhand.set(i, ItemStack.EMPTY);
                case KEEP -> {
                    ItemRetentionHandler.stash(player, offhand.copy(), EquipmentSlot.OFFHAND);
                    player.getInventory().offhand.set(i, ItemStack.EMPTY);
                }
                case CORPSE -> {
                    corpse.setItem(CorpseEntity.OFFHAND_INDEX, offhand.copy());
                    player.getInventory().offhand.set(i, ItemStack.EMPTY);
                }
            }
        }

        // Transfer main inventory (includes hotbar)
        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack item = player.getInventory().items.get(i);
            if (item.isEmpty()) continue;

            // General-inventory stacks DO take itemDamageOnDeath on the normal corpse path
            // (inside CorpseEntity#vacuumItem), so a KEEP outcome gets that exact same call
            // applied explicitly -- keeping an item saves it from the corpse, never from its
            // durability penalty. No armor-slot equivalent (see the two loops above); a null slot
            // marks this as a plain inventory-add on restore rather than an equip.
            switch (resolveDeathOutcome(item, level.random)) {
                case DESTROY -> player.getInventory().items.set(i, ItemStack.EMPTY);
                case KEEP -> {
                    ItemStack kept = CorpseEntity.applyItemDamage(item.copy());
                    ItemRetentionHandler.stash(player, kept, null);
                    player.getInventory().items.set(i, ItemStack.EMPTY);
                }
                case CORPSE -> {
                    corpse.vacuumItem(item.copy());
                    player.getInventory().items.set(i, ItemStack.EMPTY);
                }
            }
        }

        // Add trophy items (bones & rotten flesh) if configured
        if (WhatRemainsConfig.addBonesToCorpse) {
            corpse.vacuumItem(new ItemStack(Items.ROTTEN_FLESH, level.random.nextInt(3) + 1));
            corpse.vacuumItem(new ItemStack(Items.BONE, level.random.nextInt(3) + 1));
        }

        level.addFreshEntity(corpse);
        corpse.setOwnerProfile(player.getGameProfile());
        corpse.setCorpseRotation(rotation);

        // Render-only snapshot -- never redirect the real MAINHAND slot to this, never add it to
        // the container, and never touch the real stack's normal path into corpse inventory (that
        // already happened above, via the vacuum loop). See CorpseEntity#getDeathHeldItem's javadoc.
        corpse.setDeathHeldItem(deathHeldItemSnapshot);

        // Per-corpse appearance seed -- drives client-side procedural variety (flesh mask holes,
        // blotches, etc.). Rolled fresh here so distinct deaths never accidentally share a look;
        // when never explicitly set (a hand-authored /summon, or a pre-existing save) CorpseEntity's
        // own getter falls back to a UUID-derived default instead.
        corpse.setAppearanceSeed(level.random.nextLong());

        // Stuck projectiles -- carried-over arrows (getArrowCount() is a COUNT only; vanilla stores
        // no per-arrow placement data) plus, if the killing blow itself was a projectile, one extra
        // entry for it. All placement randomness is drawn from a SplitMix64 stream seeded off the
        // corpse's own appearance seed (just rolled above) -- NOT level.random -- so every client
        // renders an identical layout and it survives a relog with no extra persisted state.
        corpse.setStuckProjectiles(captureStuckProjectiles(player, event, corpse.getAppearanceSeed()));

        // XPB-08: roll how much of the player's total XP survives into the corpse -- harsher AND
        // less lucky the higher their level (XpVessel#rollDeathRecovery), nudged by their vanilla
        // luck attribute at the moment of death. Whatever doesn't survive is destroyed outright,
        // never written anywhere. The player's own live XP is untouched by this call either way --
        // vanilla already zeroes it on death whenever keepInventory is false (the early return above).
        long totalXp = XpMath.playerTotalXp(player);
        int recovered = XpVessel.rollDeathRecovery(totalXp, player.experienceLevel, player.getLuck(), player.getRandom());
        corpse.setStoredXp(recovered);
        // Persisted so a later corpse-destruction payout roll (XpVessel#rollCorpseDestructionRecovery)
        // can reuse the same level-curve position as this death roll did.
        corpse.setDeathLevel(player.experienceLevel);

        // Capture player's skin customisation flags (jacket, sleeves, pants, hat, cape)
        byte modelParts = 0;
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (player.isModelPartShown(part)) {
                modelParts |= (byte) part.getMask();
            }
        }
        corpse.setModelCustomisation(modelParts);

        // Capture how the player died, purely as data for a later (separately-scoped) cause-specific
        // corpse visual treatment (e.g. burned -> charred). Set once, at spawn, never touched again.
        corpse.setDeathCause(DeathCause.fromDamageSource(event.getSource()));
    }

    /** What happens to one item at death, shared by the armor/offhand/main-inventory loops above. */
    private enum DeathOutcome { DESTROY, KEEP, CORPSE }

    /**
     * Vanishing is checked first (matching vanilla's own precedence: a vanishing-cursed item is
     * destroyed before anything else about death-inventory handling runs), then a Soulbound chance
     * roll. Curse of Binding is NOT decided here at all — a bound armor piece transfers into the
     * corpse completely normally (falls through to {@code CORPSE} like an unenchanted item) and is
     * instead locked in place once it's there; see {@code CorpseMenu}'s armor {@code Slot} and
     * {@code CorpseEntity#removeItem}/{@code #removeItemNoUpdate}. See {@link ItemRetentionHandler}'s
     * class javadoc for the full exploit/precedence analysis.
     */
    private static DeathOutcome resolveDeathOutcome(ItemStack stack, RandomSource random) {
        if (ItemRetentionHandler.isVanishing(stack)) return DeathOutcome.DESTROY;
        if (ItemRetentionHandler.rollKeep(stack, random)) return DeathOutcome.KEEP;
        return DeathOutcome.CORPSE;
    }

    /**
     * Builds this corpse's {@link StuckProjectile} list: one synthesized entry per carried-over
     * arrow ({@link Player#getArrowCount()} -- vanilla tracks a COUNT only, no per-arrow placement
     * data, so placement is invented here) plus, if the killing blow itself was a projectile, one
     * extra entry for it.
     * <p>
     * {@code getArrowCount()} at this {@code LivingDeathEvent} handler has NOT yet been
     * incremented for a killing arrow landed this same tick, so there is no double-counting to
     * guard against here -- see this method's own verification note at the call site in
     * {@link #onPlayerDeath} for the full trace through {@code AbstractArrow#onHitEntity}.
     * <p>
     * All placement randomness -- for both the carried arrows and the killing-blow entry's cosmetic
     * jitter -- is drawn from a {@link StuckProjectile.SeedRandom} stepped from {@code
     * appearanceSeed} rather than {@code level.random}/{@code Math.random()}, so every client
     * renders the identical layout and it survives a relog.
     */
    private static List<StuckProjectile> captureStuckProjectiles(Player player, LivingDeathEvent event, long appearanceSeed) {
        StuckProjectile.SeedRandom rng = new StuckProjectile.SeedRandom(appearanceSeed);
        List<StuckProjectile> projectiles = new ArrayList<>();

        // VERIFIED against reference/forge-1.20.1-mapped-src/net/minecraft/world/entity/projectile/
        // AbstractArrow.java#onHitEntity: entity.hurt(damagesource, i) is called FIRST, and only
        // once it returns true does the method go on to call
        // livingentity.setArrowCount(livingentity.getArrowCount() + 1). hurt() -> actuallyHurt() ->
        // die() fires LivingDeathEvent synchronously before hurt() returns, so by the time this
        // handler runs (during that same synchronous dispatch, regardless of listener priority),
        // the killing arrow's increment has NOT happened yet --
        // getArrowCount() here is exactly the PRE-EXISTING pincushion. (ThrownTrident overrides
        // onHitEntity entirely and never calls setArrowCount() at all, so a killing trident is never
        // reflected in arrow count either way.) Capped defensively so a heavily-pincushioned player
        // can't force building an oversized list here -- CorpseEntity#setStuckProjectiles caps again
        // regardless, but there's no reason to synthesize more than it will ever keep.
        int carried = Math.min(player.getArrowCount(), CorpseEntity.MAX_STUCK_PROJECTILES);
        for (int i = 0; i < carried; i++) {
            int partIndex = rng.nextInt(6);
            projectiles.add(new StuckProjectile(partIndex, rng.nextFloat01(), rng.nextFloat01(),
                    rng.nextFloat01(), StuckProjectile.Kind.ARROW, -1, false));
        }

        Entity direct = event.getSource().getDirectEntity();
        if (direct instanceof AbstractArrow arrow) {
            projectiles.add(buildKillingBlowEntry(player, arrow, rng));
        }

        return projectiles;
    }

    /** Builds the single killing-blow {@link StuckProjectile} entry -- kind/colour from the
     *  concrete projectile class, placement from {@link #resolveKillingBlowPart}. */
    private static StuckProjectile buildKillingBlowEntry(Player player, AbstractArrow arrow, StuckProjectile.SeedRandom rng) {
        StuckProjectile.Kind kind = StuckProjectile.Kind.ARROW;
        int color = -1;
        if (arrow instanceof SpectralArrow) {
            kind = StuckProjectile.Kind.SPECTRAL_ARROW;
        } else if (arrow instanceof ThrownTrident) {
            kind = StuckProjectile.Kind.TRIDENT;
        } else if (arrow instanceof Arrow coloredArrow && coloredArrow.getColor() > 0) {
            // Arrow#getColor() is public (verified against reference/forge-1.20.1-mapped-src) -- no
            // access transformer needed.
            kind = StuckProjectile.Kind.TIPPED_ARROW;
            color = coloredArrow.getColor();
        }

        int partIndex = resolveKillingBlowPart(player, arrow);
        return new StuckProjectile(partIndex, rng.nextFloat01(), rng.nextFloat01(), rng.nextFloat01(),
                kind, color, true);
    }

    /**
     * Where the killing projectile visually lands, derived from where it actually struck rather
     * than randomly: a height fraction of the player's own bounding box picks the band (top ~18% ->
     * HEAD, bottom ~35% -> a LEG, the middle -> BODY -- arms are never targeted by this, keeping the
     * mapping simple), and for the leg band the horizontal offset relative to the player's own body
     * yaw picks left vs right. Falls back to BODY if the player's height is degenerate.
     */
    private static int resolveKillingBlowPart(Player player, AbstractArrow arrow) {
        double bbHeight = player.getBbHeight();
        if (bbHeight <= 0.0) return StuckProjectile.PART_BODY;

        double heightFraction = Math.max(0.0, Math.min(1.0, (arrow.getY() - player.getY()) / bbHeight));

        if (heightFraction >= 0.82) {
            return StuckProjectile.PART_HEAD;
        }
        if (heightFraction <= 0.35) {
            return isKillingBlowOnLeftSide(player, arrow)
                    ? StuckProjectile.PART_LEFT_LEG : StuckProjectile.PART_RIGHT_LEG;
        }
        return StuckProjectile.PART_BODY;
    }

    /**
     * True when {@code arrow} struck to the player's left, relative to body yaw. The player's
     * "right" unit vector in world XZ is {@code (-cos(yaw), -sin(yaw))} -- derived by rotating the
     * forward vector {@code (-sin(yaw), cos(yaw))} by +90 degrees of yaw, matching a real-world
     * compass check: facing yaw=0 (south, +Z), the right hand points toward -X (west).
     */
    private static boolean isKillingBlowOnLeftSide(Player player, AbstractArrow arrow) {
        double dx = arrow.getX() - player.getX();
        double dz = arrow.getZ() - player.getZ();
        double yawRad = Math.toRadians(player.yBodyRot);
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);
        return (dx * rightX + dz * rightZ) < 0.0;
    }

    /**
     * Cancels vanilla's own death XP-orb spawn for players entirely. Without this, {@link
     * #onPlayerDeath} above (which rolls a corpse-stored XP payout) runs ALONGSIDE, not instead of,
     * vanilla's own separate {@code LivingEntity#dropExperience()} — nothing about handling {@code
     * LivingDeathEvent} stops vanilla's later, independent orb-drop later in the same {@code die()}
     * call chain. Left unfixed, every death effectively paid out TWICE: once as orbs on the ground
     * at the death location (vanilla, capped at {@code min(level*7, 100)}), and again via the
     * corpse's own stored-XP system. This is the one and only place that vanilla payout is
     * suppressed — the corpse is meant to be the SOLE holder of a dead player's XP.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Level level = player.level();
        if (level.isClientSide) return;

        // Mirror onPlayerDeath's own gate exactly: if keepInventory is true, that handler never
        // ran and the corpse never claimed this player's XP, so vanilla's own drop must stand.
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;

        event.setCanceled(true);
    }
}
