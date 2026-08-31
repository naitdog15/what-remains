package com.temog.notminelegacy.whatremains.item;

import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The crafted XP vessel (XPB-06) — replaces the vanilla Bottle o' Enchanting as the item that
 * carries stored-XP behaviour. Extends vanilla's own {@link ExperienceBottleItem} rather than
 * plain {@code Item} so it inherits vanilla's throw/fill visuals and stats untouched; only the
 * two methods below are overridden, both plain Java (this is our own class, so no mixin is
 * needed the way the DNL Great Bottle needs one).
 * <p>
 * Unlike the retired V1 {@code whatremains:xp_bottle}, this reuses {@link XpVessel}'s tag key
 * (see that class's javadoc) so a legacy save adopts the revived item directly with no schema
 * conversion.
 */
public class XpBottleItem extends ExperienceBottleItem {

    public XpBottleItem(Properties properties) {
        super(properties);
    }

    /** Mirrors what {@code ExperienceBottleItemMixin} used to merge onto vanilla's item. */
    @Override
    public int getMaxStackSize(ItemStack stack) {
        if (!XpVessel.enabled()) return 64;
        return XpVessel.maxStackFor(stack);
    }

    /**
     * The gesture packet is the only path that may throw this item; vanilla's own throw is
     * retired outright, same rule {@code ExperienceBottleItemMixin} enforced for the vanilla item.
     * Master switch off: falls through to vanilla's own throw so the feature-off state stays a
     * real, usable bottle rather than a permanently inert one.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!XpVessel.enabled()) return super.use(level, player, hand);
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }
}
