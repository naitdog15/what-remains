package com.temog.notminelegacy.whatremains.mixin;

import com.temog.notminelegacy.whatremains.GreatBottleConfig;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.compat.dnl.DnlGreatBottleBridge;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws a cosmetic, vanilla-durability-style fill bar on a Bottle o' Enchanting or Great Bottle
 * carrying stored XP (LB-03 spec §6, item 6).
 *
 * <p>One {@code Item.class} mixin dispatching by item, mirroring the unified-across-vessels shape
 * {@code XpBottleCountBadge} and {@code XpBottleTooltip} already settled on post-XPB-04, rather than
 * two per-vessel mixins duplicating the same three injectors (the retired pre-refactor
 * {@code DnlGreatBottleBarMixin} did exactly that). {@code Item} is vanilla and always present
 * regardless of DNL/whatremains, and {@link Item#isBarVisible}/{@link Item#getBarWidth}/
 * {@link Item#getBarColor} are concrete methods {@code Item} itself declares (Forge's own patch,
 * not an inherited {@code IForgeItem} default) — a real {@code @Mixin(Item.class)} target, not the
 * "inherited from a parent" case Mixin 0.8.5 cannot resolve.
 *
 * <p><b>Hidden at full capacity, not just when empty.</b> {@code isBarVisible} gates on
 * {@link XpVessel#isPartial}, matching vanilla's own durability bar (hidden once undamaged/full) —
 * the pre-refactor version gated on {@code storedXp > 0} alone, which kept the bar showing even on
 * a maxed-out bottle.
 *
 * <p>The bar is purely cosmetic and never real durability: real damage would force
 * {@code stacksTo(1)} on a vanilla item, and the corpse's on-death {@code applyItemDamage} pass
 * would silently move the fill level.
 */
@Mixin(Item.class)
public abstract class ItemBarMixin {

    /** True when the concrete vessel item's own master switch and {@code fillBar} gate are both on. */
    private static boolean whatremains$barEnabled(ItemStack stack) {
        if (XpVessel.isXpBottle(stack)) {
            return XpVessel.enabled() && WhatRemainsConfig.xpBottleFillBar;
        }
        if (XpVessel.isGreatBottle(stack)) {
            return DnlGreatBottleBridge.isActive() && GreatBottleConfig.greatBottleFillBar;
        }
        return false;
    }

    @Inject(method = "isBarVisible", at = @At("HEAD"), cancellable = true)
    private void whatremains$barVisible(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!whatremains$barEnabled(stack)) return;
        cir.setReturnValue(XpVessel.isPartial(stack));
    }

    @Inject(method = "getBarWidth", at = @At("HEAD"), cancellable = true)
    private void whatremains$barWidth(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (!whatremains$barEnabled(stack)) return;
        cir.setReturnValue(XpVessel.barWidth(stack));
    }

    @Inject(method = "getBarColor", at = @At("HEAD"), cancellable = true)
    private void whatremains$barColor(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (!whatremains$barEnabled(stack)) return;
        cir.setReturnValue(XpVessel.barColor(stack));
    }
}
