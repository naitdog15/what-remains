package com.temog.notminelegacy.whatremains.events;

import com.temog.notminelegacy.xp.XpMath;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Reconciles vanilla float drift after a player finishes collecting this mod's bottle payout.
 * {@link PlayerXpEvent.PickupXp} fires before Mending and before the remaining XP reaches the bar,
 * so every marked pickup re-arms a delayed task rather than reconciling inside the event.
 * <p>
 * Known, accepted limitation: vanilla may merge a marked payout orb into an existing orb entity,
 * in which case the surviving entity's persistent data wins and this marker can be lost. The worst
 * case is that reconciliation does not run and behaviour remains exactly as it was before this fix;
 * orb-merge tracking is deliberately out of scope.
 */
@Mod.EventBusSubscriber(modid = "whatremains", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class XpBottlePayoutReconciler {

    /** Forge-persistent marker carried only by orbs spawned from this mod's bottle manifests. */
    public static final String PAYOUT_ORB_MARKER = "whatremains:xp_bottle_payout";

    /** Five quiet ticks comfortably outlast vanilla's two-tick per-player orb pickup delay. */
    private static final int QUIET_WINDOW_TICKS = 5;

    /**
     * Game time at which each player's pending reconciliation becomes due. Every marked pickup
     * overwrites the entry with {@code now + QUIET_WINDOW_TICKS}, so the window always measures
     * from the LAST pickup — which is the whole point, given a 32-chunk payout can trickle in over
     * roughly 64 ticks and no fixed deadline can cover that.
     * <p>
     * <b>Why a due-tick map and not {@code server.tell(new TickTask(...))}.</b> A {@code TickTask}'s
     * tick number is <i>not</i> a delay: {@code MinecraftServer#shouldRun} is
     * {@code task.getTick() + 3 < tickCount || haveTime()}, so whenever the server has spare time
     * in the current tick — the normal case — the task runs almost immediately. That tick number is
     * a starvation guard for old tasks, nothing more. Scheduling that way would reconcile once per
     * orb instead of once per payout and would silently defeat this aggregation window.
     */
    private static final Map<UUID, Long> PENDING_DUE_TICK = new ConcurrentHashMap<>();

    private XpBottlePayoutReconciler() {}

    @SubscribeEvent
    public static void onPickupXp(PlayerXpEvent.PickupXp event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!event.getOrb().getPersistentData().getBoolean(PAYOUT_ORB_MARKER)) return;
        // Re-arm: a later pickup always pushes the due tick further out.
        PENDING_DUE_TICK.put(player.getUUID(), player.serverLevel().getGameTime() + QUIET_WINDOW_TICKS);
    }

    /**
     * Services the due map. Mirrors {@code XpBottleNormalizer}'s own per-player tick pattern rather
     * than introducing a second scheduling style into this module.
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (PENDING_DUE_TICK.isEmpty()) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        Long dueTick = PENDING_DUE_TICK.get(player.getUUID());
        if (dueTick == null) return;
        if (player.serverLevel().getGameTime() < dueTick) return;

        // remove(key, value) so a pickup landing this same tick re-arms rather than being dropped.
        if (!PENDING_DUE_TICK.remove(player.getUUID(), dueTick)) return;
        if (player.isRemoved()) return;
        XpMath.reconcilePlayerXp(player);
    }

    /** Drops the departing player's pending entry rather than letting the UUID linger. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING_DUE_TICK.remove(event.getEntity().getUUID());
    }
}
