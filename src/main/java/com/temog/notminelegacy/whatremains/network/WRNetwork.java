package com.temog.notminelegacy.whatremains.network;

import com.temog.notminelegacy.network.ModChannel;
import com.temog.notminelegacy.whatremains.WhatRemains;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * What Remains' own network channel.
 *
 * <p>It carries WhatRemains' Bottle o' Enchanting gesture requests (LB-03 plan D6), including the
 * Great Bottle action set. These gestures cannot ride vanilla's use
 * packet, because that packet carries no modifier state and the
 * server's own {@code isShiftKeyDown} is one tick stale while it is being processed — a sneak
 * deposit issued on the arming tick would be read as the second click of a throw and empty the
 * bottle into the world. A packet that states the modifier explicitly removes the race outright.
 *
 * <p>Registration happens from {@link FMLCommonSetupEvent} through {@code enqueueWork} rather than
 * from the mod constructor, so the module's entry point needs no edit and message registration
 * still lands on the main thread.
 *
 * <p>Message ids are assigned positionally ({@code helper.nextId()}). Bump {@link #PROTOCOL_VERSION} whenever
 * the table below changes — an entry added, removed or reordered — so a mismatched client fails the
 * handshake loudly instead of silently mis-decoding one message id as another.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WRNetwork {

    private static final String PROTOCOL_VERSION = "2";

    private static final ModChannel MOD_CHANNEL = new ModChannel(WhatRemains.MODID, "main", PROTOCOL_VERSION);

    public static final SimpleChannel CHANNEL = MOD_CHANNEL.channel();

    private WRNetwork() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        MOD_CHANNEL.enqueueRegister(event, WRNetwork::register);
    }

    private static void register(ModChannel helper) {
        // Client-side gesture requests. Every field is a hint; each handler re-validates hand,
        // slot, item, and action against live server state.
        helper.channel().messageBuilder(ServerboundXpBottleGesture.class, helper.nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(ServerboundXpBottleGesture::decode)
                .encoder(ServerboundXpBottleGesture::encode)
                .consumerMainThread(ServerboundXpBottleGesture::handle)
                .add();

        helper.channel().messageBuilder(GreatBottleGesturePacket.class, helper.nextId(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(GreatBottleGesturePacket::decode)
                .encoder(GreatBottleGesturePacket::encode)
                .consumerMainThread(GreatBottleGesturePacket::handle)
                .add();
    }

    /** Convenience wrapper so callers do not have to reach through {@link #CHANNEL}. */
    public static void sendToServer(Object message) {
        MOD_CHANNEL.sendToServer(message);
    }
}
