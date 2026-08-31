package com.temog.notminelegacy.whatremains;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import org.slf4j.Logger;

/**
 * Forge SERVER config for the DungeonNowLoading × WhatRemains Great Bottle o' Enchanting bridge
 * (LB-03 spec §6). Stored in
 * {@code config/whatremains/dungeonnowloading-&-whatremains-great-bottle.toml}. Registered in
 * {@link WhatRemains}.
 *
 * <p><b>SERVER, not COMMON.</b> Several values here are read
 * on BOTH sides: server-side for deposit/vacuum/throw math, client-side for the merged
 * {@code getMaxStackSize} stack-limit predicate, the count badge, and the gesture input-shape
 * gates ({@code doubleClickWindowTicks}, {@code requireDoubleClickToThrow}). A COMMON config is only
 * guaranteed local to whichever process reads it, so a client and server that silently disagreed on
 * any of these would desync exactly the state the whole stackability/gesture design exists to keep
 * consistent — the exact failure mode {@code WhatRemainsConfig}'s own class javadoc documents for
 * its (structurally identical) {@code xpBottleCapacity}, which is why that value lives in
 * {@code SERVER_SPEC} and is synced to clients on login, not in the plain {@code SPEC}. See that
 * file for the precedent.
 *
 * <p><b>Fully self-contained (2026-08-12 revision).</b> Earlier revisions read the normal vessel's
 * storage settings straight off {@code WhatRemainsConfig} so the two vessels couldn't drift. That
 * coupling is gone: this file owns its storage setting ({@code storeUntilPreviousLevel}) plus a set
 * of bridge-specific STATS/GATES ({@code capacityLevels}, {@code allowDeposit},
 * {@code throwPaysStoredXp}, {@code creativeFreeTopUp}, ...) that have no whatremains equivalent at
 * all. Two reasons this is the correct shape, not a regression:
 * <ol>
 *   <li>The MASTER {@code enabled} switch must be able to turn the whole bridge off without the
 *       bridge needing to reason about whatremains' config state at all — a config that reads
 *       another module's config even while switched off is not really "off".</li>
 *   <li>The Great Bottle (level 50 = 5,345 XP) and the normal Bottle o' Enchanting (level 25 =
 *       910 XP) are different-scale vessels — 2x apart in LEVELS but ~5.9x apart in XP, since
 *       levels are non-linear (V2 ruling 1) — so the Great Bottle needs its own capacity and stack
 *       limits rather than inheriting the normal vessel's values.</li>
 * </ol>
 * <p><b>{@code enabled} is a true master switch (2026-08-12 revision).</b> Earlier revisions stated
 * {@code enabled} "does NOT gate mixin application" — the stack-size/reload-fix mixins were
 * always structurally active whenever both mods were present. That is no longer the whole story.
 * The MIXINS are still unconditionally merged into their targets whenever both mods are present
 * (mixin application happens at classload, before any {@link ForgeConfigSpec} has loaded — same
 * restart-only-config-vs-early-mixin ordering documented for LSO's {@code lsoconfig} section in
 * {@code LsoBridgeConfig}), but their RUNTIME BODIES now read {@code enabled} on every call and, when
 * false, behave as if the bridge were never installed at all: {@code getMaxStackSize} returns
 * DungeonNowLoading's own stock 64, and the vanilla throw lockout does not cancel (DNL's own
 * {@code use()} runs untouched — fixed 100 XP, matching {@code fixDnlReloadBug}'s own hardcoded
 * default). The ONE exception is
 * {@link #FIX_DNL_RELOAD_BUG} — a correctness repair for a genuine DNL bug, not a Great Bottle
 * feature, so it is gated independently and stays active even with the master switch off.
 *
 * <p>Sections below: MASTER ({@code enabled}), STATS (numeric/behavioural tuning — capacity,
 * storage, stack sizes, throw/gesture timings), GATES (per-feature on/off switches).
 * Every STATS/GATES key nests under {@code [greatBottle.stats]} / {@code [greatBottle.gates]} in the
 * generated TOML; the outer {@code greatBottle} category and its bare {@code enabled} key are
 * unchanged from earlier revisions.
 */
public final class GreatBottleConfig {
    private GreatBottleConfig() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // ── MASTER ──────────────────────────────────────────────────────────────

    public static final ForgeConfigSpec.BooleanValue ENABLED;

    // ── STATS ───────────────────────────────────────────────────────────────

    public static final ForgeConfigSpec.IntValue CAPACITY_LEVELS;
    public static final ForgeConfigSpec.BooleanValue STORE_UNTIL_PREVIOUS_LEVEL;
    public static final ForgeConfigSpec.IntValue EMPTY_STACK_SIZE;
    public static final ForgeConfigSpec.IntValue FULL_STACK_SIZE;
    public static final ForgeConfigSpec.IntValue UNRESOLVED_THROW_ROLL_MIN;
    public static final ForgeConfigSpec.IntValue UNRESOLVED_THROW_ROLL_MAX;
    public static final ForgeConfigSpec.BooleanValue STAGED_TEXTURES;
    public static final ForgeConfigSpec.IntValue DOUBLE_CLICK_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue GESTURE_RATE_LIMIT_TICKS;
    public static final ForgeConfigSpec.DoubleValue ORB_VACUUM_RADIUS;

    // ── GATES ───────────────────────────────────────────────────────────────

    public static final ForgeConfigSpec.BooleanValue ALLOW_DEPOSIT;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ORB_VACUUM;
    public static final ForgeConfigSpec.BooleanValue THROW_PAYS_STORED_XP;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_DOUBLE_CLICK_TO_THROW;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_FREE_TOP_UP;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_THROW_PAYS_ZERO;
    public static final ForgeConfigSpec.BooleanValue TOOLTIP;
    public static final ForgeConfigSpec.BooleanValue FILL_BAR;
    public static final ForgeConfigSpec.BooleanValue FILL_BAR_HUE_RAMP;
    public static final ForgeConfigSpec.BooleanValue COUNT_BADGE;
    public static final ForgeConfigSpec.BooleanValue COUNT_BADGE_MARGINAL;
    public static final ForgeConfigSpec.BooleanValue NORMALIZE_ILLEGAL_STACKS;
    public static final ForgeConfigSpec.BooleanValue FIX_DNL_RELOAD_BUG;

    // ── Baked-default constants ─────────────────────────────────────────────

    private static final boolean DEFAULT_ENABLED = true;
    /** Fallback used if the spec ever bakes a non-positive level count. */
    public static final int DEFAULT_CAPACITY_LEVELS = 50;
    private static final boolean DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL = false;
    private static final int DEFAULT_EMPTY_STACK_SIZE = 64;
    private static final int DEFAULT_FULL_STACK_SIZE = 16;
    /** XPB-04: decuple of the normal bottle's doubled 6-22 raw-XP default (10x = 60-220). */
    private static final int DEFAULT_UNRESOLVED_THROW_ROLL_MIN = 60;
    private static final int DEFAULT_UNRESOLVED_THROW_ROLL_MAX = 220;
    private static final boolean DEFAULT_STAGED_TEXTURES = true;
    private static final int DEFAULT_DOUBLE_CLICK_WINDOW_TICKS = 10;
    private static final int DEFAULT_GESTURE_RATE_LIMIT_TICKS = 3;
    private static final double DEFAULT_ORB_VACUUM_RADIUS = 4.0;
    private static final boolean DEFAULT_ALLOW_DEPOSIT = true;
    private static final boolean DEFAULT_ALLOW_ORB_VACUUM = true;
    private static final boolean DEFAULT_THROW_PAYS_STORED_XP = true;
    private static final boolean DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW = false;
    private static final boolean DEFAULT_CREATIVE_FREE_TOP_UP = true;
    private static final boolean DEFAULT_CREATIVE_THROW_PAYS_ZERO = true;
    private static final boolean DEFAULT_TOOLTIP = true;
    private static final boolean DEFAULT_FILL_BAR = true;
    private static final boolean DEFAULT_FILL_BAR_HUE_RAMP = false;
    private static final boolean DEFAULT_COUNT_BADGE = true;
    private static final boolean DEFAULT_COUNT_BADGE_MARGINAL = true;
    private static final boolean DEFAULT_NORMALIZE_ILLEGAL_STACKS = true;
    private static final boolean DEFAULT_FIX_DNL_RELOAD_BUG = true;

    // ── Baked values (set on config load / reload) ──────────────────────────

    public static boolean greatBottleEnabled;

    public static int greatBottleCapacityLevels;
    public static boolean greatBottleStoreUntilPreviousLevel;
    public static int greatBottleEmptyStackSize;
    public static int greatBottleFullStackSize;
    /** Inclusive Great Drop roll bounds in raw XP. */
    public static int greatBottleUnresolvedThrowRollMin;
    public static int greatBottleUnresolvedThrowRollMax;
    public static boolean greatBottleStagedTextures;
    public static int greatBottleDoubleClickWindowTicks;
    public static int greatBottleGestureRateLimitTicks;
    public static double greatBottleOrbVacuumRadius;

    public static boolean greatBottleAllowDeposit;
    public static boolean greatBottleAllowOrbVacuum;
    public static boolean greatBottleThrowPaysStoredXp;
    public static boolean greatBottleRequireDoubleClickToThrow;
    public static boolean greatBottleCreativeFreeTopUp;
    public static boolean greatBottleCreativeThrowPaysZero;
    public static boolean greatBottleTooltip;
    public static boolean greatBottleFillBar;
    public static boolean greatBottleFillBarHueRamp;
    public static boolean greatBottleCountBadge;
    public static boolean greatBottleCountBadgeMarginal;
    public static boolean greatBottleNormalizeIllegalStacks;
    public static boolean greatBottleFixDnlReloadBug;

    static {
        BUILDER.comment("DungeonNowLoading Great Bottle o' Enchanting bridge (LB-03 spec section 6).",
                "Only takes effect when DungeonNowLoading is loaded alongside WhatRemains.")
                .push("greatBottle");

        ENABLED = BUILDER
                .comment("MASTER switch for the whole bridge.",
                        "true (default): the Great Bottle becomes a refillable XP vessel — deposit or vacuum",
                        "via sneak+use, cash out only by throwing (see requireDoubleClickToThrow),",
                        "with the STATS/GATES below controlling the details.",
                        "false: the Great Bottle behaves EXACTLY as DungeonNowLoading ships it — fixed 100 XP",
                        "throw, stacks to 64, no storage. None of the STATS/GATES below apply while this is",
                        "false. Does NOT gate fixDnlReloadBug (see GATES) — that is a bug repair, not a",
                        "feature, and stays active either way.")
                .define("enabled", DEFAULT_ENABLED);

        // ── STATS ───────────────────────────────────────────────────────────
        BUILDER.comment("Numeric and behavioural tuning. Ignored entirely while enabled=false.")
                .push("stats");

        CAPACITY_LEVELS = BUILDER
                .comment("Maximum stored XP a Great Bottle o' Enchanting can hold, denominated in LEVELS",
                        "(V2 ruling 1) rather than raw XP — the XP figure is derived from vanilla's own",
                        "level curve on read, never stored. Default 50 = 5,345 XP.",
                        "Levels are non-linear: the Great Bottle is 2x the normal bottle (level 25, see",
                        "WhatRemainsConfig's xpBottleCapacityLevels) in LEVELS, but ~5.9x the normal bottle",
                        "in XP (5,345 vs 910) — tune this by the XP figure, not just the level ratio.",
                        "Capped at 150: the orb manifest holds at most MAX_CHUNKS (32) chunks drawn from",
                        "vanilla's orb ladder (top rung 2477), so ~79,264 XP is the largest capacity the",
                        "manifest can represent, and level 150 (79,095 XP) is the largest level that fits.")
                .defineInRange("capacityLevels", DEFAULT_CAPACITY_LEVELS, 1, 150);

        STORE_UNTIL_PREVIOUS_LEVEL = BUILDER
                .comment("If true, depositing stores only as much XP from the player's XP bar as reaches",
                        "their previous level, so at most one level is deposited at a time.")
                .define("storeUntilPreviousLevel", DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL);

        EMPTY_STACK_SIZE = BUILDER
                .comment("Stack size for a Great Bottle carrying no stored XP (never deposited into).",
                        "DNL ships this as 64; kept the same by default.")
                .defineInRange("emptyStackSize", DEFAULT_EMPTY_STACK_SIZE, 1, 64);

        FULL_STACK_SIZE = BUILDER
                .comment("Stack size for a Great Bottle carrying ANY stored XP -- full or partial (XPB-07:",
                        "a partial Great Bottle used to cap at 1 regardless of this setting; now it shares",
                        "this limit, provided every unit in the stack holds the exact same XP value).")
                .defineInRange("fullStackSize", DEFAULT_FULL_STACK_SIZE, 1, 64);

        UNRESOLVED_THROW_ROLL_MIN = BUILDER
                .comment("Lower bound (inclusive), in RAW XP, of the Great Drop formula for a Great Bottle",
                        "with no stored-XP claim: random(unresolvedThrowRollMin, unresolvedThrowRollMax).",
                        "This is also the effective 'found from a boss/vault/dungeon source' pre-roll",
                        "(XPB-04) — DNL's own drop sources can't be enumerated, so resolution stays lazy,",
                        "at throw time, exactly as before. Default 60 is a decuple of the normal bottle's",
                        "own doubled 6-22 raw-XP default. Swapped with unresolvedThrowRollMax at load if",
                        "min > max.")
                .defineInRange("unresolvedThrowRollMin", DEFAULT_UNRESOLVED_THROW_ROLL_MIN, 0, 1024);

        UNRESOLVED_THROW_ROLL_MAX = BUILDER
                .comment("Upper bound (inclusive), in RAW XP, of the Great Drop formula for a Great Bottle",
                        "with no stored-XP claim: random(unresolvedThrowRollMin, unresolvedThrowRollMax).",
                        "A bottle carrying a claim pays that claim instead unless throwPaysStoredXp is off.",
                        "Default 220 is a decuple of the normal bottle's own doubled 6-22 raw-XP default.",
                        "Swapped with unresolvedThrowRollMin at load if min > max.")
                .defineInRange("unresolvedThrowRollMax", DEFAULT_UNRESOLVED_THROW_ROLL_MAX, 0, 1024);

        STAGED_TEXTURES = BUILDER
                .comment("If false, the Great Bottle's item model predicate always returns 0 — it always",
                        "renders as DNL's plain base model, regardless of fill level.")
                .define("stagedTextures", DEFAULT_STAGED_TEXTURES);

        DOUBLE_CLICK_WINDOW_TICKS = BUILDER
                .comment("How long (in client ticks) an armed first click stays live waiting for the second,",
                        "accident-guard-clearing click before a throw is allowed. Only relevant while",
                        "requireDoubleClickToThrow (see GATES) is on. 20 ticks = 1 second.")
                .defineInRange("doubleClickWindowTicks", DEFAULT_DOUBLE_CLICK_WINDOW_TICKS, 2, 60);

        GESTURE_RATE_LIMIT_TICKS = BUILDER
                .comment("Minimum ticks that must elapse between two ACCEPTED gestures from the same player,",
                        "enforced server-side. An honest client's own click-window guard already throttles it;",
                        "this defends against a modified/replaying client sending gestures far faster than any",
                        "button press could produce.")
                .defineInRange("gestureRateLimitTicks", DEFAULT_GESTURE_RATE_LIMIT_TICKS, 0, 40);

        ORB_VACUUM_RADIUS = BUILDER
                .comment("Reach, in blocks, of the sneak+use orb vacuum (LB-03 V2.1 spec): sneaking on a",
                        "not-yet-full Great Bottle pulls nearby ExperienceOrb entities into the bottle",
                        "instead of the player's own XP bar. Clamped to vanilla's own orb-follow radius",
                        "(ExperienceOrb#scanForEntities calls level.getNearestPlayer(this, 8.0D)) — reaching",
                        "further than that would only grab orbs that were never within vanilla's own homing",
                        "range of the player to begin with. Ignored entirely while allowOrbVacuum (GATES) is off.")
                .defineInRange("orbVacuumRadius", DEFAULT_ORB_VACUUM_RADIUS, 1.0, 8.0);

        BUILDER.pop();

        // ── GATES ───────────────────────────────────────────────────────────
        BUILDER.comment("Per-feature on/off switches. Ignored entirely while enabled=false, except",
                "fixDnlReloadBug, which is a bug repair rather than a feature and always applies.")
                .push("gates");

        ALLOW_DEPOSIT = BUILDER
                .comment("If false, sneak+use on a not-yet-full Great Bottle is refused (a rejection sound",
                        "plays, nothing else happens) instead of depositing XP.")
                .define("allowDeposit", DEFAULT_ALLOW_DEPOSIT);

        ALLOW_ORB_VACUUM = BUILDER
                .comment("If true (default), sneak+use on a not-yet-full Great Bottle with at least one",
                        "capturable ExperienceOrb nearby (orbVacuumRadius, see STATS) pulls those orbs into",
                        "the bottle instead of draining the player's own XP bar — orbs take priority; the",
                        "player's bar is only drained as the fallback once nothing nearby is small enough to",
                        "fit in the bottle's remaining room. Unlike the vanilla Bottle o' Enchanting, a Great",
                        "Bottle can never be CREATED by this feature: it is DungeonNowLoading's own item,",
                        "obtained from DNL content rather than transformed from a plain glass bottle, so the",
                        "vacuum only ever tops up a Great Bottle the player already has in hand. A FULL Great",
                        "Bottle is unaffected either way — sneak+use is refused because throwing is the only",
                        "way to cash out stored XP.",
                        "If false, sneak+use falls back to depositing from the player's own bar; a full bottle",
                        "is still refused.")
                .define("allowOrbVacuum", DEFAULT_ALLOW_ORB_VACUUM);

        THROW_PAYS_STORED_XP = BUILDER
                .comment("If true, throwing a Great Bottle that carries a stored-XP claim pays that claim.",
                        "If false, every throw pays a fresh Great Drop roll from unresolvedThrowRollMin..",
                        "unresolvedThrowRollMax regardless of what is stored — the",
                        "stored claim is left untouched on the item (this only narrows the PAYOUT, it does",
                        "not strip deposits).")
                .define("throwPaysStoredXp", DEFAULT_THROW_PAYS_STORED_XP);

        REQUIRE_DOUBLE_CLICK_TO_THROW = BUILDER
                .comment("If true, throwing requires the vanilla-style accident guard: a first click arms",
                        "within doubleClickWindowTicks, and only a genuine second click (button released and",
                        "pressed again) actually throws. If false, a single non-sneak click throws immediately",
                        "— still routed through the bridge's own packet, so server-side validation is unchanged.",
                        "Default false: a single click throws exactly like the vanilla Bottle o' Enchanting.")
                .define("requireDoubleClickToThrow", DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW);

        CREATIVE_FREE_TOP_UP = BUILDER
                .comment("If true, a creative-mode player depositing into a Great Bottle tops it up to full for",
                        "free (no XP is drawn from the player). If false, creative deposits draw XP exactly",
                        "like survival deposits.")
                .define("creativeFreeTopUp", DEFAULT_CREATIVE_FREE_TOP_UP);

        CREATIVE_THROW_PAYS_ZERO = BUILDER
                .comment("If true, a creative-mode player throwing a Great Bottle pays 0 XP into the world",
                        "(the item is free to have in creative, so its contents should be too). If false,",
                        "creative throws resolve their payout exactly like a survival throw.")
                .define("creativeThrowPaysZero", DEFAULT_CREATIVE_THROW_PAYS_ZERO);

        TOOLTIP = BUILDER
                .comment("If true (default), a Great Bottle carrying stored XP shows its exact stored XP",
                        "and level-equivalent amounts, each alongside the configured capacity, in its tooltip.",
                        "If false, no stored-XP tooltip lines are added for it.")
                .define("tooltip", DEFAULT_TOOLTIP);

        FILL_BAR = BUILDER
                .comment("If true (default), a Great Bottle carrying SOME but not all of its capacity draws",
                        "a cosmetic durability-style fill bar under the item icon, exactly like a vanilla",
                        "durability item — hidden again once the bottle is full (or empty), matching",
                        "vanilla's own 'no bar when undamaged' convention. Purely cosmetic: never real",
                        "item durability.")
                .define("fillBar", DEFAULT_FILL_BAR);

        FILL_BAR_HUE_RAMP = BUILDER
                .comment("If true, the fill bar sweeps red (near-empty) to green (near-full), vanilla-",
                        "durability style. If false (default), it stays a fixed bright XP-orb green",
                        "regardless of fill level. Ignored while fillBar is false.")
                .define("fillBarHueRamp", DEFAULT_FILL_BAR_HUE_RAMP);

        COUNT_BADGE = BUILDER
                .comment("If true (default), a Great Bottle carrying stored XP draws a small level-equivalent",
                        "blocky-digit badge in the item's top-left corner (V2 ruling 2).",
                        "If false, no badge is drawn for it.")
                .define("countBadge", DEFAULT_COUNT_BADGE);

        COUNT_BADGE_MARGINAL = BUILDER
                .comment("If true (default), the count badge shows the MARGINAL levels the viewing player",
                        "would actually gain by drinking this bottle right now, computed from the player's",
                        "real current XP position on vanilla's level curve — not the bottle's intrinsic",
                        "level-from-zero figure (which is always >= this). If false, falls back to the",
                        "intrinsic figure (XpVessel#storedLevel), matching pre-XPB-04 behaviour.")
                .define("countBadgeMarginal", DEFAULT_COUNT_BADGE_MARGINAL);

        NORMALIZE_ILLEGAL_STACKS = BUILDER
                .comment("If true, XpBottleNormalizer's periodic non-destructive repair of oversized",
                        "Great Bottle stacks (count exceeding the vessel's current stack-size config; see",
                        "that class's javadoc for the threat model) runs. Leave this on unless you have a",
                        "specific reason to disable the repair net.")
                .define("normalizeIllegalStacks", DEFAULT_NORMALIZE_ILLEGAL_STACKS);

        FIX_DNL_RELOAD_BUG = BUILDER
                .comment("If true, repairs DungeonNowLoading's own GreatExperienceBottleEntity.readAdditionalSaveData,",
                        "which reads a missing StoredExperience tag as 0 instead of leaving the entity's",
                        "just-synced 100 stock default alone — so a reloaded bottle entity that predates the",
                        "field, or one from a bare /summon, silently becomes a dud 0-XP bottle instead of paying",
                        "DNL's stock yield. This is a bug repair, not a WhatRemains design decision — it",
                        "applies regardless of the master enabled switch above, and exists as a toggle only in",
                        "case a future DNL release fixes it upstream, at which point this can be turned off to",
                        "avoid a redundant (harmless) double-repair. Note this flag is independent of enabled, but",
                        "the mixin carrying the repair is gated by WhatRemains' mixin plugin and is only",
                        "applied when dungeonnowloading is installed at the supported ABI version.")
                .define("fixDnlReloadBug", DEFAULT_FIX_DNL_RELOAD_BUG);

        BUILDER.pop();
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    /**
     * Mirrors {@code XpVessel#experienceForLevel} — vanilla's own total-XP-for-level curve —
     * purely so the diagnostic below can report a derived XP figure. Diagnostic-only: it plays no
     * part in the actual storage economy, which lives in {@code XpVessel}.
     */
    private static long experienceForLevel(int level) {
        if (level <= 0) return 0L;
        if (level <= 15) return progressionSum(level, 7L, 2L);
        if (level <= 30) return 315L + progressionSum(level - 15, 37L, 5L);
        return 1395L + progressionSum(level - 30, 112L, 9L);
    }

    private static long progressionSum(long n, long first, long step) {
        return n * (2L * first + (n - 1L) * step) / 2L;
    }

    /**
     * Bake diagnostic — this spec had none before, unlike {@code WhatRemainsConfig}'s own
     * SERVER-spec bake log (see that class, ~lines 395-444) for the established pattern this
     * mirrors. Fires on load AND reload so "did this bake?" is a one-grep question instead of a
     * debugging session. Wired onto the mod event bus manually in {@link WhatRemains}' constructor,
     * right after this spec's {@code registerConfig} call — the same way
     * {@code TaxTreeNaturalizer}'s own {@code ModConfigEvent} listener is wired, rather than via a
     * class-level {@code @Mod.EventBusSubscriber}. Log line deliberately contains the literal
     * substring "XP vessel:" so it matches the same one-grep diagnostic as whatremains' own vessel.
     */
    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) {
            // Forge has already nulled ConfigValue data at this point, so this branch must never
            // call .get(). Reset the master switch fail-closed; every other baked field returns to
            // its compiled default, including the independently gated DNL reload-bug repair.
            if (event.getConfig().getSpec() == SPEC) {
                greatBottleEnabled = false;

                greatBottleCapacityLevels          = DEFAULT_CAPACITY_LEVELS;
                greatBottleStoreUntilPreviousLevel = DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL;
                greatBottleEmptyStackSize          = DEFAULT_EMPTY_STACK_SIZE;
                greatBottleFullStackSize           = DEFAULT_FULL_STACK_SIZE;
                greatBottleUnresolvedThrowRollMin  = DEFAULT_UNRESOLVED_THROW_ROLL_MIN;
                greatBottleUnresolvedThrowRollMax  = DEFAULT_UNRESOLVED_THROW_ROLL_MAX;
                greatBottleStagedTextures          = DEFAULT_STAGED_TEXTURES;
                greatBottleDoubleClickWindowTicks  = DEFAULT_DOUBLE_CLICK_WINDOW_TICKS;
                greatBottleGestureRateLimitTicks   = DEFAULT_GESTURE_RATE_LIMIT_TICKS;
                greatBottleOrbVacuumRadius         = DEFAULT_ORB_VACUUM_RADIUS;

                greatBottleAllowDeposit              = DEFAULT_ALLOW_DEPOSIT;
                greatBottleAllowOrbVacuum             = DEFAULT_ALLOW_ORB_VACUUM;
                greatBottleThrowPaysStoredXp          = DEFAULT_THROW_PAYS_STORED_XP;
                greatBottleRequireDoubleClickToThrow  = DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW;
                greatBottleCreativeFreeTopUp          = DEFAULT_CREATIVE_FREE_TOP_UP;
                greatBottleCreativeThrowPaysZero      = DEFAULT_CREATIVE_THROW_PAYS_ZERO;
                greatBottleTooltip                    = DEFAULT_TOOLTIP;
                greatBottleFillBar                    = DEFAULT_FILL_BAR;
                greatBottleFillBarHueRamp             = DEFAULT_FILL_BAR_HUE_RAMP;
                greatBottleCountBadge                 = DEFAULT_COUNT_BADGE;
                greatBottleCountBadgeMarginal          = DEFAULT_COUNT_BADGE_MARGINAL;
                greatBottleNormalizeIllegalStacks     = DEFAULT_NORMALIZE_ILLEGAL_STACKS;
                greatBottleFixDnlReloadBug             = DEFAULT_FIX_DNL_RELOAD_BUG;

                LOGGER.info("[WhatRemains] XP vessel: Great Bottle UNLOADED — reset to fail-closed defaults");
            }
            return;
        }
        if (event.getConfig().getSpec() != SPEC) return;

        greatBottleEnabled                 = ENABLED.get();

        greatBottleCapacityLevels          = CAPACITY_LEVELS.get();
        greatBottleStoreUntilPreviousLevel = STORE_UNTIL_PREVIOUS_LEVEL.get();
        greatBottleEmptyStackSize          = EMPTY_STACK_SIZE.get();
        greatBottleFullStackSize           = FULL_STACK_SIZE.get();

        int unresolvedThrowRollMin = UNRESOLVED_THROW_ROLL_MIN.get();
        int unresolvedThrowRollMax = UNRESOLVED_THROW_ROLL_MAX.get();
        greatBottleUnresolvedThrowRollMin = Math.min(unresolvedThrowRollMin, unresolvedThrowRollMax);
        greatBottleUnresolvedThrowRollMax = Math.max(unresolvedThrowRollMin, unresolvedThrowRollMax);

        greatBottleStagedTextures          = STAGED_TEXTURES.get();
        greatBottleDoubleClickWindowTicks  = DOUBLE_CLICK_WINDOW_TICKS.get();
        greatBottleGestureRateLimitTicks   = GESTURE_RATE_LIMIT_TICKS.get();
        greatBottleOrbVacuumRadius         = ORB_VACUUM_RADIUS.get();

        greatBottleAllowDeposit              = ALLOW_DEPOSIT.get();
        greatBottleAllowOrbVacuum             = ALLOW_ORB_VACUUM.get();
        greatBottleThrowPaysStoredXp          = THROW_PAYS_STORED_XP.get();
        greatBottleRequireDoubleClickToThrow  = REQUIRE_DOUBLE_CLICK_TO_THROW.get();
        greatBottleCreativeFreeTopUp          = CREATIVE_FREE_TOP_UP.get();
        greatBottleCreativeThrowPaysZero      = CREATIVE_THROW_PAYS_ZERO.get();
        greatBottleTooltip                    = TOOLTIP.get();
        greatBottleFillBar                    = FILL_BAR.get();
        greatBottleFillBarHueRamp             = FILL_BAR_HUE_RAMP.get();
        greatBottleCountBadge                 = COUNT_BADGE.get();
        greatBottleCountBadgeMarginal          = COUNT_BADGE_MARGINAL.get();
        greatBottleNormalizeIllegalStacks     = NORMALIZE_ILLEGAL_STACKS.get();
        greatBottleFixDnlReloadBug             = FIX_DNL_RELOAD_BUG.get();

        if (greatBottleEnabled) {
            LOGGER.info("[WhatRemains] XP vessel: Great Bottle ENABLED (capacity level {} = {} XP, stacks empty {} / stored (any value) {})",
                    greatBottleCapacityLevels, experienceForLevel(greatBottleCapacityLevels),
                    greatBottleEmptyStackSize, greatBottleFullStackSize);
        } else {
            LOGGER.info("[WhatRemains] XP vessel: Great Bottle DISABLED (stock DNL bottle)");
        }
    }
}
