package com.temog.notminelegacy.whatremains.compat.jade;

import com.mojang.authlib.GameProfile;
import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade tooltip for {@code whatremains:corpse} (LB-05) — owner, decay stage + remaining time,
 * a lootable-slot count once decay has actually locked some out, and stored XP.
 * <p>
 * <b>No {@code IServerDataProvider} here, unlike most cross-mod Jade bridges in this project.</b>
 * Everything read below (owner uuid/name, corpse age, stored XP, the locked-slot bit-masks
 * backing {@link CorpseEntity#isGeneralSlotLocked}) is already pushed to every nearby client via
 * {@code CorpseEntity#defineSynchedData} — see that class's synced-data section — so it's safe to
 * read directly off the client-side entity in {@link CorpseInfoProvider#appendTooltip}. Contrast
 * {@code BuildingBridgesJadePlugin}'s {@code BearTemperProvider}, which genuinely needs a server
 * round-trip because Naturalist's {@code Bear} doesn't sync its temper on its own.
 * <p>
 * <b>Item CONTENTS are deliberately not handled by this plugin at all.</b> Jade's own bundled
 * {@code UniversalPlugin} already registers {@code EntityItemStorageProvider} /
 * {@code ItemStorageProvider.INSTANCE} globally for every {@code Entity}/{@code Object} target,
 * and its target resolution ({@code CommonProxy#createItemCollector}, verified by decompiling
 * Jade 11.13.2's own classes — no source jar was available under {@code reference/}) falls
 * through to a generic {@code net.minecraft.world.Container} reader whenever the target isn't a
 * Forge {@code IItemHandler} capability provider and isn't an {@code AbstractChestedHorse}.
 * {@link CorpseEntity} IS a plain {@code Container} and is neither of those two exceptions, so it
 * already gets a native, duplicate-aggregating, row-count-configurable, chest-style item summary
 * for free, respecting the player's own Jade "storage" settings — the same rendering path a
 * vanilla chest uses. Registering a second {@code IServerExtensionProvider<ItemStack>} here would
 * at best be inert (Jade's own {@code Object.class}-scoped registration already matches
 * {@link CorpseEntity}) and at worst fight it for no benefit, so this was deliberately left out.
 * This has NOT been confirmed in-game (see the LB-05 handover) — only by reading Jade's bytecode.
 */
@WailaPlugin(WhatRemains.MODID)
public final class WhatRemainsJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(CorpseInfoProvider.INSTANCE, CorpseEntity.class);
    }

    private enum CorpseInfoProvider implements IEntityComponentProvider {
        INSTANCE;

        private static final ResourceLocation UID =
                ResourceLocation.fromNamespaceAndPath(WhatRemains.MODID, "corpse_info");

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            Entity entity = accessor.getEntity();
            if (!(entity instanceof CorpseEntity corpse)) return;

            // ── Owner (handle a null/absent profile — e.g. a corpse loaded from old data, or
            // one whose profile never resolved a name) gracefully rather than printing "null". ──
            GameProfile profile = corpse.getOwnerProfile();
            String ownerName = (profile != null) ? profile.getName() : null;
            if (ownerName != null && !ownerName.isEmpty()) {
                tooltip.add(Component.translatable("jade.whatremains.corpse.owner", ownerName)
                        .withStyle(ChatFormatting.GRAY));
            } else {
                tooltip.add(Component.translatable("jade.whatremains.corpse.owner_unknown")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }

            // ── Decay stage + remaining time until collapse. Note: DecayStage's own thresholds
            // (and therefore the stage shown here) derive from WhatRemainsConfig.decayLifespanTicks,
            // which is a COMMON (per-instance, never server-synced) config value — read locally on
            // whichever side calls it. That's a pre-existing property of DecayStage/CorpseEntity
            // shared by the client-side decay-overlay renderer, not something new introduced here;
            // it only matters if a client's local corpseDecay.decayLifespanDays setting genuinely
            // differs from the server's. ──
            DecayStage stage = corpse.getDecayStage();
            tooltip.add(Component.translatable("jade.whatremains.corpse.decay_stage",
                            Component.translatable(decayStageKey(stage)))
                    .withStyle(decayStageColor(stage)));

            int remaining = Math.max(0, DecayStage.getTotalLifespanTicks() - corpse.getCorpseAgeTicks());
            if (remaining <= 0) {
                tooltip.add(Component.translatable("jade.whatremains.corpse.collapsing")
                        .withStyle(ChatFormatting.RED));
            } else {
                tooltip.add(Component.translatable("jade.whatremains.corpse.time_left",
                                formatRemaining(remaining))
                        .withStyle(ChatFormatting.GRAY));
            }

            // ── Lootable (unlocked) general slots. Only worth a line once decay has actually
            // locked some out -- a FRESH corpse always reports 36/36, which would be pure noise.
            // isGeneralSlotLocked reads synced bit-mask fields (DATA_LOCKED_SLOTS_LO/HI), so this
            // is exact, not an approximation from the decay-stage target table. ──
            int activeGeneral = 0;
            for (int i = 0; i < CorpseEntity.GENERAL_SLOTS; i++) {
                if (!corpse.isGeneralSlotLocked(i)) activeGeneral++;
            }
            if (activeGeneral < CorpseEntity.GENERAL_SLOTS) {
                tooltip.add(Component.translatable("jade.whatremains.corpse.lootable_slots",
                                activeGeneral, CorpseEntity.GENERAL_SLOTS)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }

            // ── Stored XP (already synced — see class javadoc). ──
            int storedXp = corpse.getStoredXp();
            if (storedXp > 0) {
                tooltip.add(Component.translatable("jade.whatremains.corpse.stored_xp", storedXp)
                        .withStyle(ChatFormatting.GREEN));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public int getDefaultPriority() {
            return -4500;
        }

        private static String decayStageKey(DecayStage stage) {
            return switch (stage) {
                case FRESH -> "whatremains.decay_stage.fresh";
                case EARLY_DECAY -> "whatremains.decay_stage.early_decay";
                case MID_DECAY -> "whatremains.decay_stage.mid_decay";
                case LATE_DECAY -> "whatremains.decay_stage.late_decay";
                case ADVANCED_DECAY -> "whatremains.decay_stage.advanced_decay";
                case SKELETAL -> "whatremains.decay_stage.skeletal";
                case COLLAPSE -> "whatremains.decay_stage.collapse";
            };
        }

        private static ChatFormatting decayStageColor(DecayStage stage) {
            return switch (stage) {
                case FRESH -> ChatFormatting.GREEN;
                case EARLY_DECAY -> ChatFormatting.YELLOW;
                case MID_DECAY -> ChatFormatting.GOLD;
                case LATE_DECAY -> ChatFormatting.RED;
                case ADVANCED_DECAY, SKELETAL, COLLAPSE -> ChatFormatting.DARK_RED;
            };
        }

        /**
         * "3d 4h" / "4h 12m" / "12m" / "45s" — the unit letters come from the translatable
         * {@code jade.whatremains.corpse.time_*} format strings below, not hardcoded English, so
         * a translation can localize them like any other lang entry.
         */
        private static Component formatRemaining(int ticks) {
            long totalSeconds = ticks / 20L;
            long days = totalSeconds / 86400L;
            long hours = (totalSeconds % 86400L) / 3600L;
            long minutes = (totalSeconds % 3600L) / 60L;
            long seconds = totalSeconds % 60L;

            if (days > 0) {
                return Component.translatable("jade.whatremains.corpse.time_days", days, hours);
            } else if (hours > 0) {
                return Component.translatable("jade.whatremains.corpse.time_hours", hours, minutes);
            } else if (minutes > 0) {
                return Component.translatable("jade.whatremains.corpse.time_minutes", minutes);
            }
            return Component.translatable("jade.whatremains.corpse.time_seconds", seconds);
        }
    }
}
