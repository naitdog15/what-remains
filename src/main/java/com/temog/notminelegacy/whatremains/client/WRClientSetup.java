package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.compat.dnl.DnlGreatBottleBridge;
import com.temog.notminelegacy.whatremains.registry.WREntities;
import com.temog.notminelegacy.whatremains.registry.WRItems;
import com.temog.notminelegacy.whatremains.registry.WRMenus;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Registers client-side renderers and item model predicates for What Remains.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WRClientSetup {

    public static final ModelLayerLocation CORPSE_FLESH_WIDE =
            new ModelLayerLocation(new ResourceLocation(WhatRemains.MODID, "corpse_flesh"), "wide");
    public static final ModelLayerLocation CORPSE_FLESH_SLIM =
            new ModelLayerLocation(new ResourceLocation(WhatRemains.MODID, "corpse_flesh"), "slim");

    private WRClientSetup() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WREntities.CORPSE.get(), CorpseRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CORPSE_FLESH_WIDE,
                () -> CorpseFleshModel.createBodyLayer(false));
        event.registerLayerDefinition(CORPSE_FLESH_SLIM,
                () -> CorpseFleshModel.createBodyLayer(true));
    }

    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resourceManager ->
                CorpseFleshBaker.getInstance().onResourceReload());
    }

    @SubscribeEvent
    @SuppressWarnings({"deprecation", "removal"})
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(WRMenus.CORPSE_MENU.get(), CorpseMenuScreen::new);

            // LB-03/XPB-06: fill-stage predicate driving the crafted xp_bottle's
            // model overrides. The lambda reads ONLY the stack — level and entity
            // are BOTH null in item-frame and dropped-item render contexts, so
            // neither may ever be dereferenced here.
            //
            // Master switch / stagedTextures gate off: always return 0f, so the model override
            // table's base entry (the plain vanilla-look model) is the only one ever selected.
            ItemProperties.register(
                WRItems.XP_BOTTLE.get(),
                new ResourceLocation(WhatRemains.MODID, "xp_fill"),
                (stack, level, entity, seed) ->
                    (WhatRemainsConfig.xpBottleEnabled && WhatRemainsConfig.xpBottleStagedTextures)
                        ? XpVessel.fillFraction(stack)
                        : 0.0F
            );

            // XPB-04: same fill-stage predicate, registered against DNL's own Great Bottle item —
            // resolved by registry lookup, a null result (DNL absent) simply skips registration.
            Item greatBottle = ForgeRegistries.ITEMS.getValue(
                    new ResourceLocation("dungeonnowloading", "great_experience_bottle"));
            if (greatBottle != null) {
                ItemProperties.register(
                    greatBottle,
                    new ResourceLocation(WhatRemains.MODID, "xp_fill"),
                    (stack, level, entity, seed) ->
                        (DnlGreatBottleBridge.isActive() && GreatBottleConfig.greatBottleStagedTextures)
                            ? XpVessel.fillFraction(stack)
                            : 0.0F
                );
            }
        });
    }

    /**
     * LB-03 V2: the top-left stored-level count badge (user ruling 2). {@link RegisterItemDecorationsEvent}
     * is its own MOD-bus event distinct from {@link FMLClientSetupEvent} — Forge dispatches subscriber
     * methods by parameter type, so this cannot be folded into {@link #onClientSetup}'s single method
     * body; it needs its own {@code @SubscribeEvent} method, same as that one already does for
     * {@code EntityRenderersEvent.RegisterRenderers} / {@code RegisterLayerDefinitions} above. Fires only
     * on the logical client, matching this class's {@code Dist.CLIENT} subscriber annotation.
     * <p>
     * The gate itself lives in {@link XpBottleCountBadge#render} (re-checked every frame, not just
     * here at registration), so this method only wires the decorator up.
     * <p>
     * XPB-03: also registers against DungeonNowLoading's Great Bottle item, resolved by registry
     * lookup — a null result (DNL absent) is simply skipped, no DNL class reference needed here.
     */
    @SubscribeEvent
    public static void registerItemDecorations(RegisterItemDecorationsEvent event) {
        event.register(WRItems.XP_BOTTLE.get(), new XpBottleCountBadge());

        Item greatBottle = ForgeRegistries.ITEMS.getValue(
                new ResourceLocation("dungeonnowloading", "great_experience_bottle"));
        if (greatBottle != null) {
            event.register(greatBottle, new XpBottleCountBadge());
        }
    }
}
