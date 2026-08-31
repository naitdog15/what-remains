package com.temog.notminelegacy.whatremains.events;

import java.util.List;

import javax.annotation.Nullable;

import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.registry.WRItems;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Per-source XP roll for traded Bottles o' Enchanting (LB-03 plan D10).
 * <p>
 * Wraps every villager and wandering-trader {@link VillagerTrades.ItemListing} in a
 * delegating listing. Whenever the wrapped listing produces an offer selling
 * {@code minecraft:experience_bottle}, the result is replaced by a single
 * {@code whatremains:xp_bottle} (XPB-06 &mdash; the OUTPUT item, detection still keys off
 * vanilla's own trade-table id) carrying its own independent raw-XP roll
 * ({@link XpVessel#rollSourceXp}; 6&ndash;22 raw XP by default &mdash; double vanilla's own
 * 3&ndash;11 throw default).
 * <p>
 * The roll happens inside {@link VillagerTrades.ItemListing#getOffer}, which runs each time
 * an offer instance is generated (villager level-up via {@code Villager#updateTrades},
 * wandering-trader spawn) &mdash; so every generated offer differs, rather than one roll
 * being baked into the static trade table at server start.
 */
@Mod.EventBusSubscriber(modid = "whatremains", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class XpBottleTradeRoller {

    private XpBottleTradeRoller() {}

    // LOWEST priority: other mods' trade handlers (including any instanceof-based surgery on
    // vanilla listing classes) run first and see the original listings, not our wrappers.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onVillagerTrades(VillagerTradesEvent event) {
        // Master switch / rollTradeBottles gate off: leave the vanilla listings completely
        // unwrapped — trades stay 100% vanilla, including any bottle offers.
        if (!XpVessel.enabled() || !WhatRemainsConfig.xpBottleRollTradeBottles) return;
        for (List<VillagerTrades.ItemListing> tier : event.getTrades().values()) {
            wrapAll(tier);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWandererTrades(WandererTradesEvent event) {
        if (!XpVessel.enabled() || !WhatRemainsConfig.xpBottleRollTradeBottles) return;
        wrapAll(event.getGenericTrades());
        wrapAll(event.getRareTrades());
    }

    /**
     * True when {@code stack} carries the {@value XpVessel#TAG_ORBS} key at all, regardless of
     * its contents — a presence check mirroring {@link XpVessel#hasStoredXpTag(ItemStack)}'s own
     * {@value XpVessel#TAG_STORED} check. Never reads the array's values.
     */
    private static boolean carriesOrbManifest(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(XpVessel.TAG_ORBS);
    }

    /**
     * Wraps each listing in place. A listing's output cannot be known without invoking it
     * (the interface is opaque), so every listing is wrapped and the wrapper passes
     * non-bottle offers through untouched. Idempotent: already-wrapped listings are kept.
     */
    private static void wrapAll(List<VillagerTrades.ItemListing> listings) {
        listings.replaceAll(listing ->
                listing instanceof RollingItemListing ? listing : new RollingItemListing(listing));
    }

    /**
     * Delegating listing that re-rolls any experience-bottle result: count forced to 1
     * (D10: sourced bottles always arrive as count-1 stacks) and a fresh
     * {@link XpVessel#rollSourceXp} whole-level XP threshold written into the tag.
     */
    private record RollingItemListing(VillagerTrades.ItemListing delegate)
            implements VillagerTrades.ItemListing {

        @Nullable
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource random) {
            MerchantOffer offer = delegate.getOffer(trader, random);
            if (offer == null) {
                return null;
            }

            ItemStack result = offer.getResult();
            if (!result.is(Items.EXPERIENCE_BOTTLE)) {
                return offer;
            }
            // A listing deliberately selling an already-resolved bottle (StoredXp tag present,
            // even if explicitly 0) is another mod's design decision — pass it through untouched.
            // V2 also honours the orb-manifest key on its own: a listing that shipped a StoredOrbs
            // array without a StoredXp scalar has still made a claim (XpVessel#getOrbManifest would
            // simply ignore that manifest until a scalar exists, but re-rolling here would still
            // clobber whatever the other mod set up before it gets the chance to backfill one).
            if (XpVessel.hasStoredXpTag(result) || carriesOrbManifest(result)) {
                return offer;
            }

            ItemStack rolled = new ItemStack(WRItems.XP_BOTTLE.get(), 1);
            int roll = XpVessel.rollSourceXp(random);
            XpVessel.setStoredXp(rolled, roll);

            // Round-trip through NBT to copy every offer field (uses, maxUses, rewardExp,
            // villager xp, priceMultiplier, specialPrice, demand) with only "sell" swapped —
            // the constructors expose no way to set rewardExp directly.
            CompoundTag tag = offer.createTag();
            tag.put("sell", rolled.save(new CompoundTag()));
            return new MerchantOffer(tag);
        }
    }
}
