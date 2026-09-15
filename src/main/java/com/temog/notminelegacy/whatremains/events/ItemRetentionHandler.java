package com.temog.notminelegacy.whatremains.events;

import java.util.List;

import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.registry.WREnchantments;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;

/**
 * Decides whether one item survives death outside the corpse — LB-01's
 * {@code whatremains:soulbound} (chance-based keep, see {@link #rollKeep}) and vanilla's own Curse
 * of Vanishing (destroy outright, see {@link #isVanishing}), both re-homed onto this mod's corpse
 * system since it never runs vanilla's own drop/curse code path. Vanilla's Curse of Binding is
 * handled separately and does NOT go through this class at all — a bound armor piece transfers
 * into the corpse completely normally on death, and is instead locked in place once it's there
 * (see {@code CorpseMenu}'s armor {@code Slot}, and {@code CorpseEntity#removeItem}/
 * {@code #removeItemNoUpdate} for the hopper-extraction path). See
 * {@link PlayerDeathHandler#onPlayerDeath} for how Vanishing and a Soulbound roll interact
 * (Vanishing checked first — see that method's {@code resolveDeathOutcome}). {@link
 * PlayerDeathHandler} calls into this class for each item it would otherwise transfer into the
 * death corpse; a kept item is handed back once the player actually respawns via
 * {@link #onPlayerClone}.
 *
 * <p><b>Why a respawn hook is required at all.</b> A dead {@link Player} entity is discarded once
 * the player respawns — vanilla builds a brand-new {@code ServerPlayer} with a fresh, empty
 * inventory whenever {@code keepInventory} is false (see {@code Player#restoreFrom}), regardless
 * of what was left sitting in the dead entity's own inventory arrays at the moment of death.
 * Simply declining to clear a slot in {@link PlayerDeathHandler} would therefore NOT make the item
 * survive to respawn — it would instead hand vanilla's own post-event {@code Inventory#dropAll()}
 * something to spill on the ground at the death location, which is neither "kept" nor even
 * corpse-recoverable. The item is instead serialized into the dying player's own
 * {@link Player#getPersistentData()} — Forge's standard "survives death/dimension-change/logout"
 * NBT bag (saved to disk under the vanilla player-data {@code ForgeData} tag same as any other
 * persistent-data user) — and restored onto the new player object in
 * {@link PlayerEvent.Clone}, the one event Forge fires specifically to let mods carry state from
 * the old entity to its respawned replacement.
 *
 * <p><b>Exploit / duplication analysis (LB-01 requirement: "be careful for exploits and
 * duplication, they still lose the same durability" — carried over verbatim to Binding/Vanishing):</b>
 * <ul>
 *   <li><b>Duplication.</b> Every call site either (a) copies the stack into the corpse AND clears
 *   the live slot, (b) stashes the stack AND clears the live slot, or (c) for Vanishing, clears the
 *   live slot and writes the stack NOWHERE — never two of these for the same item, and never a
 *   stash without also clearing the source slot (so vanilla's post-event drop pass has nothing left
 *   to spill). {@link PlayerDeathHandler#onPlayerDeath} clears any leftover stash tag from a
 *   previous death before this death's decisions begin, so a death that never reaches
 *   {@link #onPlayerClone} (e.g. the entity is discarded some other way) cannot let a stale stash
 *   re-apply itself on a later, unrelated death.</li>
 *   <li><b>Durability-loss dodge.</b> A kept (Soulbound) item must lose exactly the durability it
 *   would have lost taking the corpse path, never less. Main-inventory items normally go through
 *   {@code CorpseEntity#vacuumItem}, which always applies {@code WhatRemainsConfig.itemDamageOnDeath}
 *   — so a kept main-inventory item gets that same {@code CorpseEntity#applyItemDamage} call
 *   explicitly (see {@code PlayerDeathHandler#onPlayerDeath}'s {@code KEEP} branch). Armor and the
 *   offhand slot are copied to the corpse WITHOUT that call even on the normal (non-kept) path, so
 *   a kept armor or offhand item correctly takes no extra damage either — the parity is "whatever
 *   the corpse path would have done", not "always damage it". A destroyed (Vanishing) item never
 *   reaches this concern at all — it simply ceases to exist, same as vanilla's own curse. Binding
 *   never goes through this class at all (see the class javadoc above), so it never bypasses this
 *   either — the piece takes whatever a normal corpse-bound item of its slot type would take.</li>
 *   <li><b>Level forging.</b> {@link #chanceForLevel} clamps the Soulbound level read off the stack
 *   to the configured list's own length, so an out-of-range level written by, say, a direct NBT
 *   edit reuses the highest configured chance rather than granting anything higher. Vanishing is a
 *   single-level vanilla curse (any level {@code > 0} counts), so there is no equivalent
 *   level-scaling surface to forge.</li>
 *   <li><b>Curse precedence.</b> Vanishing is checked before a Soulbound roll, matching vanilla's
 *   own precedence (vanilla destroys vanishing-cursed items before anything else about
 *   death-inventory handling runs) — an item NBT-forged with both is destroyed, not kept, closing
 *   off "stack every keep-on-death enchantment for a stronger guarantee" as a distinction without a
 *   difference.</li>
 *   <li><b>Occupied respawn slots.</b> If some other system (a starting-kit mod, a datapack) has
 *   already placed something in the exact armor/offhand slot a kept item wants back into,
 *   {@link #restoreOne} falls back to {@link ItemHandlerHelper#giveItemToPlayer} — inventory-add
 *   with a drop-at-feet overflow, never an overwrite.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "whatremains", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ItemRetentionHandler {

    private ItemRetentionHandler() {}

    private static final String KEEP_TAG = "whatremains:SoulboundKeep";
    private static final String ITEM_KEY = "Item";
    private static final String SLOT_KEY = "Slot";

    /**
     * Rolls whether {@code stack}'s Soulbound level (0 if none) saves it from the corpse this
     * death. Pure — never mutates {@code stack} or any state; callers decide what to do with the
     * result.
     */
    public static boolean rollKeep(ItemStack stack, RandomSource random) {
        if (!WhatRemainsConfig.enableSoulbound) return false;
        if (stack.isEmpty()) return false;

        int level = EnchantmentHelper.getItemEnchantmentLevel(WREnchantments.SOULBOUND.get(), stack);
        if (level <= 0) return false;

        return random.nextDouble() < chanceForLevel(level);
    }

    /** Clamps to the configured list's own length so a forged/out-of-range level can never exceed
     *  the highest chance an admin actually configured. */
    private static double chanceForLevel(int level) {
        List<? extends Double> chances = WhatRemainsConfig.soulboundChancePerLevel;
        if (chances.isEmpty()) return 0.0;

        int index = Math.max(0, Math.min(level - 1, chances.size() - 1));
        Double chance = chances.get(index);
        return chance == null ? 0.0 : Math.max(0.0, Math.min(1.0, chance));
    }

    /**
     * Vanilla Curse of Vanishing. Checked first by {@code PlayerDeathHandler} — a vanishing item is
     * destroyed outright regardless of any other enchantment on the same stack. Gated by
     * {@code enableCurseOfVanishingDestroy} so a server can restore stock (no special effect, i.e.
     * the item goes to the corpse like anything else) behaviour.
     */
    public static boolean isVanishing(ItemStack stack) {
        if (!WhatRemainsConfig.enableCurseOfVanishingDestroy) return false;
        if (stack.isEmpty()) return false;
        return EnchantmentHelper.getItemEnchantmentLevel(Enchantments.VANISHING_CURSE, stack) > 0;
    }

    /**
     * Clears any stash left over from an earlier death — see this class's javadoc on why that
     * matters. Call once, before the first {@link #stash} call of a given death.
     */
    public static void clearStash(Player player) {
        player.getPersistentData().remove(KEEP_TAG);
    }

    /** Records {@code stack} (already copied/damaged by the caller as appropriate) to be handed
     *  back to this player's respawned replacement, tagged with which slot it came from. */
    public static void stash(Player player, ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return;

        CompoundTag root = player.getPersistentData();
        ListTag list = root.contains(KEEP_TAG, Tag.TAG_LIST)
                ? root.getList(KEEP_TAG, Tag.TAG_COMPOUND)
                : new ListTag();

        CompoundTag entry = new CompoundTag();
        entry.put(ITEM_KEY, stack.save(new CompoundTag()));
        entry.putString(SLOT_KEY, slot == null ? "" : slot.getName());
        list.add(entry);

        root.put(KEEP_TAG, list);
    }

    /**
     * Hands stashed items from a dead player back to their respawned replacement.
     *
     * <p>{@code isWasDeath()} is false for a plain dimension change (e.g. Nether portal) — this
     * method returns immediately in that case, leaving any stash tag untouched for the eventual
     * real-death clone that actually wrote it.
     */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) return;

        Player oldPlayer = event.getOriginal();
        CompoundTag root = oldPlayer.getPersistentData();
        if (!root.contains(KEEP_TAG, Tag.TAG_LIST)) return;

        ListTag list = root.getList(KEEP_TAG, Tag.TAG_COMPOUND);
        root.remove(KEEP_TAG);
        if (list.isEmpty()) return;

        Player newPlayer = event.getEntity();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ItemStack stack = ItemStack.of(entry.getCompound(ITEM_KEY));
            if (stack.isEmpty()) continue;

            restoreOne(newPlayer, stack, entry.getString(SLOT_KEY));
        }
    }

    private static void restoreOne(Player player, ItemStack stack, String slotName) {
        // Regular main-inventory stacks are stashed with a blank slot name (see
        // PlayerDeathHandler) and fall straight through to the inventory-add path below.
        EquipmentSlot slot = equipmentSlotFor(slotName);
        if (slot != null && player.getItemBySlot(slot).isEmpty()) {
            player.setItemSlot(slot, stack);
            return;
        }
        // Also the fallback for a genuine equipment slot that's unexpectedly already occupied.
        ItemHandlerHelper.giveItemToPlayer(player, stack);
    }

    private static EquipmentSlot equipmentSlotFor(String name) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getName().equals(name)) return slot;
        }
        return null;
    }
}
