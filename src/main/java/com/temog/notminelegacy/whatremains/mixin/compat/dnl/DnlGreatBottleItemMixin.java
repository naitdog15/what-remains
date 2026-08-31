package com.temog.notminelegacy.whatremains.mixin.compat.dnl;

import com.temog.notminelegacy.whatremains.compat.dnl.DnlGreatBottleBridge;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Item-side half of the DungeonNowLoading Great Bottle o' Enchanting bridge (LB-03 spec §6).
 *
 * <p>DungeonNowLoading is NOT a compile-time dependency (unlike Legendary Survival Overhaul — see
 * {@code build.gradle}: no {@code compileOnly fg.deobf(...)} entry for it), so this is a soft
 * {@code @Pseudo} target by source string. {@code remap = false} everywhere per the house
 * convention for mod-owned classes/members (CLAUDE.md's Mixin pitfalls section).
 *
 * <p>Mirrors {@code ExperienceBottleItemMixin} structurally (merged stack-size override + use()
 * lockout), delegating shared fill-state math to {@link XpVessel} and DNL-specific activation to
 * {@link DnlGreatBottleBridge}.
 *
 * <p>Illegal-stack repair is deliberately NOT a merged {@code inventoryTick} here — the Great
 * Bottle's repair now lives alongside the normal bottle's in {@code events.XpBottleNormalizer}, a
 * plain Forge event-tick class, for the same reason it was never a merged method there either:
 * {@code inventoryTick} is a Minecraft-owned name, and this project ships no refmap, so a merged
 * concrete method under that name silently never overrides {@code IForgeItem}'s default in a
 * packaged run. See {@code XpBottleNormalizer}'s javadoc.
 *
 * <p>Safe here without an extra presence gate: this mixin is merged only after
 * {@code WhatRemainsMixinPlugin} confirms DungeonNowLoading is present and ABI-compatible. The
 * runtime master switch is still re-checked on every invocation.
 */
@Pseudo
@Mixin(targets = "dev.hexnowloading.dungeonnowloading.item.GreatExperienceBottleItem", remap = false)
public abstract class DnlGreatBottleItemMixin {

    /**
     * Stack limit by fill state (spec §6, "same durability-stackability" as the vanilla bottle):
     * a plain merged concrete method, deliberately public and not {@code @Unique}, with the exact
     * Forge {@code IForgeItem} signature so it wins over the interface default through ordinary
     * virtual dispatch — the same dispatch proof whatremains' {@code ExperienceBottleItemMixin}
     * relies on (LB-03 plan D2: a public exact-signature non-{@code @Unique} merged method beats
     * {@code IForgeItem}'s default). Forge-owned method name, so it survives production untouched —
     * no remap concern for a plain (non-{@code @Shadow}/{@code @Inject}) merged method either way,
     * since remap only affects annotated selector strings, never a merged method's own signature.
     *
     * <p><b>Master-off returns DungeonNowLoading's own stock 64</b> — unconditionally, regardless
     * of any leftover {@code StoredXp} tag a stack might still carry from before the bridge was
     * switched off. {@code GreatBottleConfig.greatBottleEnabled = false} means "behaves exactly as DNL ships
     * it", and DNL's own item has no stack-size override of its own to fall back on, so 64 (the
     * vanilla {@code Item} default) is what "unmodified" means here.
     */
    public int getMaxStackSize(ItemStack stack) {
        if (!DnlGreatBottleBridge.isActive()) return 64;
        return XpVessel.maxStackFor(stack);
    }

    /**
     * Vanilla's throw is retired outright: {@code GreatBottleGesturePacket} (WhatRemains' own
     * gesture message) is the only path that may spawn the entity, matching whatremains' own
     * lockout for the vanilla bottle. All parameters/return type are vanilla ({@code Item#use}'s
     * own signature), so this compiles regardless of DNL's presence.
     *
     * <p><b>Master-off does NOT cancel.</b> If this stayed cancelled while
     * {@code GreatBottleConfig.greatBottleEnabled = false}, the Great Bottle would become permanently
     * unusable (WhatRemains' gesture handler also declines to send anything, and DNL's own
     * {@code use()} — the only other path that could ever throw it — is the very thing being
     * cancelled) — worse than if this bridge were never installed at all. Letting DNL's stock
     * {@code use()} run untouched is what "master off ⇒ behaves exactly as DNL ships it" requires.
     */
    // Production stores vanilla-overridden members under SRG names (javap-verified against the
    // pinned 2.2 jar); dev runtime-deobf presents official names. The two-name selector matches
    // whichever exists in the running environment — the injector's require is satisfied by either.
    @Inject(method = {"use", "m_7203_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void whatremains$blockVanillaThrow(Level level, Player player, InteractionHand hand,
            CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (!DnlGreatBottleBridge.isActive()) return;
        cir.setReturnValue(InteractionResultHolder.pass(player.getItemInHand(hand)));
    }
}
