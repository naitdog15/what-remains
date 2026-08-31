package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.network.ServerboundXpBottleGesture;
import com.temog.notminelegacy.whatremains.network.WRNetwork;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client half of the Bottle o' Enchanting gesture (LB-03 plan D6).
 *
 * <p>The whole gate lives at the input edge, before {@code Minecraft#startUseItem} can turn a click
 * into a use packet. Doing it here — rather than deferring server-side — is what removes the arm
 * map that survived relogs, the visible item cooldown, the both-hands double packet, the re-equip
 * animation, and the held-button auto-repeat ambiguity, all at once.
 *
 * <p><b>The gate is an accident guard, not a mechanic.</b> Click one has no observable effect at
 * all: no sound, no swing, no particle, no packet. Click two only counts if the button was
 * genuinely released in between (Minecraft re-fires {@code startUseItem} every five ticks while the
 * button is held, so a held button would otherwise throw), lands inside a short window, and finds
 * the same bottle still in the same hand and the same slot.
 *
 * <p>Cancelling the input event skips vanilla's whole per-hand block, so the block/entity
 * interaction it would have tried first is re-issued here in vanilla's own order. Without that,
 * holding a bottle would stop the player opening a chest or trading with a villager. Vanilla never
 * reaches the second hand when the first holds a bottle either — {@code ExperienceBottleItem#use}
 * consumed the click — so ending the loop here matches it exactly.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, value = Dist.CLIENT)
public final class XpBottleGestureHandler {

    /** Fallback arm window, used only before the config has synced/baked. */
    private static final int DEFAULT_ARM_WINDOW_TICKS = 10;

    private XpBottleGestureHandler() {}

    /** How long an armed first click stays live, in client ticks (config, LB-03). */
    private static int armWindowTicks() {
        int configured = WhatRemainsConfig.xpBottleDoubleClickWindowTicks;
        return configured > 0 ? configured : DEFAULT_ARM_WINDOW_TICKS;
    }

    private static long armedAtGameTime = Long.MIN_VALUE;
    private static InteractionHand armedHand;
    private static int armedSlot = Inventory.NOT_FOUND_INDEX;

    /**
     * Whether the use button has actually been up since the arming click. Held-button auto-repeat
     * never clears this, which is exactly how holding right-click is stopped from throwing.
     */
    private static boolean releasedSinceArm;

    // ── Input edge ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.gameMode == null) return;
        if (player.isSpectator()) return;

        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);

        // XPB-06: only the crafted xp_bottle is a vessel now — a bare glass bottle (and a plain
        // vanilla experience_bottle, restored to 100% vanilla) is untouched input.
        if (!XpVessel.isXpBottle(held)) return;

        // Master switch, FAIL-CLOSED direction (LB-03 config, client/server sync note) — not
        // fail-open: the feature stays off/inert, never active, until config proves otherwise. This
        // is a SERVER-type spec: it login-syncs, but there is a window before it does.
        // WhatRemainsConfig.xpBottleEnabled's pre-sync Java primitive default is `false`, which is
        // EXACTLY the fail-closed direction wanted here — do NOT cancel the click, let vanilla's own
        // use() decide, and never send a packet. A lost gesture before sync is recoverable (the
        // player just tries again once synced); a bottle bricked by a lockout that never got armed
        // because no gesture was ever sent is not.
        if (!XpVessel.enabled()) return;

        // From here on vanilla must not process this click: its use() would throw the bottle.
        event.setSwingHand(false);
        event.setCanceled(true);

        // Vanilla tries what the crosshair is on first, and only falls through to the item when
        // that declines. Reproduce that ordering, or a bottle in hand becomes a "cannot interact
        // with anything" item.
        if (runTargetInteraction(minecraft, player, hand)) return;

        int slot = slotFor(player, hand);

        if (player.isShiftKeyDown()) {
            // V2.1 orb vacuum takes priority over deposit-from-the-player's-own-balance: capture
            // nearby orbs into the bottle whenever at least one actually fits, and fall back to the
            // player's own XP bar only when none do. A full bottle always computes room <= 0, so
            // anyOrbFits is false there and this falls straight through to DEPOSIT, whose server
            // handler explicitly rejects a full bottle.
            int vacuumRoom = XpVessel.capacity() - XpVessel.getStoredXp(held);
            if (XpVessel.vacuumEnabled() && XpVessel.anyOrbFits(player, vacuumRoom)) {
                disarm();
                WRNetwork.sendToServer(new ServerboundXpBottleGesture(
                        ServerboundXpBottleGesture.Action.VACUUM, hand, slot, true));
                return;
            }

            // The deposit gesture is immediate. A full bottle still sends it so the server can play
            // the rejection cue; the guard exists to protect XP from being thrown away.
            disarm();
            WRNetwork.sendToServer(new ServerboundXpBottleGesture(
                    ServerboundXpBottleGesture.Action.DEPOSIT, hand, slot, true));
            return;
        }

        if (!WhatRemainsConfig.xpBottleRequireDoubleClickToThrow) {
            // Gate off: a single non-sneak click throws immediately, no arm/release step at all.
            // All other validation (target-interaction fallthrough above, server-side re-checks)
            // still applies — only the accident-guard's own two-click requirement is skipped.
            disarm();
            WRNetwork.sendToServer(new ServerboundXpBottleGesture(
                    ServerboundXpBottleGesture.Action.THROW, hand, slot, false));
            return;
        }

        if (isSecondClick(minecraft, player, hand, slot)) {
            disarm();
            WRNetwork.sendToServer(new ServerboundXpBottleGesture(
                    ServerboundXpBottleGesture.Action.THROW, hand, slot, false));
            return;
        }

        arm(minecraft, hand, slot);
    }

    /**
     * Re-issues the block/entity interaction vanilla's {@code startUseItem} would have run for this
     * hand, in vanilla's order and with vanilla's swing rules.
     *
     * @return true when the interaction claimed the click, so the gesture must not see it
     */
    private static boolean runTargetInteraction(Minecraft minecraft, LocalPlayer player, InteractionHand hand) {
        HitResult hitResult = minecraft.hitResult;
        if (hitResult == null || minecraft.level == null) return false;

        switch (hitResult.getType()) {
            case ENTITY -> {
                EntityHitResult entityHit = (EntityHitResult) hitResult;
                Entity target = entityHit.getEntity();
                if (!minecraft.level.getWorldBorder().isWithinBounds(target.blockPosition())) return true;

                InteractionResult result = minecraft.gameMode.interactAt(player, target, entityHit, hand);
                if (!result.consumesAction()) {
                    result = minecraft.gameMode.interact(player, target, hand);
                }
                if (result.consumesAction()) {
                    if (result.shouldSwing()) player.swing(hand);
                    return true;
                }
            }
            case BLOCK -> {
                BlockHitResult blockHit = (BlockHitResult) hitResult;
                InteractionResult result = minecraft.gameMode.useItemOn(player, hand, blockHit);
                if (result.consumesAction()) {
                    if (result.shouldSwing()) player.swing(hand);
                    return true;
                }
                // Vanilla treats an outright FAIL as "this click is spent" and stops.
                if (result == InteractionResult.FAIL) return true;
            }
            default -> {
                // MISS: nothing under the crosshair, so the click was always going to reach the item.
            }
        }
        return false;
    }

    // ── Arm state ───────────────────────────────────────────────────────────

    private static boolean isSecondClick(Minecraft minecraft, LocalPlayer player, InteractionHand hand, int slot) {
        if (armedHand != hand || armedSlot != slot) return false;
        if (!releasedSinceArm) return false;
        if (minecraft.level == null) return false;
        return minecraft.level.getGameTime() - armedAtGameTime <= armWindowTicks();
    }

    private static void arm(Minecraft minecraft, InteractionHand hand, int slot) {
        armedHand = hand;
        armedSlot = slot;
        armedAtGameTime = minecraft.level == null ? Long.MIN_VALUE : minecraft.level.getGameTime();
        releasedSinceArm = false;
    }

    private static void disarm() {
        armedHand = null;
        armedSlot = Inventory.NOT_FOUND_INDEX;
        armedAtGameTime = Long.MIN_VALUE;
        releasedSinceArm = false;
    }

    private static int slotFor(LocalPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : Inventory.SLOT_OFFHAND;
    }

    /**
     * Watches for the button release the second click depends on, and drops the arm the moment the
     * player changes what they are holding — the guard must never fire on a bottle other than the
     * one it was armed for.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (armedHand == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            disarm();
            return;
        }

        // Fix round F5: any Screen opening (inventory, chest, pause menu, chat, ...) between the
        // arming click and the second one must drop the arm — none of vanilla's own key-input
        // handling runs while a Screen owns input focus, so a "click 2" that arrives after closing
        // one is a fresh, unrelated click and must not be read as the second half of a stale guard.
        if (minecraft.screen != null) {
            disarm();
            return;
        }

        if (!minecraft.options.keyUse.isDown()) {
            releasedSinceArm = true;
        }

        if (minecraft.level.getGameTime() - armedAtGameTime > armWindowTicks()) {
            disarm();
            return;
        }

        if (slotFor(player, armedHand) != armedSlot) {
            disarm();
            return;
        }

        ItemStack held = player.getItemInHand(armedHand);
        if (!XpVessel.isXpBottle(held)) {
            disarm();
        }
    }

    /** A fresh world (or a relog) must never inherit a live arm. */
    @SubscribeEvent
    public static void onLoggedOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        disarm();
    }
}
