package com.temog.notminelegacy.whatremains.loot;

import com.temog.notminelegacy.whatremains.WhatRemains;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Registers the whatremains global-loot-modifier serializers.
 * <p>
 * NOTE: this intentionally deviates from the module's {@code DeferredRegister} house pattern
 * ({@link com.temog.notminelegacy.whatremains.registry.WRItems} etc.) — every existing
 * DeferredRegister here is subscribed from the {@code WhatRemains} constructor, which is
 * off-limits to this change set. A {@link RegisterEvent} handler on the MOD bus is the
 * annotation-driven equivalent and needs no constructor edit.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WRLootModifiers {

    /**
     * Registry id of {@link XpBottleLootModifier}. Must match both
     * {@code data/whatremains/loot_modifiers/xp_bottle_roller.json} and the entry in
     * {@code data/forge/loot_modifiers/global_loot_modifiers.json}.
     */
    public static final ResourceLocation XP_BOTTLE_ROLLER_ID =
            new ResourceLocation(WhatRemains.MODID, "xp_bottle_roller");

    private WRLootModifiers() {}

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                helper -> helper.register(XP_BOTTLE_ROLLER_ID, XpBottleLootModifier.CODEC));
    }
}
