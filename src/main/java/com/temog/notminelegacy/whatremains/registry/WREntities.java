package com.temog.notminelegacy.whatremains.registry;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WREntities {

    private WREntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, WhatRemains.MODID);

    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE =
            ENTITY_TYPES.register("corpse", () ->
                    EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(1.4f, 0.4f)
                            .clientTrackingRange(64)
                    .updateInterval(3)
                    .setShouldReceiveVelocityUpdates(true)
                            .build("whatremains:corpse"));

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
    }

    @Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class AttributeRegistration {
        @SubscribeEvent
        public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
            event.put(CORPSE.get(), CorpseEntity.createCorpseAttributes().build());
        }
    }
}
