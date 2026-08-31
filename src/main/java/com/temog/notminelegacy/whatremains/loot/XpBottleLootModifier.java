package com.temog.notminelegacy.whatremains.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.registry.WRItems;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;

/**
 * Per-source XP roll for loot-table Bottles o' Enchanting (LB-03 plan D10).
 * <p>
 * Post-processes generated loot: each unrolled {@code minecraft:experience_bottle} stack of
 * count N is replaced by N independent raw-XP rolls from {@link XpVessel#rollSourceXp}
 * (6&ndash;22 raw XP by default &mdash; double vanilla's own 3&ndash;11 throw default,
 * configurable via lootRollMin/lootRollMax). Every roll becomes its own count-1
 * {@code whatremains:xp_bottle} stack carrying that exact rolled XP amount (XPB-06: the OUTPUT
 * item, not vanilla's own experience_bottle &mdash; dungeon/structure loot keeps handing out an
 * already-filled, ready-to-use vessel; only self-serve deposit from a glass bottle is gated).
 * <p>
 * Bottles that already carry stored XP (pre-filled by another mod's loot function, or by an
 * earlier modifier) are passed through untouched &mdash; a {@code StoredXp} tag means the
 * bottle has already been rolled or deliberately filled.
 * <p>
 * Registered as {@code whatremains:xp_bottle_roller} (see {@link WRLootModifiers} and
 * {@code data/whatremains/loot_modifiers/xp_bottle_roller.json}).
 * <p>
 * V2: {@link XpVessel#setStoredXp} now also (re)writes the orb manifest on every call, so each
 * of the independently-rolled count-1 stacks below gets its own independently-shaped manifest for
 * free — nothing here reads or writes {@code StoredOrbs} directly, and none of that shape needs
 * seeding from this class's own {@code random} (the core accessor draws its own).
 */
public class XpBottleLootModifier extends LootModifier {

    public static final Codec<XpBottleLootModifier> CODEC =
            RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, XpBottleLootModifier::new));

    public XpBottleLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    @NotNull
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        // Master switch / rollLootBottles gate off: leave generated loot completely untouched —
        // an experience bottle from a loot table stays a plain, unrolled vanilla stack.
        if (!XpVessel.enabled() || !WhatRemainsConfig.xpBottleRollLootBottles) return generatedLoot;

        // Fast path — this modifier runs for every loot roll on the server (chests, mobs,
        // blocks, fishing), and almost none of them drop experience bottles.
        boolean hasBottle = false;
        for (ItemStack stack : generatedLoot) {
            if (isUnrolledBottle(stack)) {
                hasBottle = true;
                break;
            }
        }
        if (!hasBottle) {
            return generatedLoot;
        }

        RandomSource random = context.getRandom();
        ObjectArrayList<ItemStack> out = new ObjectArrayList<>(generatedLoot.size());
        for (ItemStack stack : generatedLoot) {
            if (!isUnrolledBottle(stack)) {
                out.add(stack);
                continue;
            }

            // Roll each of the N units independently (never one roll shared by a stack —
            // that is exploit E-01's dup state reached by normal play).
            for (int i = 0; i < stack.getCount(); i++) {
                int roll = XpVessel.rollSourceXp(random);
                ItemStack single = new ItemStack(WRItems.XP_BOTTLE.get(), 1);
                XpVessel.setStoredXp(single, roll);
                out.add(single);
            }
        }
        return out;
    }

    /**
     * An experience bottle with no {@code StoredXp} tag at all has never been rolled (D10
     * semantics). Checked by tag PRESENCE rather than {@code getStoredXp(stack) <= 0} (fix round
     * F7): a mod deliberately shipping a bottle explicitly tagged {@code StoredXp:0} has made a
     * claim too, and re-rolling it here would silently overwrite that mod's own decision.
     */
    private static boolean isUnrolledBottle(ItemStack stack) {
        return stack.is(Items.EXPERIENCE_BOTTLE) && !XpVessel.hasStoredXpTag(stack);
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
