package com.temog.notminelegacy.whatremains.enchantment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * LB-01. A 3-level enchantment, obtainable on any damageable item (weapon, tool, armor piece —
 * {@link EnchantmentCategory#BREAKABLE} is vanilla's own "has durability" category, matching the
 * TODO's "any enchantable-compatible item" scope exactly), that gives a per-level chance for the
 * enchanted item to stay in the dying player's inventory instead of transferring into their death
 * corpse.
 * <p>
 * This class only declares the enchantment's identity/table-availability. The actual roll (per
 * item, per death), the durability-parity guarantee, and the cross-respawn persistence live in
 * {@link com.temog.notminelegacy.whatremains.events.ItemRetentionHandler} — see that class's
 * javadoc for the exploit/duplication analysis (which also covers vanilla's Curse of Binding and
 * Curse of Vanishing, re-homed onto the same mechanism).
 */
public class SoulboundEnchantment extends Enchantment {

    /** Kept as a named constant rather than a literal: both this class and
     *  {@link com.temog.notminelegacy.whatremains.WhatRemainsConfig}'s {@code soulboundChancePerLevel}
     *  list are conceptually sized off this same number. */
    public static final int MAX_LEVEL = 3;

    public SoulboundEnchantment() {
        super(Rarity.RARE, EnchantmentCategory.BREAKABLE, new EquipmentSlot[] {
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET,
        });
    }

    @Override
    public int getMaxLevel() {
        return MAX_LEVEL;
    }

    /** Mirrors Mending's real vanilla RARE-tier pricing shape, scaled up per level so a level-3
     *  roll (the highest keep-chance) costs meaningfully more than a level-1 roll. */
    @Override
    public int getMinCost(int level) {
        return 10 + (level - 1) * 15;
    }

    @Override
    public int getMaxCost(int level) {
        return getMinCost(level) + 40;
    }

    @Override
    public boolean isTreasureOnly() {
        return false;
    }

    @Override
    public boolean isCurse() {
        return false;
    }

    @Override
    public boolean isAllowedOnBooks() {
        return true;
    }
}
