package com.temog.notminelegacy.whatremains.menu;

import com.mojang.datafixers.util.Pair;
import com.temog.notminelegacy.compat.ModCompat;
import com.temog.notminelegacy.whatremains.compat.WRCuriosCompat;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.registry.WRMenus;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Corpse container menu with 4 armor + 1 offhand + 36 general + N curios slots.
 * <p>
 * Slot coordinates match the corpse_container.png (176×222) texture exactly.
 * The general inventory uses an irregular 6-row layout (4+4+5+5+9+9 = 36).
 * <ul>
 *   <li>0-3: Armor (helmet, chestplate, leggings, boots) — vertical column</li>
 *   <li>4: Offhand</li>
 *   <li>5-40: General inventory (irregular grid) — randomly lock over time</li>
 *   <li>41+: Curios/accessories (dynamic count) — never decay, shown in panel</li>
 * </ul>
 */
public class CorpseMenu extends AbstractContainerMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Container corpseContainer;
    private final CorpseEntity corpse;

    /** Armor slot empty-icon sprites indexed by container slot (0=head .. 3=feet). */
    private static final ResourceLocation[] ARMOR_SLOT_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
            InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
            InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
    };

    /** EquipmentSlot mapping for armour validation. */
    private static final EquipmentSlot[] ARMOR_EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    };

    /**
     * General inventory slot (x,y) positions, matching the texture layout exactly.
     * 36 slots: 4+4+5+5+9+9 irregular grid.
     */
    private static final int[][] GENERAL_SLOT_POSITIONS = {
            // Row A: 4 slots (to the right of preview area)
            {98, 18}, {116, 18}, {134, 18}, {152, 18},
            // Row B: 4 slots
            {98, 36}, {116, 36}, {134, 36}, {152, 36},
            // Row C: 5 slots (wider row)
            {80, 54}, {98, 54}, {116, 54}, {134, 54}, {152, 54},
            // Row D: 5 slots
            {80, 72}, {98, 72}, {116, 72}, {134, 72}, {152, 72},
            // Row E: full 9-wide row
            {8, 90}, {26, 90}, {44, 90}, {62, 90}, {80, 90}, {98, 90}, {116, 90}, {134, 90}, {152, 90},
            // Row F: full 9-wide row
            {8, 108}, {26, 108}, {44, 108}, {62, 108}, {80, 108}, {98, 108}, {116, 108}, {134, 108}, {152, 108},
    };

    /** Number of base corpse slots (armor + offhand + general). */
    private final int baseSlotCount;
    /** First menu slot index of curios. */
    private final int curiosMenuStart;
    /** Total corpse slots including curios. */
    private final int totalCorpseSlots;

    public CorpseMenu(int windowId, Inventory playerInv, CorpseEntity corpse) {
        super(WRMenus.CORPSE_MENU.get(), windowId);
        this.corpseContainer = corpse;
        this.corpse = corpse;
        this.baseSlotCount = CorpseEntity.BASE_SLOTS;

        corpseContainer.startOpen(playerInv.player);

        // ── Armor slots (0-3): vertical column at x=57 ─────────────────────
        for (int i = 0; i < CorpseEntity.ARMOR_SLOTS; i++) {
            final int armorIdx = i;
            final EquipmentSlot equipSlot = ARMOR_EQUIPMENT[i];
            Slot armorSlot = new Slot(corpseContainer, i, 57, 16 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    if (stack.getItem() instanceof Equipable eq) {
                        return eq.getEquipmentSlot() == equipSlot;
                    }
                    return false;
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, ARMOR_SLOT_ICONS[armorIdx]);
                }
            };
            this.addSlot(armorSlot);
        }

        // ── Offhand slot (4) ───────────────────────────────────────────────
        this.addSlot(new Slot(corpseContainer, CorpseEntity.OFFHAND_INDEX, 77, 27) {
            @Override
            public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
            }
        });

        // ── General inventory slots (36, irregular grid) ────────────────────
        for (int i = 0; i < CorpseEntity.GENERAL_SLOTS; i++) {
            int containerIdx = CorpseEntity.GENERAL_START + i;
            int x = GENERAL_SLOT_POSITIONS[i][0];
            int y = GENERAL_SLOT_POSITIONS[i][1];
            this.addSlot(new GeneralSlot(corpseContainer, corpse, containerIdx, x, y));
        }

        // ── Curios slots (dynamic, initially OFF-SCREEN) ────────────────────
        int curiosCount = corpse.getCuriosSize();
        LOGGER.debug("[WhatRemains] CorpseMenu: building with {} curios slots (base={})",
                curiosCount, this.baseSlotCount);
        this.curiosMenuStart = this.slots.size();
        for (int i = 0; i < curiosCount; i++) {
            final int ci = i;
            int containerIdx = CorpseEntity.BASE_SLOTS + i;
            Slot curiosSlot = new Slot(corpseContainer, containerIdx, -999, -999) {
                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    if (!ModCompat.isCuriosLoaded()) return null;
                    String type = corpse.getCuriosSlotType(ci);
                    if (!type.isEmpty()) {
                        ResourceLocation icon = getCuriosIcon(type);
                        if (icon != null) {
                            return Pair.of(InventoryMenu.BLOCK_ATLAS, icon);
                        }
                    }
                    return null;
                }
            };
            this.addSlot(curiosSlot);
        }
        this.totalCorpseSlots = this.slots.size();

        // ── Player inventory (3 rows × 9) ──────────────────────────────────
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9,
                        8 + col * 18, 140 + row * 18));
            }
        }

        // ── Player hotbar (1 row × 9) ──────────────────────────────────────
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 198));
        }
    }

    /** Index of the first curios Slot in this menu's slot list. */
    public int getCuriosMenuStart() { return curiosMenuStart; }
    /** Number of curios slots in this menu. */
    public int getCuriosCount() { return corpse.getCuriosSize(); }

    @Override
    public boolean stillValid(Player player) {
        return corpseContainer.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        corpseContainer.stopOpen(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return result;

        ItemStack slotStack = slot.getItem();
        result = slotStack.copy();

        if (index < totalCorpseSlots) {
            // Moving from corpse to player inventory
            if (!this.moveItemStackTo(slotStack, totalCorpseSlots, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Moving from player to corpse general slots only
            int generalMenuStart = CorpseEntity.ARMOR_SLOTS + 1; // after armor + offhand
            int generalMenuEnd = generalMenuStart + CorpseEntity.GENERAL_SLOTS;
            if (!this.moveItemStackTo(slotStack, generalMenuStart, generalMenuEnd, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (slotStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return result;
    }

    /** Access corpse entity from screen rendering code. */
    public CorpseEntity getCorpse() {
        return corpse;
    }

    // ── General slot that respects random decay locking ──────────────────────

    public static class GeneralSlot extends Slot {
        private final CorpseEntity corpse;

        public GeneralSlot(Container container, CorpseEntity corpse, int index, int x, int y) {
            super(container, index, x, y);
            this.corpse = corpse;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return corpse.isSlotActive(getContainerSlot());
        }

        @Override
        public boolean mayPickup(Player player) {
            return corpse.isSlotActive(getContainerSlot());
        }

        /** True if this slot is currently locked by decay. Used by screen rendering. */
        public boolean isLocked() {
            return !corpse.isSlotActive(getContainerSlot());
        }
    }

    // ── Curios icon helper (soft-dependency safe) ───────────────────────────

    static ResourceLocation getCuriosIcon(String type) {
        if (!ModCompat.isCuriosLoaded()) return null;
        return CuriosIconLookup.get(type);
    }

    private static final class CuriosIconLookup {
        static ResourceLocation get(String type) {
            return WRCuriosCompat.getSlotIcon(type);
        }
    }
}
