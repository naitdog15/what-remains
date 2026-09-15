package com.temog.notminelegacy.whatremains.registry;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.enchantment.SoulboundEnchantment;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WREnchantments {

    private WREnchantments() {}

    public static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, WhatRemains.MODID);

    /**
     * LB-01: chance-per-level to keep an enchanted item out of the death corpse — see
     * {@link SoulboundEnchantment} and {@link com.temog.notminelegacy.whatremains.events.ItemRetentionHandler}.
     */
    public static final RegistryObject<SoulboundEnchantment> SOULBOUND =
            ENCHANTMENTS.register("soulbound", SoulboundEnchantment::new);

    public static void register(IEventBus bus) {
        ENCHANTMENTS.register(bus);
    }
}
