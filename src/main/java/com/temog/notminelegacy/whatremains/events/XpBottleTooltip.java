package com.temog.notminelegacy.whatremains.events;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Shows how much XP a Bottle o' Enchanting is carrying.
 *
 * <p>Only filled bottles get a line: an untagged bottle is indistinguishable from a vanilla one and
 * must read as vanilla, tooltip included.
 *
 * <p>V2 adds a second line giving the level-equivalent alongside the raw XP figure — the same number
 * {@link XpVessel#storedLevel(ItemStack)} draws on the count badge, so a player who opens the
 * tooltip can see exactly what the badge is counting rather than having to do the level math
 * themselves.
 *
 * <p>XPB-03: also serves the DungeonNowLoading Great Bottle, sharing these same lang keys and
 * lines — {@code capacity}/{@code capacityLevels} are already item-aware, so only the item check
 * and the activation gate need to dispatch by vessel.
 *
 * <p>XPB-04: also strips DNL's own stale native tooltip line off the Great Bottle whenever the
 * bridge is active — see {@link #DNL_NATIVE_TOOLTIP_KEY}.
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID)
public final class XpBottleTooltip {

    private XpBottleTooltip() {}

    private static final String STORED_KEY = "whatremains.xp_vessel.stored";

    /** V2: the badge-explaining level-equivalent line, shown under {@link #STORED_KEY}. */
    private static final String STORED_LEVEL_KEY = "whatremains.xp_vessel.stored_level";

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        boolean isNormalBottle = XpVessel.isXpBottle(stack);
        if (!isNormalBottle) return;

        boolean active = XpVessel.enabled() && WhatRemainsConfig.xpBottleTooltip;
        if (!active) return;

        int stored = XpVessel.getStoredXp(stack);
        if (stored <= 0) return;

        event.getToolTip().add(Component.translatable(STORED_KEY, stored, XpVessel.capacity(stack))
                .withStyle(ChatFormatting.GRAY));
        event.getToolTip().add(Component.translatable(STORED_LEVEL_KEY,
                        XpVessel.storedLevel(stack), XpVessel.capacityLevels(stack))
                .withStyle(ChatFormatting.GRAY));
    }
}
