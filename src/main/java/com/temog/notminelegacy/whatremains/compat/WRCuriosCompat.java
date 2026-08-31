package com.temog.notminelegacy.whatremains.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import net.minecraftforge.common.util.LazyOptional;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Curios API interaction helper for the corpse system.
 * <p>
 * <b>This class directly references Curios API classes.</b>
 * It must ONLY be loaded (referenced) when Curios is present — callers
 * must guard with {@code ModCompat.isCuriosLoaded()} before invoking
 * any method here.
 */
public final class WRCuriosCompat {
    private WRCuriosCompat() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A captured curios slot entry: its type identifier, index within that type,
     * and the item found there.
     * <p>
     * The {@link #key()} method provides a stable composite key like "ring:0"
     * that uniquely identifies the slot independent of iteration order.
     */
    public record CuriosSlotEntry(String slotType, int indexInType, ItemStack item) {
        /** Stable composite key: "type:indexWithinType", e.g. "ring:0", "aether_pendant:0". */
        public String key() { return slotType + ":" + indexInType; }
    }

    /**
     * Collects ALL curios slots from the player (empty and non-empty),
     * preserving type identifiers and per-type indices.
     * <p>
     * Aether accessories are Curios-backed and will appear here with
     * slot types prefixed "aether_" (e.g. "aether_pendant", "aether_cape").
     *
     * @return ordered list of slot entries; empty list if Curios unavailable
     */
    public static List<CuriosSlotEntry> collectAllSlots(Player player) {
        List<CuriosSlotEntry> result = new ArrayList<>();

        LazyOptional<ICuriosItemHandler> lazyHandler;
        try {
            lazyHandler = CuriosApi.getCuriosInventory(player);
        } catch (Exception e) {
            LOGGER.error("[WhatRemains] Failed to get curios inventory for {}: {}",
                    player.getName().getString(), e.getMessage());
            return result;
        }

        if (lazyHandler == null || !lazyHandler.isPresent()) {
            return result;
        }

        ICuriosItemHandler handler = lazyHandler.resolve().orElse(null);
        if (handler == null) {
            return result;
        }
        var curiosMap = handler.getCurios();

        if (curiosMap == null || curiosMap.isEmpty()) {
            return result;
        }

        curiosMap.forEach((type, stacksHandler) -> {
            var stacks = stacksHandler.getStacks();
            int slotCount = stacks.getSlots();

            for (int i = 0; i < slotCount; i++) {
                ItemStack original = stacks.getStackInSlot(i);
                ItemStack copy = original.copy();
                result.add(new CuriosSlotEntry(type, i, copy));
            }
        });

        return result;
    }

    /**
     * Clears all curios slots on the player so the Curios death handler
     * has nothing left to drop.
     */
    public static void clearCurios(Player player) {
        try {
            CuriosApi.getCuriosInventory(player).ifPresent(handler -> {
                handler.getCurios().forEach((type, stacksHandler) -> {
                    var stacks = stacksHandler.getStacks();
                    for (int i = 0; i < stacks.getSlots(); i++) {
                        stacks.setStackInSlot(i, ItemStack.EMPTY);
                    }
                });
            });
        } catch (Exception e) {
            LOGGER.error("[WhatRemains] Failed to clear curios for {}: {}",
                    player.getName().getString(), e.getMessage());
        }
    }

    /**
     * Gets the atlas sprite icon ResourceLocation for a curios slot type.
     * Uses the Curios API to look up the registered icon.
     */
    public static ResourceLocation getSlotIcon(String type) {
        return CuriosApi.getSlotIcon(type);
    }
}
