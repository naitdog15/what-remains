package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.network.GreatBottleGesturePacket;
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
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Client half of the Great Bottle o' Enchanting gesture (LB-03 spec §6), mirroring
 * {@code whatremains.client.XpBottleGestureHandler}'s arm/release/window state machine — see that
 * class's javadoc for the full "why" behind intercepting at the input edge rather than the server.
 *
 * <p>Unconditionally {@code @Mod.EventBusSubscriber}-registered like every other client handler in
 * this module — FML scans and subscribes it regardless of whether DNL is present. Every entry point
 * therefore checks {@link #bridgeActive()} before handling input. Deposit/vacuum/throw math remains
 * server-authoritative; this class identifies the held vessel through {@link XpVessel} and sends
 * the dedicated request packet.
 *
 * <p><b>V2.1 orb vacuum exception.</b> Deciding whether sneak+use should send {@code VACUUM} instead
 * of {@code DEPOSIT} needs to know, client-side, whether the held Great Bottle is full and how much
 * room it has left — both of which are level-curve math this class has no reason to duplicate. This
 * uses the same item-aware {@link XpVessel} methods as the server so both sides agree on capacity,
 * fill state, vacuum policy, and radius.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, value = Dist.CLIENT)
public final class GreatBottleGestureHandler {

    private static final String DNL_MODID = "dungeonnowloading";

    private GreatBottleGestureHandler() {}

    private static long armedAtGameTime = Long.MIN_VALUE;
    private static InteractionHand armedHand;
    private static int armedSlot = Inventory.NOT_FOUND_INDEX;
    private static boolean releasedSinceArm;

    private static boolean bridgeActive() {
        if (!ModList.get().isLoaded(DNL_MODID)) return false;
        // Client fail-safe: a SERVER-type spec that has not yet been synced (e.g. the narrow login
        // window before ConfigSync bakes the server's actual value in) must resolve to "inactive",
        // never fall back to the compiled default. A missed gesture this frame is harmless — it is
        // simply retried on the next click — but wrongly cancelling input here while the real
        // (possibly disabled) state hasn't landed yet risks eating a click that should have reached
        // DNL's own unlocked use().
        if (!GreatBottleConfig.SPEC.isLoaded()) return false;
        return GreatBottleConfig.greatBottleEnabled;
    }

    /** How long (in client ticks) an armed first click stays live — config-driven, only consulted
     *  once {@link #bridgeActive()} has already confirmed the spec is loaded. */
    private static int doubleClickWindowTicks() {
        return GreatBottleConfig.greatBottleDoubleClickWindowTicks;
    }

    // ── Input edge ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onUseInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;
        if (!bridgeActive()) return;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.gameMode == null) return;
        if (player.isSpectator()) return;

        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);
        if (!XpVessel.isGreatBottle(held)) return;

        // From here on vanilla must not process this click: its (now-neutralised) use() would
        // otherwise no-op anyway, but cancelling still matters for the block/entity interaction
        // re-dispatch below and to suppress the swing/re-equip animation.
        event.setSwingHand(false);
        event.setCanceled(true);

        if (runTargetInteraction(minecraft, player, hand)) return;

        int slot = slotFor(player, hand);

        if (player.isShiftKeyDown()) {
            disarm();
            // V2.1 orb vacuum (LB-03): a not-yet-full Great Bottle prefers pulling nearby orbs in
            // over draining the player's own bar — VACUUM takes priority, DEPOSIT is only the
            // fallback once nothing is capturable. A FULL bottle skips the probe and sends DEPOSIT
            // so the server can play the authoritative rejection cue; throwing is the only way to
            // cash out stored XP.
            if (!XpVessel.isFull(held) && XpVessel.vacuumEnabled(held)) {
                int room = XpVessel.capacity(held) - XpVessel.getStoredXp(held);
                if (XpVessel.anyOrbFits(player, held, room)) {
                    WRNetwork.sendToServer(new GreatBottleGesturePacket(
                            GreatBottleGesturePacket.Action.VACUUM, hand, slot, true));
                    return;
                }
            }
            WRNetwork.sendToServer(new GreatBottleGesturePacket(
                    GreatBottleGesturePacket.Action.DEPOSIT, hand, slot, true));
            return;
        }

        if (!GreatBottleConfig.greatBottleRequireDoubleClickToThrow) {
            // Accident guard narrowed off: a single non-sneak click throws immediately, same as
            // vanilla — still routed through the packet, so server-side validation is unchanged.
            disarm();
            WRNetwork.sendToServer(new GreatBottleGesturePacket(
                    GreatBottleGesturePacket.Action.THROW, hand, slot, false));
            return;
        }

        if (isSecondClick(minecraft, hand, slot)) {
            disarm();
            WRNetwork.sendToServer(new GreatBottleGesturePacket(
                    GreatBottleGesturePacket.Action.THROW, hand, slot, false));
            return;
        }

        arm(minecraft, hand, slot);
    }

    /**
     * Re-issues the block/entity interaction vanilla's {@code startUseItem} would have run for this
     * hand, in vanilla's order and with vanilla's swing rules — identical logic to
     * {@code XpBottleGestureHandler#runTargetInteraction}.
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
                if (result == InteractionResult.FAIL) return true;
            }
            default -> {
                // MISS: nothing under the crosshair, so the click was always going to reach the item.
            }
        }
        return false;
    }

    // ── Arm state ───────────────────────────────────────────────────────────

    private static boolean isSecondClick(Minecraft minecraft, InteractionHand hand, int slot) {
        if (armedHand != hand || armedSlot != slot) return false;
        if (!releasedSinceArm) return false;
        if (minecraft.level == null) return false;
        return minecraft.level.getGameTime() - armedAtGameTime <= doubleClickWindowTicks();
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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (armedHand == null) return;
        if (!bridgeActive()) {
            disarm();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            disarm();
            return;
        }

        if (!minecraft.options.keyUse.isDown()) {
            releasedSinceArm = true;
        }

        if (minecraft.level.getGameTime() - armedAtGameTime > doubleClickWindowTicks()) {
            disarm();
            return;
        }

        if (slotFor(player, armedHand) != armedSlot) {
            disarm();
            return;
        }

        if (!XpVessel.isGreatBottle(player.getItemInHand(armedHand))) {
            disarm();
        }
    }

    /** A fresh world (or a relog) must never inherit a live arm. */
    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        disarm();
    }
}
