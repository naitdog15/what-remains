package com.temog.notminelegacy.whatremains.network;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.compat.dnl.DnlGreatBottleBridge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;

/**
 * The Great Bottle o' Enchanting gesture (LB-03 spec §6), carried alongside the normal bottle's
 * {@link ServerboundXpBottleGesture} on {@link WRNetwork}'s channel.
 *
 * <p><b>Channel decision.</b> Reusing whatremains' existing gesture packet was considered and
 * rejected — not a style preference but a hard blocker: its server handler hardcodes a check
 * against {@code whatremains:xp_bottle} (XPB-06: {@code XpVessel#isXpBottle}), so it cannot
 * validate a different item. This separate,
 * structurally identical message mirrors
 * {@code ServerboundXpBottleGesture}'s wire shape (action/hand/slot/sneaking) and its server-side
 * validation discipline (live hand/slot/item re-derived from server state, a rate limit keyed by
 * player UUID) so the two features read as one design, not two independently-invented ones.
 *
 * <p>Every call into {@link DnlGreatBottleBridge} below happens only after
 * {@link #validateAndRun} confirms DungeonNowLoading is loaded; WhatRemains owns this packet and
 * is therefore already present.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GreatBottleGesturePacket {

    private static final String DNL_MODID = "dungeonnowloading";

    private static final Map<UUID, Long> LAST_ACCEPTED_GAME_TIME = new ConcurrentHashMap<>();

    public enum Action {
        /** Sneak + use: bank the player's XP; a full bottle is rejected. */
        DEPOSIT,
        /** Second click of the non-sneak accident guard: actually throw the bottle. */
        THROW,
        /**
         * Sneak + use on a not-yet-full Great Bottle with capturable {@code ExperienceOrb}s nearby
         * (V2.1 orb vacuum) — pulls those orbs into the bottle instead of draining the player's own
         * XP bar. The client only ever sends this in place of {@code DEPOSIT} for a not-yet-full
         * bottle: a FULL bottle sends {@code DEPOSIT} so the server can reject it (see {@code
         * GreatBottleGestureHandler}), and {@code DnlGreatBottleBridge#handleVacuum} refuses a full
         * bottle as its own belt-and-braces copy of that same rule.
         */
        VACUUM
    }

    private final Action action;
    private final InteractionHand hand;
    private final int slot;
    private final boolean sneaking;

    public GreatBottleGesturePacket(Action action, InteractionHand hand, int slot, boolean sneaking) {
        this.action = action;
        this.hand = hand;
        this.slot = slot;
        this.sneaking = sneaking;
    }

    // ── Wire format ─────────────────────────────────────────────────────────

    public static void encode(GreatBottleGesturePacket message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeEnum(message.hand);
        buffer.writeVarInt(message.slot);
        buffer.writeBoolean(message.sneaking);
    }

    public static GreatBottleGesturePacket decode(FriendlyByteBuf buffer) {
        return new GreatBottleGesturePacket(
                buffer.readEnum(Action.class),
                buffer.readEnum(InteractionHand.class),
                buffer.readVarInt(),
                buffer.readBoolean()
        );
    }

    // ── Server handling ─────────────────────────────────────────────────────

    public static void handle(GreatBottleGesturePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        validateAndRun(message, context.getSender());
        context.setPacketHandled(true);
    }

    /**
     * Re-derives every precondition from live server state, exactly like
     * {@code ServerboundXpBottleGesture#validateAndRun} does for the vanilla bottle. The
     * {@code ModList} gate runs BEFORE anything touches {@link DnlGreatBottleBridge} — see class
     * javadoc.
     */
    private static void validateAndRun(GreatBottleGesturePacket message, ServerPlayer player) {
        if (player == null || message.action == null || message.hand == null) return;
        if (!ModList.get().isLoaded(DNL_MODID)) return;
        if (player.isRemoved() || !player.isAlive() || player.isSpectator()) return;
        // Master-off: DnlGreatBottleBridge#handleDeposit/handleThrow would reject this anyway (each
        // re-checks isActive() itself), but bailing here also skips touching the rate-limit map for
        // a gesture that can never do anything while the bridge is switched off.
        if (!DnlGreatBottleBridge.isActive()) return;

        long now = player.level().getGameTime();
        Long lastAccepted = LAST_ACCEPTED_GAME_TIME.get(player.getUUID());
        if (lastAccepted != null
                && now - lastAccepted < GreatBottleConfig.greatBottleGestureRateLimitTicks) return;

        boolean accepted = switch (message.action) {
            // A deposit is only ever produced by a sneaking press — an un-sneaked DEPOSIT is a
            // malformed request, mirroring ServerboundXpBottleGesture's own rule.
            case DEPOSIT -> message.sneaking
                    && DnlGreatBottleBridge.handleDeposit(player, message.hand, message.slot);
            case THROW -> DnlGreatBottleBridge.handleThrow(player, message.hand, message.slot);
            // Same sneaking requirement as DEPOSIT (V2.1 orb vacuum) — VACUUM is a sneak-gesture
            // variant of it, never a standalone click, so an un-sneaked one is equally malformed.
            case VACUUM -> message.sneaking
                    && DnlGreatBottleBridge.handleVacuum(player, message.hand, message.slot);
        };

        // Only an ACCEPTED gesture updates the timestamp — a rejected one must not itself become a
        // spam-pacing signal (same rule as the vanilla bottle's rate limit).
        if (accepted) {
            LAST_ACCEPTED_GAME_TIME.put(player.getUUID(), now);
        }
    }

    /** Drops the departing player's rate-limit entry rather than let the UUID linger for the rest
     *  of the server's lifetime. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_ACCEPTED_GAME_TIME.remove(event.getEntity().getUUID());
    }
}
