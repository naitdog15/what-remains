package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.WhatRemains;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Render-frame upload budget and world-session cleanup for the dynamic flesh texture owner. */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class CorpseFleshClientEvents {

    private CorpseFleshClientEvents() {}

    @SubscribeEvent
    public static void onRenderFrame(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            CorpseFleshBaker.getInstance().onRenderFrame();
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CorpseFleshBaker.getInstance().onLogout();
    }
}
