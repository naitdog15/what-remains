package com.temog.notminelegacy.whatremains.registry;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.item.XpBottleItem;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WRItems {

    private WRItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, WhatRemains.MODID);

    /**
     * XPB-06: revives the retired V1 {@code whatremains:xp_bottle} id as the real, crafted XP
     * vessel — see {@link XpBottleItem}. Reviving (rather than picking a new id) means a legacy
     * V1 stack in an old save resolves straight to this item on load, no migration code needed
     * (see {@code XpVessel}'s class javadoc on the shared {@code StoredXp} tag key).
     */
    public static final RegistryObject<Item> XP_BOTTLE =
            ITEMS.register("xp_bottle", () -> new XpBottleItem(new Item.Properties()));

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
