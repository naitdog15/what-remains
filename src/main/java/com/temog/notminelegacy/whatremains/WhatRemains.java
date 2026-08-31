package com.temog.notminelegacy.whatremains;

import com.mojang.logging.LogUtils;
import com.temog.notminelegacy.whatremains.environment.CorpseEnvironmentLoader;
import com.temog.notminelegacy.whatremains.registry.WREntities;
import com.temog.notminelegacy.whatremains.registry.WRItems;
import com.temog.notminelegacy.whatremains.registry.WRMenus;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Entry point for <b>Not Mine Legacy: What Remains</b>.
 * <p>
 * A reimagined version of DrCyano's Lootable Bodies for 1.20.1.
 * When a player dies, their inventory is stored inside a skeleton corpse
 * entity. The corpse is invulnerable to all damage except being hit
 * twice with a shovel to "bury" and loot it.
 * <p>
 * Fully configurable: corpse HP, damage sources, decay, item damage on death,
 * inventory size, and more.
 */
@Mod(WhatRemains.MODID)
public class WhatRemains {

    public static final String MODID = "whatremains";
    private static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal")
    public WhatRemains() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        WREntities.register(modEventBus);
        WRItems.register(modEventBus);
        WRMenus.register(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, WhatRemainsConfig.SPEC, "whatremains.toml");
        // Fix round F6: the xpBottle* values must be visible to both client and server the same
        // way (they drive the item's model-stage predicate and max-stack rule), which a COMMON
        // config does not guarantee — only SERVER configs are synced to clients on login (Forge's
        // ConfigSync). See WhatRemainsConfig's class javadoc.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, WhatRemainsConfig.SERVER_SPEC, "whatremains-server.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, GreatBottleConfig.SPEC,
                "whatremains/dungeonnowloading-&-whatremains-great-bottle.toml");
        modEventBus.addListener(GreatBottleConfig::onConfigLoad);

        LOGGER.info("[WhatRemains] Initialized — death corpse system active");
    }

    /**
     * Registers the corpse-environment datapack registry ({@code data/whatremains/corpse_environment/*.json})
     * so it (re)builds on every server resource reload — same event/idiom BuildingBridges uses for
     * {@code EcosystemLoader}/{@code CollisionLoader}.
     */
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new CorpseEnvironmentLoader());
    }
}
