package com.temog.notminelegacy.whatremains.events;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.registry.WRItems;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * XPB-06: adds the crafted {@code whatremains:xp_bottle} to the creative tab as a plain empty
 * entry — vanilla's own {@code minecraft:experience_bottle} is no longer touched here at all (it
 * goes back to 100% vanilla, creative tab included, now that it carries no vessel behaviour).
 * <p>
 * Deliberately NOT gated on {@link XpVessel#enabled()}: unlike the pre-XPB-06 version (which only
 * ever modified vanilla's own always-present entry, so a stale/pre-sync "disabled" reading simply
 * left that entry showing untouched), this item has no other path into creative/JEI — if this were
 * gated and {@code BuildCreativeModeTabContentsEvent} happened to fire before the SERVER-synced
 * master switch settles (e.g. the very first tab build, in the main menu before any world is
 * joined), the item would vanish from creative entirely. The item is a real, always-registered,
 * always-craftable item regardless of the switch (which only ever gates its vessel BEHAVIOUR, not
 * its existence — see {@link com.temog.notminelegacy.whatremains.item.XpBottleItem}), so it should
 * always be visible.
 * <p>
 * Pre-XPB-06 this class also REPLACED DNL's own untagged Great Bottle creative entry with a
 * synthetic full-capacity (level 50) preview stack, so browsing creative/JEI showed off the
 * mechanic at a glance. Removed by request: both vessels now show as the same plain, empty,
 * untagged item a player would actually craft or find — DNL already registers its own untagged
 * Great Bottle entry natively, so once this class stops replacing it there is nothing left for it
 * to do for that item at all.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class XpBottleCreativeTab {

    private XpBottleCreativeTab() {}

    @SubscribeEvent
    public static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            // Empty, untagged entry — a real craftable item now, not vanilla's always-present one.
            event.accept(WRItems.XP_BOTTLE.get());
        }
    }
}
