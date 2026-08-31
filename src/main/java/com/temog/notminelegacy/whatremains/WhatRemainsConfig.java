package com.temog.notminelegacy.whatremains;

import java.util.List;

import com.mojang.logging.LogUtils;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import org.slf4j.Logger;

/**
 * Forge config for the What Remains corpse system.
 * <p>
 * All values are hot-reloadable via {@link ModConfigEvent}.
 * <p>
 * <b>Two specs, two sync guarantees (fix round F6).</b> {@link #SPEC} is
 * {@code ModConfig.Type.COMMON} — per-instance, never synced to a joining client by Forge's
 * {@code ConfigSync}. {@link #SERVER_SPEC} is {@code ModConfig.Type.SERVER} — per-world and
 * synced on login. The full {@code xpBottle*} configuration surface (master switch, stats, and
 * feature gates) lives in {@link #SERVER_SPEC} <em>because</em> it must: the item's model-stage
 * predicate and its max-stack rule are both derived from it on the client, so a client and server
 * that silently disagreed (COMMON configs are only guaranteed local to whichever process reads
 * them) would desync exactly the state the whole stackability design (LB-03 plan D1/D2) exists to
 * keep consistent. Every other value here only ever matters server-side, so it stays COMMON.
 * <p>
 * Both specs are registered from {@code WhatRemains.java}'s constructor. A single
 * {@link #onLoad(ModConfigEvent)} subscriber handles both — Forge fires one {@link ModConfigEvent}
 * per spec per mod, so the handler discriminates by {@link ModConfigEvent#getConfig()}'s spec
 * identity (the same pattern as {@code TaxTreeNaturalizer#onConfigReloading}).
 */
@Mod.EventBusSubscriber(modid = WhatRemains.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WhatRemainsConfig {

    private WhatRemainsConfig() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ── General ─────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.IntValue ITEM_DAMAGE_ON_DEATH;
    private static final ForgeConfigSpec.DoubleValue CORPSE_HP;
    private static final ForgeConfigSpec.BooleanValue ADD_BONES_TO_CORPSE;
    private static final ForgeConfigSpec.IntValue CORPSE_INVENTORY_SIZE;
    private static final ForgeConfigSpec.IntValue SHOVEL_HITS_TO_BURY;

    // ── Damage-source whitelist ─────────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue HURT_BY_ALL;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_FIRE;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_EXPLOSIONS;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_FALL;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_CACTUS;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_WEAPONS;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_SUFFOCATION;
    private static final ForgeConfigSpec.BooleanValue HURT_BY_OTHER;

    // ── Decay ───────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.BooleanValue ENABLE_DECAY;
    private static final ForgeConfigSpec.BooleanValue DECAY_ONLY_WHEN_EMPTY;
    private static final ForgeConfigSpec.IntValue DECAY_TIME_SECONDS;
    private static final ForgeConfigSpec.DoubleValue DECAY_LIFESPAN_DAYS;
    private static final ForgeConfigSpec.DoubleValue FLY_DECAY_BONUS_PER_FLY;
    private static final ForgeConfigSpec.DoubleValue VULTURE_DECAY_BONUS_PER_VULTURE;
    private static final ForgeConfigSpec.BooleanValue DEBUG_FLIES_IGNORE_DECAY_STAGE;
    private static final ForgeConfigSpec.BooleanValue DEBUG_VULTURES_IGNORE_DECAY_STAGE;

    // ── Environmental exposure (char/frost/waterlogged trackers + temperature decay rate) ──
    private static final ForgeConfigSpec.DoubleValue DECAY_NEUTRAL_TEMPERATURE;
    private static final ForgeConfigSpec.DoubleValue DECAY_COLD_REFERENCE_TEMPERATURE;
    private static final ForgeConfigSpec.DoubleValue DECAY_COLD_RATE_MULTIPLIER;
    private static final ForgeConfigSpec.DoubleValue DECAY_HOT_REFERENCE_TEMPERATURE;
    private static final ForgeConfigSpec.DoubleValue DECAY_HOT_RATE_MULTIPLIER;
    private static final ForgeConfigSpec.DoubleValue DECAY_NO_SKY_RATE_MULTIPLIER;

    // ── Corpse vulture feeding (BuildingBridges vulture-corpse bridge, if installed) ────────
    //
    // These knobs describe what a feeding vulture does TO THE CORPSE -- whatremains' own
    // mechanic -- so they live here rather than in BuildingBridges' VultureConfig, exactly like
    // flyDecayBonusPerFly above (the corpse-side knob for the corpse-fly bridge). whatremains
    // must never depend on buildingbridges (CLAUDE.md's module-dependency switchboard rule):
    // CorpseEntity is whatremains' central class and loads whenever the module loads, so a hard
    // import of a BuildingBridges class here would throw NoClassDefFoundError on corpse classload
    // the moment BuildingBridges is absent. BuildingBridges' VultureConfig keeps only the BIRD-
    // behaviour knobs (circling, flush radii, Nether gate, timing) that only its own goal classes
    // ever read -- see VultureConfig's class javadoc for the mirrored explanation.

    private static final ForgeConfigSpec.BooleanValue ENABLE_VULTURE_CORPSE_FEEDING;
    private static final ForgeConfigSpec.IntValue VULTURE_MAX_FEEDERS_PER_CORPSE;
    private static final ForgeConfigSpec.DoubleValue VULTURE_FOOD_CONSUME_CHANCE;
    private static final ForgeConfigSpec.IntValue VULTURE_MAX_FOOD_ITEMS_PER_CORPSE;
    private static final ForgeConfigSpec.DoubleValue VULTURE_GORE_DROP_CHANCE;
    private static final ForgeConfigSpec.IntValue VULTURE_MAX_GORE_DROPS_PER_CORPSE;
    private static final ForgeConfigSpec.DoubleValue VULTURE_FEEDER_LEASH_RANGE;

    // ── Vacuum ──────────────────────────────────────────────────────────────

    private static final ForgeConfigSpec.IntValue VACUUM_RADIUS;
    private static final ForgeConfigSpec.IntValue VACUUM_TICKS;

    // ── Death XP penalty (XPB-08, tiered die-roll redesign XPB-09) ──────────

    private static final ForgeConfigSpec.IntValue DEATH_XP_MAX_LEVEL;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Double>> DEATH_XP_FACE_RETENTION_AT_LEVEL_0;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Double>> DEATH_XP_FACE_RETENTION_AT_MAX_LEVEL;
    private static final ForgeConfigSpec.DoubleValue DEATH_XP_LUCK_DICE_WEIGHT;
    private static final ForgeConfigSpec.IntValue DEATH_XP_LUCK_DICE_CAP;

    // ── Corpse XP recovery (vacuum + destruction payout) ────────────────────

    private static final ForgeConfigSpec.IntValue CORPSE_VACUUM_XP_DRAIN_PER_ACTION;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Double>> CORPSE_DESTRUCTION_FACE_RETENTION_AT_LEVEL_0;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Double>> CORPSE_DESTRUCTION_FACE_RETENTION_AT_MAX_LEVEL;

    static {
        BUILDER.push("general");

        ITEM_DAMAGE_ON_DEATH = BUILDER
                .comment("Durability damage applied to damageable items on death (0 = none). Items won't break.")
                .defineInRange("itemDamageOnDeath", 32, 0, 1000);

        CORPSE_HP = BUILDER
                .comment("Hit-points of the corpse entity (10 hearts = 20 HP).")
                .defineInRange("corpseHP", 50.0, 1.0, (double) Short.MAX_VALUE);

        ADD_BONES_TO_CORPSE = BUILDER
                .comment("If true, bones and rotten flesh are added to the corpse inventory.")
                .define("addBonesToCorpse", true);

        CORPSE_INVENTORY_SIZE = BUILDER
                .comment("Maximum number of item stacks the corpse can hold (54 = double-chest).")
                .defineInRange("corpseInventorySize", 54, 9, Short.MAX_VALUE / 2);

        SHOVEL_HITS_TO_BURY = BUILDER
                .comment("Number of shovel hits required to bury (destroy) the corpse.")
                .defineInRange("shovelHitsToBury", 2, 1, 100);

        BUILDER.pop();

        // ── Damage sources ──────────────────────────────────────────────────
        BUILDER.push("corpseDamage");

        HURT_BY_ALL = BUILDER
                .comment("If true, the corpse takes damage from any source.")
                .define("hurtByAll", false);
        HURT_BY_FIRE = BUILDER
                .comment("If true, the corpse takes fire/lava damage.")
                .define("hurtByFire", false);
        HURT_BY_EXPLOSIONS = BUILDER
                .comment("If true, the corpse takes explosion damage (creepers, TNT).")
                .define("hurtByExplosions", false);
        HURT_BY_FALL = BUILDER
                .comment("If true, the corpse takes fall damage.")
                .define("hurtByFall", false);
        HURT_BY_CACTUS = BUILDER
                .comment("If true, the corpse takes cactus damage.")
                .define("hurtByCactus", false);
        HURT_BY_WEAPONS = BUILDER
                .comment("If true, the corpse takes damage from player/mob attacks.")
                .define("hurtByWeapons", false);
        HURT_BY_SUFFOCATION = BUILDER
                .comment("If true, the corpse takes suffocation damage from blocks.")
                .define("hurtBySuffocation", false);
        HURT_BY_OTHER = BUILDER
                .comment("If true, the corpse takes damage from uncategorized sources.")
                .define("hurtByOther", false);

        BUILDER.pop();

        // ── Decay ───────────────────────────────────────────────────────────
        BUILDER.push("corpseDecay");

        ENABLE_DECAY = BUILDER
                .comment("If true, corpses self-destruct after a time period.")
                .define("enableDecay", false);
        DECAY_ONLY_WHEN_EMPTY = BUILDER
                .comment("If true (and enableDecay is true), corpses only decay while empty.")
                .define("decayOnlyWhenEmpty", false);
        DECAY_TIME_SECONDS = BUILDER
                .comment("Seconds after death before a corpse decays (min 2).")
                .defineInRange("decayTimeSeconds", 3600, 2, Integer.MAX_VALUE / 20);
        DECAY_LIFESPAN_DAYS = BUILDER
                .comment("Number of Minecraft days (fractional allowed) for the full corpse decay lifecycle "
                        + "(visual stages + collapse), at the neutral-temperature/sky-visible baseline (1.0x "
                        + "decay-rate multiplier) with no flies landed. 2.5 days = 50 real minutes at 1.0x; a "
                        + "Plains-biome corpse (~1.09x) collapses in ~46 real minutes, skeletal at ~39 minutes.")
                .defineInRange("decayLifespanDays", 2.5, 0.05, 365.0);
        FLY_DECAY_BONUS_PER_FLY = BUILDER
                .comment("Extra decay-speed multiplier added per fly currently landed on this corpse "
                        + "(BuildingBridges' corpse-fly bridge, if installed). 0 disables the effect. "
                        + "E.g. 0.25 with 3 flies landed on the corpse at once = 1.75x normal decay speed.")
                .defineInRange("flyDecayBonusPerFly", 0.25, 0.0, 5.0);
        VULTURE_DECAY_BONUS_PER_VULTURE = BUILDER
                .comment("Extra decay-speed multiplier added per vulture currently feeding on this corpse "
                        + "(BuildingBridges' vulture-corpse bridge, if installed). A feeding vulture tears into "
                        + "the body far more aggressively than a fly landing on it, so this is a much bigger "
                        + "per-instance bonus than flyDecayBonusPerFly. 0 disables the effect. "
                        + "E.g. 1.5 with 1 vulture feeding = 2.5x normal decay speed.")
                .defineInRange("vultureDecayBonusPerVulture", 1.5, 0.0, 20.0);
        DEBUG_FLIES_IGNORE_DECAY_STAGE = BUILDER
                .comment("TESTING ONLY. If true, BuildingBridges' corpse-fly bridge (if installed) treats every "
                        + "corpse as maximally attractive/fly-spawning regardless of decay stage -- a FRESH corpse "
                        + "behaves like MID_DECAY instead of having zero attraction. Lets you test fly attraction/"
                        + "landing immediately without waiting for a corpse to actually decay. Leave false for "
                        + "normal play -- it removes the 'only decomposing bodies attract flies' distinction entirely.")
                .define("debugFliesIgnoreDecayStage", false);
        DEBUG_VULTURES_IGNORE_DECAY_STAGE = BUILDER
                .comment("TESTING ONLY. If true, BuildingBridges' vulture-corpse bridge (if installed) treats a "
                        + "FRESH corpse as already feedable, so vultures will descend and land on a body the "
                        + "instant it appears instead of circling it until it starts decomposing. Normal play "
                        + "requires EARLY_DECAY..ADVANCED_DECAY, which at the default lifespan means roughly the "
                        + "first few real-time minutes after death are circling-only by design -- this flag exists "
                        + "so landing/feeding can be tested without that wait. SKELETAL and COLLAPSE are still "
                        + "excluded even with this on: there is genuinely nothing left to feed on. Leave false for "
                        + "normal play.")
                .define("debugVulturesIgnoreDecayStage", false);

        DECAY_NEUTRAL_TEMPERATURE = BUILDER
                .comment("Biome base temperature (Biome#getBaseTemperature()) at which the decay-rate",
                        "multiplier is exactly 1.0x -- roughly forest/plains. Below this, decay slows",
                        "toward decayColdRateMultiplier at decayColdReferenceTemperature; above it, decay",
                        "speeds up toward decayHotRateMultiplier at decayHotReferenceTemperature.")
                .defineInRange("decayNeutralTemperature", 0.6, -5.0, 5.0);
        DECAY_COLD_REFERENCE_TEMPERATURE = BUILDER
                .comment("Biome base temperature (snowy/taiga-like) at which the cold-side decay-rate",
                        "multiplier bottoms out at decayColdRateMultiplier. Temperatures below this still",
                        "clamp to decayColdRateMultiplier -- it's a floor, not a cliff.")
                .defineInRange("decayColdReferenceTemperature", -0.5, -5.0, 5.0);
        DECAY_COLD_RATE_MULTIPLIER = BUILDER
                .comment("Decay-rate multiplier at or below decayColdReferenceTemperature (placeholder default -- expect balance tuning later).")
                .defineInRange("decayColdRateMultiplier", 0.35, 0.01, 10.0);
        DECAY_HOT_REFERENCE_TEMPERATURE = BUILDER
                .comment("Biome base temperature (desert/nether-like) at which the hot-side decay-rate",
                        "multiplier caps out at decayHotRateMultiplier. Temperatures above this still",
                        "clamp to decayHotRateMultiplier -- it's a ceiling, not a cliff.")
                .defineInRange("decayHotReferenceTemperature", 2.0, -5.0, 5.0);
        DECAY_HOT_RATE_MULTIPLIER = BUILDER
                .comment("Decay-rate multiplier at or above decayHotReferenceTemperature (placeholder default -- expect balance tuning later).")
                .defineInRange("decayHotRateMultiplier", 1.6, 0.01, 10.0);
        DECAY_NO_SKY_RATE_MULTIPLIER = BUILDER
                .comment("Extra decay-rate multiplier applied when the corpse cannot see the sky",
                        "(Level#canSeeSky), stacking MULTIPLICATIVELY with the temperature-derived",
                        "multiplier above. The Nether's permanent 'no sky' therefore reads as an extra",
                        "slowdown on top of its already-hot biome temperature -- not specially cased.")
                .defineInRange("decayNoSkyRateMultiplier", 0.85, 0.01, 10.0);

        BUILDER.pop();

        // ── Corpse vulture feeding ────────────────────────────────────────────
        BUILDER.push("corpseVulture");

        ENABLE_VULTURE_CORPSE_FEEDING = BUILDER
                .comment("The CORPSE-SIDE master gate on this corpse ever being fed on by a vulture of "
                        + "either species (BuildingBridges' vulture-corpse bridge, if installed). This is "
                        + "one of TWO deliberately separate layers (plan section 9b): this switch governs "
                        + "what happens TO THE CORPSE -- when false, CorpseEntity#reserveFeedingSlot always "
                        + "refuses a lease (so no feeding-slot reservation is ever granted), the decay-rate "
                        + "term this corpse's own tick adds per feeding vulture contributes zero, and "
                        + "consumeCarrionFood/dropVultureGore both refuse. BuildingBridges' OWN "
                        + "VultureConfig.enableCorpseFeeding is the separate BIRD-SIDE gate on whether a "
                        + "vulture ever ATTEMPTS the feeding behaviour at all (it may still circle and land "
                        + "near the corpse regardless of either switch -- that pacing lives entirely in "
                        + "VultureConfig). Turning off only one switch still leaves the other layer's effects "
                        + "possible: a bird can still circle/land with this OFF and BuildingBridges' switch ON "
                        + "(it just never gets to actually feed), and a bird cannot even attempt to feed with "
                        + "BuildingBridges' switch OFF regardless of this one. Turn off BOTH for a full "
                        + "disable. Mirrors flyDecayBonusPerFly's split above: this is what happens to the "
                        + "corpse, not bird behaviour.")
                .define("enableVultureCorpseFeeding", true);
        VULTURE_MAX_FEEDERS_PER_CORPSE = BUILDER
                .comment("Hard cap on simultaneous feeding-slot reservations this corpse will grant, "
                        + "across both vulture species combined. 0 disables vulture feeding on this corpse "
                        + "entirely -- CorpseEntity#reserveFeedingSlot always fails, though circling and "
                        + "landing (BuildingBridges-side pacing) are unaffected. 3 = default.")
                .defineInRange("vultureMaxFeedersPerCorpse", 3, 0, 16);
        VULTURE_FOOD_CONSUME_CHANCE = BUILDER
                .comment("Chance (0.0-1.0), rolled once per completed feeding cycle, that this corpse lets "
                        + "a feeding vulture consume one tagged food item from its container. A failed roll "
                        + "still lets the cycle advance decay -- only the consumption itself is skipped. "
                        + "Eligible items are tag-only (raw + cooked meat and fish) drawn only from active "
                        + "general slots 5..40 -- armor, offhand and every Curios slot are never touched. "
                        + "0.40 = default.")
                .defineInRange("vultureFoodConsumeChance", 0.40, 0.0, 1.0);
        VULTURE_MAX_FOOD_ITEMS_PER_CORPSE = BUILDER
                .comment("Hard cap on food items this corpse will ever let a vulture consume from its "
                        + "container over its entire lifetime -- a persistent counter that survives a world "
                        + "reload. 0 disables food consumption on this corpse entirely; decay acceleration "
                        + "and gore drops still occur normally. 8 = default.")
                .defineInRange("vultureMaxFoodItemsPerCorpse", 8, 0, 64);
        VULTURE_GORE_DROP_CHANCE = BUILDER
                .comment("Chance (0.0-1.0), rolled once per completed feeding cycle, that this corpse "
                        + "spawns one rotten_flesh gore item beside itself. Spawned with a short pickup "
                        + "delay and an offset outside a vulture's own one-block pickup sweep so it doesn't "
                        + "immediately vanish back into the feeding bird's hand. Bounded overall by "
                        + "vultureMaxGoreDropsPerCorpse -- this chance alone does not limit total drops from "
                        + "a long feeding session. 0.25 = default.")
                .defineInRange("vultureGoreDropChance", 0.25, 0.0, 1.0);
        VULTURE_MAX_GORE_DROPS_PER_CORPSE = BUILDER
                .comment("Hard cap on rotten_flesh gore drops this corpse will ever spawn over its entire "
                        + "lifetime -- a persistent counter that survives a world reload, preventing an "
                        + "uncapped feeding session from becoming an infinite rotten-flesh farm. "
                        + "0 disables gore drops on this corpse entirely. 6 = default.")
                .defineInRange("vultureMaxGoreDropsPerCorpse", 6, 0, 64);
        VULTURE_FEEDER_LEASH_RANGE = BUILDER
                .comment("How far (in blocks) a feeding vulture may drift from this corpse before its "
                        + "feeding-slot reservation is pruned by the corpse's own tick -- the backstop that "
                        + "makes a leaked lease impossible even though a goal's stop() is not guaranteed to "
                        + "run on death, removal or chunk unload. 32.0 = default (matches BuildingBridges' "
                        + "own default corpseDetectRange, though the two are independently configurable). "
                        + "Floor raised from 1.0 to 8.0 (defect fix): a vulture that has reserved a slot but "
                        + "is still descending toward the corpse -- not yet perched -- can legitimately be "
                        + "several blocks away for the several ticks descent takes. A leash shorter than that "
                        + "prunes the very lease it just reserved before it ever lands, producing an endless "
                        + "reserve/abort cycle that never actually feeds. 8.0 is comfortably larger than any "
                        + "sane per-tick descent distance while still pruning a genuinely abandoned lease "
                        + "quickly.")
                .defineInRange("vultureFeederLeashRange", 32.0, 8.0, 128.0);

        BUILDER.pop();

        // ── Vacuum ──────────────────────────────────────────────────────────
        BUILDER.push("vacuum");

        VACUUM_RADIUS = BUILDER
                .comment("Block radius for vacuuming loose item drops into the corpse after spawning.")
                .defineInRange("vacuumRadius", 3, 0, 16);
        VACUUM_TICKS = BUILDER
                .comment("Number of ticks after death to keep vacuuming nearby items.")
                .defineInRange("vacuumTicks", 20, 0, 200);

        BUILDER.pop();

        // ── Death XP penalty ──────────────────────────────────────────────
        BUILDER.push("deathXpPenalty");

        DEATH_XP_MAX_LEVEL = BUILDER
                .comment("Level at which the death-XP-recovery die below reaches its harshest faces.",
                        "Below this level every face's retention interpolates linearly from its",
                        "level-0 value; at or above it, the maxLevel value applies unchanged.")
                .defineInRange("maxLevel", 60, 1, 1000);
        DEATH_XP_FACE_RETENTION_AT_LEVEL_0 = BUILDER
                .comment("A dying player's death-XP recovery is a single roll of a die whose face",
                        "count is however many entries this list has (default 6, like a d6) -- a",
                        "discrete, predictable set of outcomes rather than a continuous gamble. Each",
                        "entry is the FRACTION of total XP that face keeps (0..1); whatever a face",
                        "doesn't keep is destroyed permanently, never recoverable by any means. This",
                        "list is the face values at level 0, ordered best-to-worst -- must have the",
                        "same length as faceRetentionAtMaxLevel.")
                .defineList("faceRetentionAtLevel0",
                        List.of(0.44, 0.38, 0.31, 0.25, 0.20, 0.15),
                        o -> o instanceof Double d && d >= 0.0D && d <= 1.0D);
        DEATH_XP_FACE_RETENTION_AT_MAX_LEVEL = BUILDER
                .comment("Same die faces as faceRetentionAtLevel0, but their values at maxLevel and",
                        "above. Deliberately keeps a real floor on the worst face (default 0.07, not",
                        "some arbitrarily tiny leftover amount) -- even the worst possible roll at the",
                        "harshest level still guarantees a meaningful fraction of your XP.")
                .defineList("faceRetentionAtMaxLevel",
                        List.of(0.20, 0.18, 0.15, 0.13, 0.10, 0.07),
                        o -> o instanceof Double d && d >= 0.0D && d <= 1.0D);
        DEATH_XP_LUCK_DICE_WEIGHT = BUILDER
                .comment("Extra dice rolled per point of the dying player's vanilla luck attribute",
                        "(LivingEntity#getLuck -- fed by the vanilla Luck/Bad Luck potion effects,",
                        "+-1 per amplifier level). Positive luck rolls extra dice and keeps the BEST",
                        "face among them (advantage); negative luck rolls extra dice and keeps the",
                        "WORST (disadvantage). Extra dice are rounded and capped by luckDiceCap.")
                .defineInRange("luckDiceWeight", 1.0, 0.0, 10.0);
        DEATH_XP_LUCK_DICE_CAP = BUILDER
                .comment("Maximum extra dice luck can add in either direction, regardless of how",
                        "strong the luck attribute is (e.g. from an admin /attribute command).")
                .defineInRange("luckDiceCap", 4, 0, 20);

        BUILDER.pop();

        // ── Corpse XP recovery ───────────────────────────────────────────────
        BUILDER.push("corpseXpRecovery");

        CORPSE_VACUUM_XP_DRAIN_PER_ACTION = BUILDER
                .comment("Max XP a single SNEAK + secondary-interact with a non-full xp_bottle drains",
                        "from a corpse per action. Draining a large stored balance therefore takes",
                        "several sneak-clicks by design, same as the in-world orb vacuum's own gradual",
                        "feel -- also bounded by the bottle's own remaining room either way.")
                .defineInRange("vacuumDrainPerAction", 200, 1, 100000);
        CORPSE_DESTRUCTION_FACE_RETENTION_AT_LEVEL_0 = BUILDER
                .comment("When a corpse is destroyed (shovel-buried, decays out, or collapses) with XP",
                        "still stored in it, whatever's left rolls ONE MORE TIME through the same",
                        "die-roll mechanism as the original death penalty (deathXpPenalty above) --",
                        "but against THIS more forgiving curve rather than that harsher one, since the",
                        "player already paid the harsher cost once at death. Survivors drop as real",
                        "XP orbs at the corpse; whatever the roll doesn't keep is destroyed, same as",
                        "the original death roll. This list is the face values at level 0 (the",
                        "player's level AT DEATH, not at destruction time) -- must have the same",
                        "length as this section's own faceRetentionAtMaxLevel below. Defaults match",
                        "XPB-09's original (pre-recalibration) death-penalty curve.")
                .defineList("faceRetentionAtLevel0",
                        List.of(0.95, 0.85, 0.72, 0.58, 0.45, 0.32),
                        o -> o instanceof Double d && d >= 0.0D && d <= 1.0D);
        CORPSE_DESTRUCTION_FACE_RETENTION_AT_MAX_LEVEL = BUILDER
                .comment("Same die faces as this section's own faceRetentionAtLevel0 above, but their",
                        "values at deathXpPenalty.maxLevel and above (the two curves share one level",
                        "scale, only the anchor VALUES differ).")
                .defineList("faceRetentionAtMaxLevel",
                        List.of(0.55, 0.45, 0.35, 0.27, 0.20, 0.14),
                        o -> o instanceof Double d && d >= 0.0D && d <= 1.0D);

        BUILDER.pop();

        // xpBottle* used to live here (COMMON). Moved to SERVER_SPEC below — fix round F6, see the
        // class javadoc. The DungeonNowLoading Great Bottle remains independently tunable through
        // the sibling GreatBottleConfig rather than sharing these normal-bottle settings.
    }

    static final ForgeConfigSpec SPEC = BUILDER.build();

    // ── XP bottle (SERVER — must sync to clients, see class javadoc) ─────────
    //
    // LB-03 full configuration surface, three sub-groups under xpBottle:
    //   master        — the single off-switch for the whole feature.
    //   stats         — numeric tuning (capacity, stack sizes, rolls, timing).
    //   featureGates  — per-behaviour switches, all only consulted while master.enabled is true.

    private static final ForgeConfigSpec.Builder SERVER_BUILDER = new ForgeConfigSpec.Builder();

    // ── Master ──
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_ENABLED;

    // ── Stats ──
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_CAPACITY_LEVELS;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_STORE_UNTIL_PREVIOUS_LEVEL;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_EMPTY_STACK_SIZE;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_FULL_STACK_SIZE;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_LOOT_ROLL_MIN;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_LOOT_ROLL_MAX;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_UNRESOLVED_THROW_ROLL_MIN;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_UNRESOLVED_THROW_ROLL_MAX;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_DOUBLE_CLICK_WINDOW_TICKS;
    private static final ForgeConfigSpec.IntValue XP_BOTTLE_GESTURE_RATE_LIMIT_TICKS;
    private static final ForgeConfigSpec.DoubleValue XP_BOTTLE_ORB_VACUUM_RADIUS;

    // ── Feature gates (only consulted while xpBottleEnabled is true) ──
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_ALLOW_DEPOSIT;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_THROW_PAYS_STORED_XP;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_REQUIRE_DOUBLE_CLICK_TO_THROW;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_UNRESOLVED_THROW_USES_VANILLA_ROLL;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_ROLL_LOOT_BOTTLES;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_ROLL_TRADE_BOTTLES;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_CREATIVE_FREE_TOP_UP;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_CREATIVE_THROW_PAYS_ZERO;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_STAGED_TEXTURES;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_COUNT_BADGE;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_COUNT_BADGE_MARGINAL;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_TOOLTIP;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_FILL_BAR;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_FILL_BAR_HUE_RAMP;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_NORMALIZE_ILLEGAL_STACKS;
    private static final ForgeConfigSpec.BooleanValue XP_BOTTLE_ALLOW_ORB_VACUUM;

    // ── Baked-default constants (SERVER_SPEC only) ───────────────────────────
    //
    // Single source of truth for both the defineInRange/define calls just below AND the fail-closed
    // reset applied on ModConfigEvent.Unloading (see onLoad): naming these once means the compiled
    // default a fresh world loads with and the value a departing client resets to can never drift
    // apart. xpBottleEnabled itself is the one exception — its Unloading reset target is
    // deliberately false (fail-CLOSED), not this compiled-true default; see the reset block.
    private static final int DEFAULT_CAPACITY_LEVELS = 5;
    private static final boolean DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL = false;
    private static final int DEFAULT_EMPTY_STACK_SIZE = 64;
    private static final int DEFAULT_FULL_STACK_SIZE = 16;
    /** Doubles vanilla's own 3-11 raw-XP throw default. */
    private static final int DEFAULT_LOOT_ROLL_MIN = 6;
    private static final int DEFAULT_LOOT_ROLL_MAX = 22;
    private static final int DEFAULT_UNRESOLVED_THROW_ROLL_MIN = 6;
    private static final int DEFAULT_UNRESOLVED_THROW_ROLL_MAX = 22;
    private static final int DEFAULT_DOUBLE_CLICK_WINDOW_TICKS = 10;
    private static final int DEFAULT_GESTURE_RATE_LIMIT_TICKS = 3;
    private static final double DEFAULT_ORB_VACUUM_RADIUS = 4.0;
    private static final boolean DEFAULT_ALLOW_DEPOSIT = true;
    private static final boolean DEFAULT_THROW_PAYS_STORED_XP = true;
    private static final boolean DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW = false;
    private static final boolean DEFAULT_UNRESOLVED_THROW_USES_VANILLA_ROLL = false;
    private static final boolean DEFAULT_ROLL_LOOT_BOTTLES = true;
    private static final boolean DEFAULT_ROLL_TRADE_BOTTLES = true;
    private static final boolean DEFAULT_CREATIVE_FREE_TOP_UP = true;
    private static final boolean DEFAULT_CREATIVE_THROW_PAYS_ZERO = true;
    private static final boolean DEFAULT_STAGED_TEXTURES = true;
    private static final boolean DEFAULT_COUNT_BADGE = true;
    private static final boolean DEFAULT_COUNT_BADGE_MARGINAL = true;
    private static final boolean DEFAULT_TOOLTIP = true;
    private static final boolean DEFAULT_FILL_BAR = true;
    private static final boolean DEFAULT_FILL_BAR_HUE_RAMP = false;
    private static final boolean DEFAULT_NORMALIZE_ILLEGAL_STACKS = true;
    private static final boolean DEFAULT_ALLOW_ORB_VACUUM = true;

    static {
        SERVER_BUILDER.push("xpBottle");

        // ── Master ────────────────────────────────────────────────────────
        SERVER_BUILDER.push("master");

        XP_BOTTLE_ENABLED = SERVER_BUILDER
                .comment("Master switch for the whole XP-vessel Bottle o' Enchanting rework.",
                        "false restores 100% vanilla Bottle o' Enchanting behaviour; stored XP already on",
                        "bottles is preserved in NBT and returns if re-enabled.")
                .define("enabled", true);

        SERVER_BUILDER.pop();

        // ── Stats ─────────────────────────────────────────────────────────
        SERVER_BUILDER.push("stats");

        XP_BOTTLE_CAPACITY_LEVELS = SERVER_BUILDER
                .comment("Bottle capacity, denominated in LEVELS rather than raw XP (V2). Default 5 is",
                        "the XP required to reach level 5 = 55 XP (XPB-06 -- cut hard from the old",
                        "default of 25/910 XP so a bare glass bottle can no longer become a large free",
                        "XP bank turn one; see whatremains:xp_bottle, the crafted item that alone",
                        "carries vessel behaviour). XpVessel#capacity() derives the XP figure from this",
                        "level via vanilla's own (quadratic) level-cost curve, it is never stored",
                        "directly. Because that curve is non-linear, raising this a LITTLE raises the XP",
                        "capacity a LOT -- e.g. level 100 costs 30,970 XP, not 20x level 5's 55 despite",
                        "being 20x the level number.",
                        "Capped at 150: the orb manifest holds at most MAX_CHUNKS (32) chunks drawn from",
                        "vanilla's orb ladder (top rung 2477), so ~79,264 XP is the largest capacity the",
                        "manifest can represent, and level 150 (79,095 XP) is the largest level that fits.")
                .defineInRange("capacityLevels", DEFAULT_CAPACITY_LEVELS, 1, 150);
        XP_BOTTLE_STORE_UNTIL_PREVIOUS_LEVEL = SERVER_BUILDER
                .comment("If true, depositing stores only as much XP from the player's XP bar as reaches their previous level, so at most one level is deposited at a time.")
                .define("storeUntilPreviousLevel", DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL);
        XP_BOTTLE_EMPTY_STACK_SIZE = SERVER_BUILDER
                .comment("Stack size for EMPTY bottles (no stored XP). Vanilla default is 64.")
                .defineInRange("emptyStackSize", DEFAULT_EMPTY_STACK_SIZE, 1, 64);
        XP_BOTTLE_FULL_STACK_SIZE = SERVER_BUILDER
                .comment("Stack size for any bottle carrying stored XP -- full OR partial (XPB-07: partial",
                        "bottles used to cap at 1 regardless of this setting; now they share this limit,",
                        "provided every unit in the stack holds the exact same XP value). Not a duplication",
                        "risk at any value -- every unit honestly cost being deposited/vacuumed/found to",
                        "its exact amount, and vanilla's own NBT-equality check is what enforces that two",
                        "stacks only ever combine when their stored values genuinely match.")
                .defineInRange("fullStackSize", DEFAULT_FULL_STACK_SIZE, 1, 64);
        XP_BOTTLE_LOOT_ROLL_MIN = SERVER_BUILDER
                .comment("Lower bound (inclusive), in RAW XP, of the uniform roll for a loot- or",
                        "trade-sourced bottle, clamped to capacity(). Default 6 doubles vanilla's own",
                        "3-11 raw-XP throw default. Swapped with lootRollMax at load if min > max.")
                .defineInRange("lootRollMin", DEFAULT_LOOT_ROLL_MIN, 0, 1024);
        XP_BOTTLE_LOOT_ROLL_MAX = SERVER_BUILDER
                .comment("Upper bound (inclusive), in RAW XP, of the uniform roll for a loot- or",
                        "trade-sourced bottle, clamped to capacity(). Default 22 doubles vanilla's own",
                        "3-11 raw-XP throw default. Swapped with lootRollMin at load if min > max.")
                .defineInRange("lootRollMax", DEFAULT_LOOT_ROLL_MAX, 0, 1024);
        XP_BOTTLE_UNRESOLVED_THROW_ROLL_MIN = SERVER_BUILDER
                .comment("Lower bound (inclusive), in RAW XP, of the uniform payout for an unresolved",
                        "thrown bottle when unresolvedThrowUsesVanillaRoll is false. This distribution",
                        "is deliberately separate from loot/trade level rolls. Swapped with",
                        "unresolvedThrowRollMax at load if min > max.")
                .defineInRange("unresolvedThrowRollMin", DEFAULT_UNRESOLVED_THROW_ROLL_MIN, 0, 1024);
        XP_BOTTLE_UNRESOLVED_THROW_ROLL_MAX = SERVER_BUILDER
                .comment("Upper bound (inclusive), in RAW XP, of the uniform payout for an unresolved",
                        "thrown bottle when unresolvedThrowUsesVanillaRoll is false. This distribution",
                        "is deliberately separate from loot/trade level rolls. Swapped with",
                        "unresolvedThrowRollMin at load if min > max.")
                .defineInRange("unresolvedThrowRollMax", DEFAULT_UNRESOLVED_THROW_ROLL_MAX, 0, 1024);
        XP_BOTTLE_DOUBLE_CLICK_WINDOW_TICKS = SERVER_BUILDER
                .comment("How many client ticks the accident-guard's first click stays armed, waiting",
                        "for a genuine second click to throw. Only relevant while requireDoubleClickToThrow is true.")
                .defineInRange("doubleClickWindowTicks", DEFAULT_DOUBLE_CLICK_WINDOW_TICKS, 2, 60);
        XP_BOTTLE_GESTURE_RATE_LIMIT_TICKS = SERVER_BUILDER
                .comment("Minimum server ticks between two ACCEPTED gesture packets from the same player.",
                        "0 disables the rate limit entirely.")
                .defineInRange("gestureRateLimitTicks", DEFAULT_GESTURE_RATE_LIMIT_TICKS, 0, 40);
        XP_BOTTLE_ORB_VACUUM_RADIUS = SERVER_BUILDER
                .comment("How far the vacuum reaches, in blocks. 8 is vanilla's own orb follow radius",
                        "(ExperienceOrb#scanForEntities), so above that would grab orbs that were never",
                        "heading for the player anyway.")
                .defineInRange("orbVacuumRadius", DEFAULT_ORB_VACUUM_RADIUS, 1.0, 8.0);

        SERVER_BUILDER.pop();

        // ── Feature gates ─────────────────────────────────────────────────
        SERVER_BUILDER.push("featureGates");

        XP_BOTTLE_ALLOW_DEPOSIT = SERVER_BUILDER
                .comment("If false, sneak+RMB deposit does nothing but play the reject cue.")
                .define("allowDeposit", DEFAULT_ALLOW_DEPOSIT);
        XP_BOTTLE_THROW_PAYS_STORED_XP = SERVER_BUILDER
                .comment("If false, every throw pays a fresh roll (unresolvedThrowRollMin..",
                        "unresolvedThrowRollMax, or vanilla's triangular roll per",
                        "unresolvedThrowUsesVanillaRoll) — stored XP is never paid out by a throw.")
                .define("throwPaysStoredXp", DEFAULT_THROW_PAYS_STORED_XP);
        XP_BOTTLE_REQUIRE_DOUBLE_CLICK_TO_THROW = SERVER_BUILDER
                .comment("If false, a single non-sneak click throws immediately — no arm/release accident-guard step.",
                        "Default false: a single click throws exactly like the vanilla Bottle o' Enchanting.")
                .define("requireDoubleClickToThrow", DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW);
        XP_BOTTLE_UNRESOLVED_THROW_USES_VANILLA_ROLL = SERVER_BUILDER
                .comment("If true, an unresolved (never rolled) thrown bottle pays vanilla's own",
                        "triangular 3 + rand(5) + rand(5) instead of the uniform RAW-XP range",
                        "unresolvedThrowRollMin..unresolvedThrowRollMax. Loot- and trade-sourced",
                        "pre-rolls always use lootRollMin..lootRollMax regardless of this flag.")
                .define("unresolvedThrowUsesVanillaRoll", DEFAULT_UNRESOLVED_THROW_USES_VANILLA_ROLL);
        XP_BOTTLE_ROLL_LOOT_BOTTLES = SERVER_BUILDER
                .comment("If false, loot-table bottles are left completely vanilla — no per-unit XP roll.")
                .define("rollLootBottles", DEFAULT_ROLL_LOOT_BOTTLES);
        XP_BOTTLE_ROLL_TRADE_BOTTLES = SERVER_BUILDER
                .comment("If false, villager/wandering-trader bottle offers are left completely vanilla — no per-offer XP roll.")
                .define("rollTradeBottles", DEFAULT_ROLL_TRADE_BOTTLES);
        XP_BOTTLE_CREATIVE_FREE_TOP_UP = SERVER_BUILDER
                .comment("If false, a creative player's deposit deducts XP from their bar normally, same as survival.")
                .define("creativeFreeTopUp", DEFAULT_CREATIVE_FREE_TOP_UP);
        XP_BOTTLE_CREATIVE_THROW_PAYS_ZERO = SERVER_BUILDER
                .comment("If false, a creative player's throw pays out the bottle's real stored XP instead of zero.")
                .define("creativeThrowPaysZero", DEFAULT_CREATIVE_THROW_PAYS_ZERO);
        XP_BOTTLE_STAGED_TEXTURES = SERVER_BUILDER
                .comment("If false, the item model predicate always returns 0 — the bottle always renders",
                        "as the plain base vanilla model, regardless of fill level.")
                .define("stagedTextures", DEFAULT_STAGED_TEXTURES);
        XP_BOTTLE_COUNT_BADGE = SERVER_BUILDER
                .comment("If true (V2 default), draws the level-equivalent of this bottle's stored XP as a",
                        "small number badge in the item's top-left corner (Light-item style). This is",
                        "PER-BOTTLE, not a stack total — a stack of identical full bottles all show the",
                        "same single bottle's level.")
                .define("countBadge", DEFAULT_COUNT_BADGE);
        XP_BOTTLE_COUNT_BADGE_MARGINAL = SERVER_BUILDER
                .comment("If true (default), the count badge shows the MARGINAL levels the viewing player",
                        "would actually gain by drinking this bottle right now, computed from the player's",
                        "real current XP position on vanilla's level curve — not the bottle's intrinsic",
                        "level-from-zero figure (which is always >= this). If false, the badge falls back",
                        "to the intrinsic figure (XpVessel#storedLevel), matching pre-XPB-04 behaviour.")
                .define("countBadgeMarginal", DEFAULT_COUNT_BADGE_MARGINAL);
        XP_BOTTLE_TOOLTIP = SERVER_BUILDER
                .comment("If false, no 'stored XP' tooltip line is added.")
                .define("tooltip", DEFAULT_TOOLTIP);
        XP_BOTTLE_FILL_BAR = SERVER_BUILDER
                .comment("If true (default), a bottle carrying SOME but not all of its capacity draws a",
                        "cosmetic durability-style fill bar under the item icon, exactly like a vanilla",
                        "durability item — hidden again once the bottle is full (or empty), matching",
                        "vanilla's own 'no bar when undamaged' convention. Purely cosmetic: never real",
                        "item durability.")
                .define("fillBar", DEFAULT_FILL_BAR);
        XP_BOTTLE_FILL_BAR_HUE_RAMP = SERVER_BUILDER
                .comment("If true, the fill bar sweeps red (near-empty) to green (near-full), vanilla-",
                        "durability style. If false (default), it stays a fixed bright XP-orb green",
                        "regardless of fill level. Ignored while fillBar is false.")
                .define("fillBarHueRamp", DEFAULT_FILL_BAR_HUE_RAMP);
        XP_BOTTLE_NORMALIZE_ILLEGAL_STACKS = SERVER_BUILDER
                .comment("If false, the non-destructive oversized-stack repair net (op-created /give or",
                        "NBT-edit stacks, or an existing stack left oversized by a stack-size config",
                        "change, only) is disabled.")
                .define("normalizeIllegalStacks", DEFAULT_NORMALIZE_ILLEGAL_STACKS);
        XP_BOTTLE_ALLOW_ORB_VACUUM = SERVER_BUILDER
                .comment("If true, sneak+use with a glass bottle or a non-full Bottle o' Enchanting pulls",
                        "nearby XP orbs into the bottle instead of the player's XP bar; a glass bottle",
                        "becomes a filled bottle. Turning it off restores vanilla glass-bottle and deposit",
                        "behaviour exactly.")
                .define("allowOrbVacuum", DEFAULT_ALLOW_ORB_VACUUM);

        SERVER_BUILDER.pop();
        SERVER_BUILDER.pop();
    }

    static final ForgeConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    // ── Baked values (set on config load / reload) ──────────────────────────

    public static int itemDamageOnDeath;
    public static double corpseHP;
    public static boolean addBonesToCorpse;
    public static int corpseInventorySize;
    public static int shovelHitsToBury;

    public static boolean hurtByAll;
    public static boolean hurtByFire;
    public static boolean hurtByExplosions;
    public static boolean hurtByFall;
    public static boolean hurtByCactus;
    public static boolean hurtByWeapons;
    public static boolean hurtBySuffocation;
    public static boolean hurtByOther;
    /** Derived: true when no damage source is enabled. */
    public static boolean invulnerable;

    public static boolean enableDecay;
    public static boolean decayOnlyWhenEmpty;
    /** Decay time in game ticks. */
    public static long decayTimeTicks;
    /** Full decay lifecycle lifespan in ticks (1 MC day = 24 000 ticks). */
    public static int decayLifespanTicks;
    /** Extra decay-speed multiplier added per fly landed on the corpse (BuildingBridges bridge). */
    public static double flyDecayBonusPerFly;
    /** Extra decay-speed multiplier added per vulture feeding on the corpse (BuildingBridges bridge).
     *  A feeding vulture accelerates decay far more aggressively than a landed fly. */
    public static double vultureDecayBonusPerVulture;
    /** TESTING ONLY — see the config comment. Makes every corpse maximally fly-attractive
     *  regardless of decay stage (BuildingBridges bridge). */
    public static boolean debugFliesIgnoreDecayStage;
    /** TESTING ONLY — see the config comment. Lets vultures treat a FRESH corpse as feedable so
     *  landing can be tested without waiting for decay (BuildingBridges bridge). */
    public static boolean debugVulturesIgnoreDecayStage;

    /** Biome base temperature at which the decay-rate multiplier is exactly 1.0x. */
    public static double decayNeutralTemperature;
    /** Biome base temperature at which the cold-side multiplier bottoms out. */
    public static double decayColdReferenceTemperature;
    /** Decay-rate multiplier at/below {@link #decayColdReferenceTemperature}. */
    public static double decayColdRateMultiplier;
    /** Biome base temperature at which the hot-side multiplier caps out. */
    public static double decayHotReferenceTemperature;
    /** Decay-rate multiplier at/above {@link #decayHotReferenceTemperature}. */
    public static double decayHotRateMultiplier;
    /** Extra decay-rate multiplier applied when the corpse can't see the sky (stacks multiplicatively). */
    public static double decayNoSkyRateMultiplier;

    // ── Corpse vulture feeding (BuildingBridges vulture-corpse bridge, if installed) ──
    /** The CORPSE-SIDE master gate on this corpse ever being fed on by a vulture of either species --
     *  when false, {@code reserveFeedingSlot} always refuses, the per-feeder decay-rate term
     *  contributes zero, and {@code consumeCarrionFood}/{@code dropVultureGore} both refuse. The
     *  separate BIRD-SIDE gate ({@code VultureConfig.ENABLE_CORPSE_FEEDING}) governs whether a
     *  vulture ever attempts the behaviour at all; see this field's config comment for the full
     *  two-layer explanation. */
    public static boolean enableVultureCorpseFeeding;
    /** Hard cap on simultaneous feeding-slot reservations this corpse grants (both species
     *  combined). 0 disables vulture feeding on this corpse entirely. */
    public static int vultureMaxFeedersPerCorpse;
    /** Chance (0.0-1.0), rolled once per completed feeding cycle, that one tagged food item is
     *  consumed from this corpse's container. */
    public static double vultureFoodConsumeChance;
    /** Hard cap on food items ever consumed from this corpse over its entire lifetime (persistent,
     *  survives a world reload). 0 disables food consumption on this corpse entirely. */
    public static int vultureMaxFoodItemsPerCorpse;
    /** Chance (0.0-1.0), rolled once per completed feeding cycle, that a feeding vulture drops one
     *  rotten_flesh gore item beside this corpse. */
    public static double vultureGoreDropChance;
    /** Hard cap on rotten_flesh gore drops ever spawned by this corpse over its entire lifetime
     *  (persistent, survives a world reload). */
    public static int vultureMaxGoreDropsPerCorpse;
    /** How far (in blocks) a feeding vulture may drift from this corpse before its feeding-slot
     *  reservation is pruned by the corpse's own tick. */
    public static double vultureFeederLeashRange;

    public static int vacuumRadius;
    public static int vacuumTicks;

    public static int deathXpMaxLevel;
    public static List<? extends Double> deathXpFaceRetentionAtLevel0;
    public static List<? extends Double> deathXpFaceRetentionAtMaxLevel;
    public static double deathXpLuckDiceWeight;
    public static int deathXpLuckDiceCap;

    public static int corpseVacuumXpDrainPerAction;
    public static List<? extends Double> corpseDestructionFaceRetentionAtLevel0;
    public static List<? extends Double> corpseDestructionFaceRetentionAtMaxLevel;

    // xpBottle* fields are baked from SERVER_SPEC, which is unavailable until a world loads.
    // Every reader goes through XpVessel/WhatRemainsConfig's own defaults rather than trusting a
    // pre-bake 0/false — see XpVessel#capacity()'s DEFAULT_CAPACITY fallback in particular.
    // xpBottleEnabled is the one field this cuts the RIGHT way for: its pre-bake default (Java's
    // primitive `false`) is coincidentally the FAIL-CLOSED direction — every gated site treats
    // xpBottleEnabled == false as "run vanilla, unmodified", which is exactly the behaviour wanted
    // before a client has synced the real value (see XpBottleGestureHandler's fail-closed comment).
    // Fail-CLOSED, not fail-open: the feature is off/inert until config proves otherwise, never the
    // reverse. If this bake never runs at all (corrupt toml, a handler that silently doesn't fire),
    // the field simply never leaves this same false/closed state — see the onLoad log line below,
    // which turns "did the config actually load?" into a one-grep question.
    public static boolean xpBottleEnabled;

    /**
     * Configured bottle capacity in LEVELS (V2), not raw XP — see {@link XpVessel#capacity()},
     * which derives the XP figure from this via vanilla's own level curve. Replaces V1's
     * XP-denominated {@code xpBottleCapacity}.
     */
    public static int xpBottleCapacityLevels;
    public static boolean xpBottleStoreUntilPreviousLevel;
    public static int xpBottleEmptyStackSize;
    public static int xpBottleFullStackSize;
    /** Inclusive loot/trade roll bounds in RAW XP (XPB-04 — previously LEVEL-denominated). */
    public static int xpBottleLootRollMin;
    public static int xpBottleLootRollMax;
    /** Inclusive unresolved-throw roll bounds in RAW XP. */
    public static int xpBottleUnresolvedThrowRollMin;
    public static int xpBottleUnresolvedThrowRollMax;
    public static int xpBottleDoubleClickWindowTicks;
    public static int xpBottleGestureRateLimitTicks;
    /** How far the orb vacuum reaches, in blocks — see {@code XpVessel#vacuumRadius()}. */
    public static double xpBottleOrbVacuumRadius;

    public static boolean xpBottleAllowDeposit;
    public static boolean xpBottleThrowPaysStoredXp;
    public static boolean xpBottleRequireDoubleClickToThrow;
    public static boolean xpBottleUnresolvedThrowUsesVanillaRoll;
    public static boolean xpBottleRollLootBottles;
    public static boolean xpBottleRollTradeBottles;
    public static boolean xpBottleCreativeFreeTopUp;
    public static boolean xpBottleCreativeThrowPaysZero;
    public static boolean xpBottleStagedTextures;
    public static boolean xpBottleCountBadge;
    /** If true, the count badge shows marginal (viewer-relative) levels rather than intrinsic ones. */
    public static boolean xpBottleCountBadgeMarginal;
    public static boolean xpBottleTooltip;
    public static boolean xpBottleFillBar;
    /** If true, the fill bar hue-ramps red→green instead of staying a fixed green. */
    public static boolean xpBottleFillBarHueRamp;
    public static boolean xpBottleNormalizeIllegalStacks;
    /** Sneak+use pulls nearby XP orbs into the held bottle instead of the player's XP bar. */
    public static boolean xpBottleAllowOrbVacuum;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading) {
            // Unloading fires (for SERVER configs, at world exit) AFTER Forge nulls the config
            // data — a ConfigValue#get() at that point throws IllegalStateException in dev, so
            // this branch must never call .get() on anything below.
            //
            // V1 of this handler simply returned here, which left every baked xpBottle* field
            // holding whatever the LAST modded server sent, forever — a client that leaves a
            // modded server (for a vanilla one, or none at all) would keep xpBottleEnabled=true
            // and stale capacity/stack-size fields indefinitely: getMaxStackSize would keep
            // reporting 16/1 against a server that never sent those values, and the count badge
            // would keep drawing a level number for a bottle that, as far as this client can now
            // tell, is not even feature-gated any more. V2 instead resets every SERVER_SPEC-baked
            // primitive to a fail-closed compiled default on unload — xpBottleEnabled specifically
            // to `false` (not its compiled-`true` define default) so every gated call site falls
            // back to exactly-vanilla behaviour immediately, per the master-off invariant; the rest
            // reset to their compiled defaults belt-and-braces, in case any site ever reads one of
            // them without checking xpBottleEnabled first.
            if (event.getConfig().getSpec() == SERVER_SPEC) {
                xpBottleEnabled = false;

                xpBottleCapacityLevels          = DEFAULT_CAPACITY_LEVELS;
                xpBottleStoreUntilPreviousLevel = DEFAULT_STORE_UNTIL_PREVIOUS_LEVEL;
                xpBottleEmptyStackSize          = DEFAULT_EMPTY_STACK_SIZE;
                xpBottleFullStackSize           = DEFAULT_FULL_STACK_SIZE;
                xpBottleLootRollMin             = DEFAULT_LOOT_ROLL_MIN;
                xpBottleLootRollMax             = DEFAULT_LOOT_ROLL_MAX;
                xpBottleUnresolvedThrowRollMin  = DEFAULT_UNRESOLVED_THROW_ROLL_MIN;
                xpBottleUnresolvedThrowRollMax  = DEFAULT_UNRESOLVED_THROW_ROLL_MAX;
                xpBottleDoubleClickWindowTicks  = DEFAULT_DOUBLE_CLICK_WINDOW_TICKS;
                xpBottleGestureRateLimitTicks   = DEFAULT_GESTURE_RATE_LIMIT_TICKS;
                xpBottleOrbVacuumRadius         = DEFAULT_ORB_VACUUM_RADIUS;

                xpBottleAllowDeposit                   = DEFAULT_ALLOW_DEPOSIT;
                xpBottleThrowPaysStoredXp              = DEFAULT_THROW_PAYS_STORED_XP;
                xpBottleRequireDoubleClickToThrow      = DEFAULT_REQUIRE_DOUBLE_CLICK_TO_THROW;
                xpBottleUnresolvedThrowUsesVanillaRoll = DEFAULT_UNRESOLVED_THROW_USES_VANILLA_ROLL;
                xpBottleRollLootBottles                = DEFAULT_ROLL_LOOT_BOTTLES;
                xpBottleRollTradeBottles               = DEFAULT_ROLL_TRADE_BOTTLES;
                xpBottleCreativeFreeTopUp              = DEFAULT_CREATIVE_FREE_TOP_UP;
                xpBottleCreativeThrowPaysZero          = DEFAULT_CREATIVE_THROW_PAYS_ZERO;
                xpBottleStagedTextures                 = DEFAULT_STAGED_TEXTURES;
                xpBottleCountBadge                     = DEFAULT_COUNT_BADGE;
                xpBottleCountBadgeMarginal              = DEFAULT_COUNT_BADGE_MARGINAL;
                xpBottleTooltip                        = DEFAULT_TOOLTIP;
                xpBottleFillBar                        = DEFAULT_FILL_BAR;
                xpBottleFillBarHueRamp                 = DEFAULT_FILL_BAR_HUE_RAMP;
                xpBottleNormalizeIllegalStacks         = DEFAULT_NORMALIZE_ILLEGAL_STACKS;
                xpBottleAllowOrbVacuum                 = DEFAULT_ALLOW_ORB_VACUUM;

                LOGGER.info("[WhatRemains] XP vessel: UNLOADED — reset to fail-closed defaults (vanilla bottles)");
            }
            // The COMMON spec (corpse settings) is per-instance/local and never synced from a
            // server, so it carries no "stale server value" hazard — leave its baked fields as
            // they were, same as V1's plain early return.
            return;
        }
        if (event.getConfig().getSpec() == SERVER_SPEC) {
            xpBottleEnabled                 = XP_BOTTLE_ENABLED.get();

            xpBottleCapacityLevels          = XP_BOTTLE_CAPACITY_LEVELS.get();
            xpBottleStoreUntilPreviousLevel = XP_BOTTLE_STORE_UNTIL_PREVIOUS_LEVEL.get();
            xpBottleEmptyStackSize          = XP_BOTTLE_EMPTY_STACK_SIZE.get();
            xpBottleFullStackSize           = XP_BOTTLE_FULL_STACK_SIZE.get();

            // Defensive swaps: each pair contains independent range-checked ints, so nothing stops
            // a hand-edited toml (or two mods layering config changes) from setting min > max.
            int sourceRollMin = XP_BOTTLE_LOOT_ROLL_MIN.get();
            int sourceRollMax = XP_BOTTLE_LOOT_ROLL_MAX.get();
            xpBottleLootRollMin = Math.min(sourceRollMin, sourceRollMax);
            xpBottleLootRollMax = Math.max(sourceRollMin, sourceRollMax);

            int unresolvedThrowRollMin = XP_BOTTLE_UNRESOLVED_THROW_ROLL_MIN.get();
            int unresolvedThrowRollMax = XP_BOTTLE_UNRESOLVED_THROW_ROLL_MAX.get();
            xpBottleUnresolvedThrowRollMin = Math.min(unresolvedThrowRollMin, unresolvedThrowRollMax);
            xpBottleUnresolvedThrowRollMax = Math.max(unresolvedThrowRollMin, unresolvedThrowRollMax);

            xpBottleDoubleClickWindowTicks  = XP_BOTTLE_DOUBLE_CLICK_WINDOW_TICKS.get();
            xpBottleGestureRateLimitTicks   = XP_BOTTLE_GESTURE_RATE_LIMIT_TICKS.get();
            xpBottleOrbVacuumRadius         = XP_BOTTLE_ORB_VACUUM_RADIUS.get();

            xpBottleAllowDeposit                   = XP_BOTTLE_ALLOW_DEPOSIT.get();
            xpBottleThrowPaysStoredXp              = XP_BOTTLE_THROW_PAYS_STORED_XP.get();
            xpBottleRequireDoubleClickToThrow      = XP_BOTTLE_REQUIRE_DOUBLE_CLICK_TO_THROW.get();
            xpBottleUnresolvedThrowUsesVanillaRoll = XP_BOTTLE_UNRESOLVED_THROW_USES_VANILLA_ROLL.get();
            xpBottleRollLootBottles                = XP_BOTTLE_ROLL_LOOT_BOTTLES.get();
            xpBottleRollTradeBottles               = XP_BOTTLE_ROLL_TRADE_BOTTLES.get();
            xpBottleCreativeFreeTopUp              = XP_BOTTLE_CREATIVE_FREE_TOP_UP.get();
            xpBottleCreativeThrowPaysZero          = XP_BOTTLE_CREATIVE_THROW_PAYS_ZERO.get();
            xpBottleStagedTextures                 = XP_BOTTLE_STAGED_TEXTURES.get();
            xpBottleCountBadge                     = XP_BOTTLE_COUNT_BADGE.get();
            xpBottleCountBadgeMarginal              = XP_BOTTLE_COUNT_BADGE_MARGINAL.get();
            xpBottleTooltip                        = XP_BOTTLE_TOOLTIP.get();
            xpBottleFillBar                        = XP_BOTTLE_FILL_BAR.get();
            xpBottleFillBarHueRamp                 = XP_BOTTLE_FILL_BAR_HUE_RAMP.get();
            xpBottleNormalizeIllegalStacks         = XP_BOTTLE_NORMALIZE_ILLEGAL_STACKS.get();
            xpBottleAllowOrbVacuum                 = XP_BOTTLE_ALLOW_ORB_VACUUM.get();

            // Diagnostic (SERVER-spec bake only, one line — this repo has been bitten repeatedly by
            // "config never actually loaded, feature silently inert, no error" — see CLAUDE.md's
            // mixin-registration section for the sibling failure mode). Makes "did this bake?" a
            // one-grep question instead of a debugging session. Logs both the configured LEVEL and
            // the XP figure XpVessel#capacity() derives from it (V2), since the two can differ by
            // orders of magnitude thanks to the level curve's non-linearity — see capacityLevels'
            // own comment above.
            if (xpBottleEnabled) {
                LOGGER.info("[WhatRemains] XP vessel: ENABLED (capacity level {} = {} XP, stacks empty {} / stored (any value) {})",
                        xpBottleCapacityLevels, XpVessel.experienceForLevel(xpBottleCapacityLevels),
                        xpBottleEmptyStackSize, xpBottleFullStackSize);
            } else {
                LOGGER.info("[WhatRemains] XP vessel: DISABLED (vanilla bottles)");
            }
            return;
        }
        if (event.getConfig().getSpec() != SPEC) return;

        itemDamageOnDeath   = ITEM_DAMAGE_ON_DEATH.get();
        corpseHP            = CORPSE_HP.get();
        addBonesToCorpse    = ADD_BONES_TO_CORPSE.get();
        corpseInventorySize = CORPSE_INVENTORY_SIZE.get();
        shovelHitsToBury    = SHOVEL_HITS_TO_BURY.get();

        hurtByAll           = HURT_BY_ALL.get();
        hurtByFire          = HURT_BY_FIRE.get();
        hurtByExplosions    = HURT_BY_EXPLOSIONS.get();
        hurtByFall          = HURT_BY_FALL.get();
        hurtByCactus        = HURT_BY_CACTUS.get();
        hurtByWeapons       = HURT_BY_WEAPONS.get();
        hurtBySuffocation   = HURT_BY_SUFFOCATION.get();
        hurtByOther         = HURT_BY_OTHER.get();

        invulnerable = !(hurtByAll || hurtByFire || hurtByExplosions || hurtByFall
                || hurtByCactus || hurtByWeapons || hurtBySuffocation || hurtByOther);

        enableDecay        = ENABLE_DECAY.get();
        decayOnlyWhenEmpty = DECAY_ONLY_WHEN_EMPTY.get();
        decayTimeTicks     = (long) DECAY_TIME_SECONDS.get() * 20L;
        decayLifespanTicks = (int) Math.round(DECAY_LIFESPAN_DAYS.get() * 24_000.0);
        flyDecayBonusPerFly = FLY_DECAY_BONUS_PER_FLY.get();
        vultureDecayBonusPerVulture = VULTURE_DECAY_BONUS_PER_VULTURE.get();
        debugFliesIgnoreDecayStage = DEBUG_FLIES_IGNORE_DECAY_STAGE.get();
        debugVulturesIgnoreDecayStage = DEBUG_VULTURES_IGNORE_DECAY_STAGE.get();

        decayNeutralTemperature        = DECAY_NEUTRAL_TEMPERATURE.get();
        decayColdReferenceTemperature  = DECAY_COLD_REFERENCE_TEMPERATURE.get();
        decayColdRateMultiplier        = DECAY_COLD_RATE_MULTIPLIER.get();
        decayHotReferenceTemperature   = DECAY_HOT_REFERENCE_TEMPERATURE.get();
        decayHotRateMultiplier         = DECAY_HOT_RATE_MULTIPLIER.get();
        decayNoSkyRateMultiplier       = DECAY_NO_SKY_RATE_MULTIPLIER.get();

        enableVultureCorpseFeeding    = ENABLE_VULTURE_CORPSE_FEEDING.get();
        vultureMaxFeedersPerCorpse    = VULTURE_MAX_FEEDERS_PER_CORPSE.get();
        vultureFoodConsumeChance     = VULTURE_FOOD_CONSUME_CHANCE.get();
        vultureMaxFoodItemsPerCorpse = VULTURE_MAX_FOOD_ITEMS_PER_CORPSE.get();
        vultureGoreDropChance        = VULTURE_GORE_DROP_CHANCE.get();
        vultureMaxGoreDropsPerCorpse = VULTURE_MAX_GORE_DROPS_PER_CORPSE.get();
        vultureFeederLeashRange      = VULTURE_FEEDER_LEASH_RANGE.get();

        vacuumRadius = VACUUM_RADIUS.get();
        vacuumTicks  = VACUUM_TICKS.get();

        deathXpMaxLevel                 = DEATH_XP_MAX_LEVEL.get();
        deathXpFaceRetentionAtLevel0    = DEATH_XP_FACE_RETENTION_AT_LEVEL_0.get();
        deathXpFaceRetentionAtMaxLevel  = DEATH_XP_FACE_RETENTION_AT_MAX_LEVEL.get();
        deathXpLuckDiceWeight           = DEATH_XP_LUCK_DICE_WEIGHT.get();
        deathXpLuckDiceCap              = DEATH_XP_LUCK_DICE_CAP.get();

        corpseVacuumXpDrainPerAction              = CORPSE_VACUUM_XP_DRAIN_PER_ACTION.get();
        corpseDestructionFaceRetentionAtLevel0    = CORPSE_DESTRUCTION_FACE_RETENTION_AT_LEVEL_0.get();
        corpseDestructionFaceRetentionAtMaxLevel  = CORPSE_DESTRUCTION_FACE_RETENTION_AT_MAX_LEVEL.get();
    }
}
