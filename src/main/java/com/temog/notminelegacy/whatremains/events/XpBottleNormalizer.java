package com.temog.notminelegacy.whatremains.events;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.logging.LogUtils;
import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;

/**
 * Non-destructive repair net for oversized XP-vessel stacks (LB-03 fix round F2, replacing the
 * {@code ExperienceBottleItemMixin}-merged {@code inventoryTick} that used to live there — see
 * that class's javadoc for why it was removed).
 *
 * <p><b>Threat model (rewritten XPB-07).</b> Before XPB-07, ANY partial stack
 * (0 &lt; storedXp &lt; capacity, per {@link XpVessel#isPartial}) with {@code count > 1} was
 * axiomatically illegal — {@link XpVessel#maxStackFor} capped partials at 1, so such a stack could
 * only be reached through {@code /give}, hand-edited NBT, a foreign mod, or a capacity config
 * change reclassifying a full stack as partial. XPB-07 relaxes that cap: a bottle carrying ANY
 * stored XP, partial or full, now stacks to {@code fullStackSize} (16 default) provided every unit
 * shares byte-identical NBT — see {@link XpVessel#maxStackFor}'s own javadoc for why that is safe
 * (every unit in such a stack honestly cost being deposited/vacuumed/found to its exact value; the
 * same reasoning that already covered full stacks pre-XPB-07 now covers any exact value).
 * <p>
 * What remains genuinely illegal is a stack whose {@code count} exceeds the vessel's OWN
 * <em>currently configured</em> {@link XpVessel#maxStackFor} — reachable only via {@code /give},
 * NBT editing, a foreign mod, or a {@code fullStackSize}/{@code emptyStackSize} config change that
 * LOWERS the limit below what existing legitimate stacks already hold (the empty-stack analogue of
 * the old capacity-raise scenario). None of these are an in-game duplication exploit reachable
 * through normal play, and an operator with {@code /give}/NBT-edit access can already {@code /xp}
 * themselves whatever they like. This class exists purely to keep an already-legitimate save
 * usable after such a change — it is a repair net, never an anti-cheat wall, and it must never be
 * the thing that decides a stack is "fraudulent" and destroys it.
 *
 * <p>Because an oversized, NBT-identical stack cannot be told apart from N independently-earned
 * bottles that simply merged in the inventory UI, destroying any part of it is never safe. Repair
 * is therefore purely structural and value-preserving: one unit is peeled off into its own count-1
 * stack (carrying the SAME stored-XP tag) per scan pass, placed back into the inventory, so the
 * oversized stack shrinks by exactly one every pass until it fits within the current legal limit.
 * Nothing is ever dropped into the world, no tag is ever stripped, and no claim is ever deleted —
 * if the split-off copy does not fit anywhere this pass, the inventory is left completely
 * untouched and the attempt is simply retried on the next pass.
 *
 * <p>The scan covers each player's main inventory AND offhand — armor slots and block containers
 * are not walked — which is deliberate under the threat model above (op-origin only); the
 * throw/dispense paths pay out whatever a legally-sized stack honestly carries regardless of which
 * of those other locations it was thrown from.
 *
 * <p><b>V2: both keys.</b> A vessel now carries two tags — {@code StoredXp} (the scalar
 * {@link XpVessel} treats as canonical everywhere) and {@code StoredOrbs} (the orb manifest,
 * always subordinate to it; see {@link XpVessel}'s class javadoc). {@code getOrbManifest} already
 * re-derives the manifest from the scalar on every read, so a manifest that merely disagrees with
 * a healthy scalar is self-healing and never reaches a payout. When the split-off copy for an
 * oversized stack IS taken, the manifest travels with it for free: {@link ItemStack#copyWithCount}
 * deep-copies the whole NBT compound, {@code StoredOrbs} included, so the peeled bottle keeps
 * paying out exactly what the source would have.
 * <p>
 * <b>XPB-06: the glass-conversion repair is retired.</b> A pre-XPB-06 stack could carry an
 * orphaned {@code StoredOrbs} manifest with no scalar (positive provenance it was a vessel that
 * reached zero), and this class used to repair that by converting the live slot to
 * {@code minecraft:glass_bottle}. Now that a plain glass bottle can never become a vessel again,
 * that conversion would resurrect exactly the glass&harr;vessel interchangeability this whole round
 * closes — an emptied {@code xp_bottle} simply stays an empty {@code xp_bottle} (it already stacks
 * to 64 same as glass under the existing empty-bottle rule), so the orphan is left as dead NBT for
 * {@code getOrbManifest} to quietly ignore rather than being "repaired" into a different item.
 *
 * <p><b>XPB-03: also scans the DungeonNowLoading Great Bottle</b> ({@link XpVessel#isGreatBottle}),
 * gated by its own independent master/normalize switches
 * ({@link GreatBottleConfig#greatBottleEnabled} / {@link GreatBottleConfig#greatBottleNormalizeIllegalStacks}) —
 * the oversized-stack split repair is pure structural NBT math with no vessel-specific meaning, so
 * it is shared between both vessel types. See {@link #scanCompartment} for the exact gating.
 */
@Mod.EventBusSubscriber(modid = "whatremains", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class XpBottleNormalizer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often (in ticks) each player's inventory is scanned for illegal stacks. */
    private static final int SCAN_INTERVAL_TICKS = 20;

    /** Players already WARNed this session, so the log gets one line rather than one per pass. */
    private static final Set<UUID> WARNED_THIS_SESSION = ConcurrentHashMap.newKeySet();

    private XpBottleNormalizer() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % SCAN_INTERVAL_TICKS != 0) return;

        // Independent per-vessel gates. XpVessel.isGreatBottle already resolves the DNL item by
        // registry lookup (degrades to false when DNL is absent), so greatActive needs no separate
        // presence check of its own — no stack can ever satisfy isGreatBottle unless DNL is
        // genuinely loaded, regardless of what this flag evaluates to.
        boolean normalActive = XpVessel.enabled() && WhatRemainsConfig.xpBottleNormalizeIllegalStacks;
        boolean greatActive = GreatBottleConfig.greatBottleEnabled && GreatBottleConfig.greatBottleNormalizeIllegalStacks;
        if (!normalActive && !greatActive) return;

        Inventory inventory = player.getInventory();
        boolean sawIllegalStack = false;

        // Main inventory and offhand are scanned identically — an oversized stack sitting in the
        // offhand is no less reachable by /give or NBT-edit than one in the main inventory.
        sawIllegalStack |= scanCompartment(inventory, inventory.items, normalActive, greatActive);
        sawIllegalStack |= scanCompartment(inventory, inventory.offhand, normalActive, greatActive);

        if (sawIllegalStack && WARNED_THIS_SESSION.add(player.getUUID())) { // NORMALIZER_WARN_ANCHOR
            LOGGER.warn(
                    "[WhatRemains] {} is carrying an oversized XP vessel stack (count exceeds the "
                            + "vessel's current configured stack limit) — normalizing non-destructively "
                            + "(one bottle split off per {}-tick pass; no XP is ever created or destroyed "
                            + "by this repair). This can only arise from /give, NBT editing, a foreign "
                            + "mod, or a stack-size config change.",
                    player.getName().getString(), SCAN_INTERVAL_TICKS);
        }
    }

    /**
     * Runs the oversized-stack repair over one inventory compartment. {@code compartment} must be
     * a live backing list of {@code inventory} (its {@code items} or {@code offhand}).
     * <p>
     * {@code normalActive}/{@code greatActive} gate each vessel independently — a stack whose own
     * vessel type is currently disabled is skipped entirely, exactly as if this class did not scan
     * it at all.
     *
     * @return true when an oversized stack was seen, so the caller can log once per session
     */
    private static boolean scanCompartment(Inventory inventory, NonNullList<ItemStack> compartment,
            boolean normalActive, boolean greatActive) {
        boolean sawIllegalStack = false;

        for (int slot = 0; slot < compartment.size(); slot++) {
            ItemStack stack = compartment.get(slot);
            boolean isNormalBottle = XpVessel.isXpBottle(stack);
            boolean isGreatBottle = !isNormalBottle && XpVessel.isGreatBottle(stack);

            if (isNormalBottle && !normalActive) continue;
            if (isGreatBottle && !greatActive) continue;
            if (!isNormalBottle && !isGreatBottle) continue;

            // XPB-07: a stack is illegal only when it exceeds its OWN current maxStackFor, not
            // merely for being partial with count > 1 (that is now a normal, legal state — see
            // this class's javadoc). This still catches a stack-size config LOWERING after
            // players already hold larger legitimate stacks, the same way the pre-XPB-07 check
            // caught a capacity RAISING reclassifying a full stack as partial.
            if (stack.getCount() > XpVessel.maxStackFor(stack)) {
                sawIllegalStack = true;
                splitOneOff(inventory, stack);
            }
        }
        return sawIllegalStack;
    }

    /**
     * Peels exactly one unit off {@code stack} into its own count-1 stack, sharing the same
     * stored-XP tag — and, since {@link ItemStack#copyWithCount} deep-copies the whole NBT
     * compound, the same orb manifest too — if, and only if, a free slot exists to hold it. The
     * candidate split is tried against the live inventory FIRST; the source stack is only shrunk
     * once that attempt is confirmed to have fully landed somewhere, so a failed attempt leaves
     * the inventory bit-identical rather than losing or duplicating anything.
     */
    private static void splitOneOff(Inventory inventory, ItemStack stack) {
        // Preflight a genuinely free slot BEFORE touching anything. Inventory#add empties the
        // candidate and returns true for an `instabuild` player even when NOTHING was placed
        // (Inventory:272-274 — `count == i && instabuild` → setCount(0), return true). The
        // isEmpty() re-check below would then read that as "it landed" and shrink the source,
        // destroying a bottle and its stored claim outright. A creative player with 36 full main
        // slots is exactly that case, and scanning the offhand made it newly reachable.
        if (inventory.getFreeSlot() < 0) return;

        ItemStack candidate = stack.copyWithCount(1);

        // XPB-07 note: inventory.add may now legitimately MERGE candidate into another existing
        // same-value stack elsewhere (rather than only ever occupying a fresh empty slot, as it
        // did back when a partial's maxStackFor was hardcoded to 1) — that is a strictly BETTER
        // outcome (further consolidation) and this preflight still works correctly either way,
        // it just means the getFreeSlot() < 0 bail-out above is occasionally more conservative
        // than strictly necessary (it declines to even try a merge-only repair when nothing is
        // free), never unsafe.
        inventory.add(candidate);
        if (!candidate.isEmpty()) return;

        stack.shrink(1);
    }
}
