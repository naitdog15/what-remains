package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.entity.DecayStage;

/**
 * Immutable, quantised snapshot of the corpse's current flesh appearance.
 * <p>
 * {@link #fromAge(int)} produces the pure age-driven baseline exactly as before. {@link
 * #withEnvironment(EnvironmentalRenderInputs)} is the ONE shared "external dampen/force" hook the
 * env-corpse design docs ask for (RECONCILIATION.md §2A/§7.1): every environmental case that
 * modulates coverage/palette — char (one-directional FLOOR, "regardless of age"), scorch
 * (desert_sun/nether_ash SUPPRESS blends), calcify (SUPPRESS + palette override), frost (coverage
 * dampening only — its own skin-recolour + detail-composite passes live in
 * {@code CorpseFleshBaker}), and ocean (a full per-stage {@link Endpoint} blend, the only case that
 * also moves {@link #bodyFullness}) — is a single branch inside this one method rather than a
 * separate bespoke code path per case.
 */
public final class CorpseRenderState {

    private static final int VISUAL_STEPS = 64;

    // Index 4 (ADVANCED_DECAY) is the OLD SKELETAL entry, byte-for-byte unchanged — DecayStage's own
    // header comment explains why: ADVANCED_DECAY took over old SKELETAL's exact fraction/position,
    // so nothing a player has already seen at that stage may visually move. Index 5 (NEW SKELETAL) is
    // a defensive-only entry — CorpseFleshBaker#resolve now nulls out (never bakes flesh) at true
    // SKELETAL, so this endpoint is never actually blended into a baked texture; it exists purely so
    // any other code path that still constructs a CorpseRenderState at this stage (e.g.
    // CorpseHeldItemLayer's pose computation, which needs visualStep/other fields regardless of
    // whether flesh bakes) gets numerically sane, monotonically-worse-than-index-4 values rather than
    // an array-bounds crash or a stale duplicate of ADVANCED_DECAY.
    private static final Endpoint[] ENDPOINTS = {
            new Endpoint(1.00F, 0.0F, 1.0F, 1.0F,
                    0x563444, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0.0F, 0.0F, 0.0F),
            new Endpoint(0.90F, 6.0F, 0.98F, 0.93F,
                    0x483E2B, 0.04F, 2.0F, 5.0F, 0, 0.0F, 0.0F, 0.0F, 0.0F),
            new Endpoint(0.60F, 36.0F, 1.10F, 0.54F,
                    0x4C632F, 0.24F, 4.0F, 8.0F, 0x653827, 0.08F, 2.0F, 5.0F, 0.12F),
            new Endpoint(0.22F, 58.0F, 1.25F, 0.36F,
                    0x385226, 0.42F, 6.0F, 12.0F, 0x532D22, 0.04F, 2.0F, 5.0F, 0.34F),
            new Endpoint(0.00F, 18.0F, 0.18F, 0.34F,
                    0x3B2F1B, 0.20F, 3.0F, 7.0F, 0, 0.0F, 0.0F, 0.0F, 0.68F),
            new Endpoint(0.00F, 20.0F, 0.08F, 0.28F,
                    0x241E16, 0.04F, 2.0F, 4.0F, 0, 0.0F, 0.0F, 0.0F, 0.94F)
    };

    /**
     * {@code ocean_bloat}'s parallel endpoint table (ocean_bloat.md §3.2/§3.3/§3.4/§3.5) —
     * coverage/hole fields are deliberately IDENTICAL to {@link #ENDPOINTS} stage-for-stage (only
     * hue/sat/val/blotch-RGB/diameters/fullness differ), which is the concrete mechanism that keeps
     * ocean_bloat {@code colour_only} + a shape axis rather than {@code decay_modified}: blending
     * two identical coverage values by any intensity is a no-op. {@code fullness = 1.20} is flat
     * across every stage on purpose — the swell ceiling is deliberately NOT stage-varying.
     */
    // Same index-4/index-5 treatment as ENDPOINTS above: index 4 (ADVANCED_DECAY) is the OLD
    // SKELETAL row unchanged; index 5 (NEW SKELETAL) is a defensive-only near-bare ocean-styled
    // entry. fullness stays the flat 1.20F this whole table never varies (see class javadoc); the
    // rest darkens/desaturates in step with the plain SKELETAL entry above. Without extending this
    // array, withEnvironment's Math.min(index, OCEAN_ENDPOINTS.length - 1) clamp would silently
    // freeze an ocean-styled corpse's visuals two stages early once DecayStage grew a 7th value.
    private static final Endpoint[] OCEAN_ENDPOINTS = {
            new Endpoint(1.20F, 190.0F, 0.30F, 1.15F,
                    0x563444, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0.0F, 0.0F, 0.0F),
            new Endpoint(1.20F, 190.0F, 0.30F, 1.15F,
                    0x494A53, 0.04F, 2.0F, 5.0F, 0, 0.0F, 0.0F, 0.0F, 0.0F),
            new Endpoint(1.20F, 190.0F, 0.30F, 1.15F,
                    0x6B6072, 0.24F, 4.0F, 10.0F, 0x5F6B74, 0.08F, 2.0F, 5.0F, 0.12F),
            new Endpoint(1.20F, 190.0F, 0.30F, 1.15F,
                    0x5B4F5E, 0.42F, 6.0F, 14.0F, 0x4F595F, 0.04F, 2.0F, 5.0F, 0.34F),
            new Endpoint(1.20F, 190.0F, 0.12F, 1.05F,
                    0x3A3B3E, 0.20F, 3.0F, 7.0F, 0, 0.0F, 0.0F, 0.0F, 0.68F),
            new Endpoint(1.20F, 190.0F, 0.06F, 0.95F,
                    0x28292B, 0.04F, 2.0F, 4.0F, 0, 0.0F, 0.0F, 0.0F, 0.94F)
    };

    // ── Scorch (desert_desiccation.md §2 / nether_ash_scorch.md §3) ─────────────────────────────
    private static final float DESERT_HUE_TARGET = 34.0F;
    private static final float DESERT_SAT_TARGET = 0.45F;
    private static final float DESERT_VAL_TARGET = 1.20F;
    private static final float DESERT_HOLE_SUPPRESS = 0.80F;
    private static final float DESERT_BLOTCH_SUPPRESS = 0.90F;

    private static final float NETHER_HUE_DELTA_DEG = -8.0F;
    private static final float NETHER_SAT_MULT_TARGET = 0.25F;
    private static final float NETHER_VAL_MULT_TARGET = 0.60F;
    private static final int NETHER_ASH_GREY_RGB = 0x3A3634;
    private static final int NETHER_SULFUR_RGB = 0x968045;
    private static final float NETHER_BLOTCH_SUPPRESS = 0.35F;
    private static final float NETHER_HOLE_SUPPRESS = 0.55F;
    private static final float NETHER_SULFUR_GATE_START = 0.35F;
    private static final float NETHER_SULFUR_TARGET_COVERAGE = 0.10F;

    // ── Calcify (cave_calcification.md §4) ───────────────────────────────────────────────────────
    private static final float CALCIFY_HUE_DELTA = 15.0F;
    private static final float CALCIFY_SAT_TARGET = 0.30F;
    private static final float CALCIFY_VAL_TARGET = 0.92F;
    private static final int CALCIFY_PRIMARY_RGB = 0xD6D2C4;
    private static final int CALCIFY_SECONDARY_RGB = 0x6E6C68;
    private static final float CALCIFY_HOLE_SUPPRESS = 0.55F;

    // ── Char (lava_progressive_incineration.md §2) ───────────────────────────────────────────────
    private static final float CHAR_SUPPRESS_START = 0.2F;
    private static final float CHAR_SUPPRESS_RANGE = 0.6F;
    private static final float CHAR_FLOOR_START = 0.5F;
    private static final float CHAR_FLOOR_RANGE = 0.5F;
    private static final float CHAR_HOLE_FLOOR_MAX = 0.68F;
    private static final float CHAR_REVEAL_BOOST_MAX = 0.90F;

    // ── Frost coverage dampening only — skin recolour + detail composite live in the baker
    // (frozen_rime.md §2 item 3) ─────────────────────────────────────────────────────────────────
    private static final float FROST_HOLE_SUPPRESS = 0.40F;
    private static final float FROST_BLOTCH_SUPPRESS = 0.30F;

    public final DecayStage decayStage;
    public final float stageProgress;
    public final float overallProgress;
    public final int visualStep;
    public final float bodyFullness;
    public final float hueShiftDegrees;
    public final float saturationMultiplier;
    public final float valueMultiplier;
    public final int primaryBlotchRgb;
    public final float primaryBlotchCoverage;
    public final float blotchMinDiameter;
    public final float blotchMaxDiameter;
    public final int secondaryBlotchRgb;
    public final float secondaryBlotchCoverage;
    public final float secondaryBlotchMinDiameter;
    public final float secondaryBlotchMaxDiameter;
    public final float holeCoverage;
    /** Floor applied to {@code CorpseFleshMask}'s full-limb bone-reveal chance — nonzero only
     *  above {@code charLevel 0.5} (lava's headline "near-bare skeleton regardless of age" lever). */
    public final float externalRevealBoost;

    private CorpseRenderState(DecayStage decayStage, float stageProgress, float overallProgress,
                              int visualStep, Endpoint values, float externalRevealBoost) {
        this.decayStage = decayStage;
        this.stageProgress = stageProgress;
        this.overallProgress = overallProgress;
        this.visualStep = visualStep;
        this.bodyFullness = values.fullness;
        this.hueShiftDegrees = values.hue;
        this.saturationMultiplier = values.saturation;
        this.valueMultiplier = values.value;
        this.primaryBlotchRgb = values.primaryRgb;
        this.primaryBlotchCoverage = values.primaryCoverage;
        this.blotchMinDiameter = values.minDiameter;
        this.blotchMaxDiameter = values.maxDiameter;
        this.secondaryBlotchRgb = values.secondaryRgb;
        this.secondaryBlotchCoverage = values.secondaryCoverage;
        this.secondaryBlotchMinDiameter = values.secondaryMinDiameter;
        this.secondaryBlotchMaxDiameter = values.secondaryMaxDiameter;
        this.holeCoverage = values.holes;
        this.externalRevealBoost = externalRevealBoost;
    }

    /**
     * Uses one global 64-step clock so colour, detail and holes change together. FRESH is held at
     * one visual step; it must never spend cache entries on identical pre-decay bakes.
     * <p>
     * Pure age baseline — carries no environmental influence. Callers that need the environmental
     * cases wired in must chain {@link #withEnvironment(EnvironmentalRenderInputs)}.
     */
    public static CorpseRenderState fromAge(int ageTicks) {
        DecayStage stage = DecayStage.fromAge(ageTicks);
        float overall = DecayStage.overallProgress(ageTicks);
        int visualStep = stage == DecayStage.FRESH
                ? 0
                : Math.round(overall * (VISUAL_STEPS - 1));

        if (stage == DecayStage.COLLAPSE) {
            Endpoint skeletal = ENDPOINTS[DecayStage.SKELETAL.ordinal()];
            return new CorpseRenderState(stage, 1.0F, overall, VISUAL_STEPS - 1,
                    new Endpoint(0.0F, skeletal.hue, skeletal.saturation, skeletal.value,
                            skeletal.primaryRgb, skeletal.primaryCoverage, skeletal.minDiameter,
                            skeletal.maxDiameter, skeletal.secondaryRgb, skeletal.secondaryCoverage,
                            skeletal.secondaryMinDiameter, skeletal.secondaryMaxDiameter, 1.0F),
                    0.0F);
        }

        float quantisedOverall = visualStep / (float) (VISUAL_STEPS - 1);
        int total = Math.max(1, DecayStage.getTotalLifespanTicks());
        float start = stage.getStartTick() / (float) total;
        float end = stage.getEndTick() / (float) total;
        float progress = stage == DecayStage.FRESH
                ? 0.0F
                : clamp((quantisedOverall - start) / Math.max(0.0001F, end - start));

        int index = stage.ordinal();
        Endpoint current = ENDPOINTS[index];
        Endpoint values = index == 0 ? current : Endpoint.lerp(ENDPOINTS[index - 1], current, progress);
        return new CorpseRenderState(stage, progress, overall, visualStep, values, 0.0F);
    }

    /**
     * Layers every environmental case's coverage/palette modulation on top of this (assumed
     * age-only) state, in this fixed order: scorch → calcify → ocean → char → frost coverage
     * dampening. Char runs after scorch/calcify/ocean so its FLOOR semantics ("regardless of every
     * other axis") always win on {@link #holeCoverage}; frost's dampening runs last since it only
     * ever shrinks coverage further, order-independent by construction (pure multiplication).
     * <p>
     * scorch/calcify/ocean are triggered by mutually-exclusive-in-practice environment conditions
     * (sky+heat vs. no-sky+moderate-heat vs. ocean-tagged water) so this fixed sequential order is a
     * defensive default for the rare edge case where more than one is simultaneously nonzero, not a
     * tuned interaction — no design doc specifies one.
     */
    public CorpseRenderState withEnvironment(EnvironmentalRenderInputs env) {
        float fullness = bodyFullness;
        float hue = hueShiftDegrees;
        float saturation = saturationMultiplier;
        float value = valueMultiplier;
        int primaryRgb = primaryBlotchRgb;
        float primaryCoverage = primaryBlotchCoverage;
        float minDiameter = blotchMinDiameter;
        float maxDiameter = blotchMaxDiameter;
        int secondaryRgb = secondaryBlotchRgb;
        float secondaryCoverage = secondaryBlotchCoverage;
        float secondaryMinDiameter = secondaryBlotchMinDiameter;
        float secondaryMaxDiameter = secondaryBlotchMaxDiameter;
        float holes = holeCoverage;
        float revealBoost = 0.0F;

        // ── Scorch: desert_sun (SUPPRESS toward absolute HSV targets) / nether_ash (additive hue,
        // multiplicative sat/val, blotch palette override with a delayed sulfur gate) ─────────────
        float scorch = clamp(env.scorchLevel());
        if (scorch > 0.0F) {
            if (EnvironmentalRenderInputs.SCORCH_STYLE_DESERT.equals(env.scorchStyle())) {
                hue = lerp(hue, DESERT_HUE_TARGET, scorch);
                saturation = lerp(saturation, DESERT_SAT_TARGET, scorch);
                value = lerp(value, DESERT_VAL_TARGET, scorch);
                holes *= (1.0F - DESERT_HOLE_SUPPRESS * scorch);
                primaryCoverage *= (1.0F - DESERT_BLOTCH_SUPPRESS * scorch);
                secondaryCoverage *= (1.0F - DESERT_BLOTCH_SUPPRESS * scorch);
            } else if (EnvironmentalRenderInputs.SCORCH_STYLE_NETHER.equals(env.scorchStyle())) {
                hue = hue + NETHER_HUE_DELTA_DEG * scorch;
                saturation = saturation * lerp(1.0F, NETHER_SAT_MULT_TARGET, scorch);
                value = value * lerp(1.0F, NETHER_VAL_MULT_TARGET, scorch);
                primaryRgb = lerpRgb(primaryRgb, NETHER_ASH_GREY_RGB, scorch);
                primaryCoverage *= (1.0F - NETHER_BLOTCH_SUPPRESS * scorch);
                float sulfurGate = clamp((scorch - NETHER_SULFUR_GATE_START) / (1.0F - NETHER_SULFUR_GATE_START));
                if (sulfurGate > 0.0F) {
                    secondaryRgb = NETHER_SULFUR_RGB;
                    secondaryCoverage = lerp(secondaryCoverage * (1.0F - NETHER_BLOTCH_SUPPRESS * scorch),
                            NETHER_SULFUR_TARGET_COVERAGE, sulfurGate);
                    // SKELETAL's base endpoint has no secondary blotch at all (diameters 0/0) — the
                    // sulfur crust "newly appears" here (nether_ash_scorch.md §2's SKELETAL row), so
                    // borrow MID/LATE's own secondary diameter range rather than leaving it degenerate.
                    if (secondaryMinDiameter <= 0.0F && secondaryMaxDiameter <= 0.0F) {
                        secondaryMinDiameter = 2.0F;
                        secondaryMaxDiameter = 5.0F;
                    }
                } else {
                    secondaryCoverage *= (1.0F - NETHER_BLOTCH_SUPPRESS * scorch);
                }
                holes *= (1.0F - NETHER_HOLE_SUPPRESS * scorch);
            }
        }

        // ── Calcify: additive hue, SUPPRESS toward absolute sat/val targets, full palette override,
        // hole suppression (never a full floor — flesh stays flesh, per base_relationship) ─────────
        float calcify = clamp(env.calcifyLevel());
        if (calcify > 0.0F) {
            hue = hue + CALCIFY_HUE_DELTA * calcify;
            saturation = lerp(saturation, CALCIFY_SAT_TARGET, calcify);
            value = lerp(value, CALCIFY_VAL_TARGET, calcify);
            primaryRgb = lerpRgb(primaryRgb, CALCIFY_PRIMARY_RGB, calcify);
            if (secondaryRgb != 0) {
                secondaryRgb = lerpRgb(secondaryRgb, CALCIFY_SECONDARY_RGB, calcify);
            }
            holes *= (1.0F - CALCIFY_HOLE_SUPPRESS * calcify);
        }

        // ── Ocean: full per-stage Endpoint blend, only when waterlogged resolves to ocean_bloat.
        // OCEAN_ENDPOINTS' coverage/holes fields equal ENDPOINTS' own stage-for-stage, so this blend
        // cannot move coverage even fractionally — only palette/diameters/fullness move. ───────────
        float waterlogged = clamp(env.waterloggedLevel());
        if (waterlogged > 0.0F && EnvironmentalRenderInputs.WATERLOGGED_STYLE_OCEAN.equals(env.waterloggedStyle())) {
            Endpoint current = new Endpoint(fullness, hue, saturation, value, primaryRgb, primaryCoverage,
                    minDiameter, maxDiameter, secondaryRgb, secondaryCoverage, secondaryMinDiameter,
                    secondaryMaxDiameter, holes);
            int index = decayStage.ordinal();
            Endpoint oceanTarget;
            if (index == 0) {
                oceanTarget = OCEAN_ENDPOINTS[0];
            } else {
                int clamped = Math.min(index, OCEAN_ENDPOINTS.length - 1);
                oceanTarget = Endpoint.lerp(OCEAN_ENDPOINTS[clamped - 1], OCEAN_ENDPOINTS[clamped], stageProgress);
            }
            Endpoint blended = Endpoint.lerp(current, oceanTarget, waterlogged);
            fullness = blended.fullness;
            hue = blended.hue;
            saturation = blended.saturation;
            value = blended.value;
            primaryRgb = blended.primaryRgb;
            primaryCoverage = blended.primaryCoverage;
            minDiameter = blended.minDiameter;
            maxDiameter = blended.maxDiameter;
            secondaryRgb = blended.secondaryRgb;
            secondaryCoverage = blended.secondaryCoverage;
            secondaryMinDiameter = blended.secondaryMinDiameter;
            secondaryMaxDiameter = blended.secondaryMaxDiameter;
            holes = blended.holes;
        }

        // ── Char: one-directional FLOOR on holes + reveal-boost, SUPPRESS on blotch coverage.
        // Applied after every other axis so lava's "regardless of age, regardless of environment"
        // override always wins the final holeCoverage value. ────────────────────────────────────
        float charLevel = clamp(env.charLevel());
        if (charLevel > 0.0F) {
            float suppression = clamp((charLevel - CHAR_SUPPRESS_START) / CHAR_SUPPRESS_RANGE);
            primaryCoverage *= (1.0F - suppression);
            secondaryCoverage *= (1.0F - suppression);
            // Shrivel the body toward the SKELETAL fullness endpoint (0.0) in step with the SAME
            // suppression curve (reaches fully-shriveled by charLevel 0.8), well ahead of where
            // revealBoost below becomes likely to actually fire (starts at 0.5, only strong past
            // ~0.7-0.8). Without this, a full-limb bone reveal could expose bare skeleton bone next
            // to an otherwise still-plump FRESH-stage body — a randomly-chosen limb (RevealArchetype
            // is deterministic per corpse UUID) going instantly bare while the rest of the corpse is
            // still full-bodied reads as a disconnected/floating rendering glitch, not deliberate
            // decay. One-directional floor (Math.min), matching char's own one-way "only gets worse"
            // semantics — never re-inflates the body once shriveled.
            fullness = Math.min(fullness, lerp(1.0F, 0.0F, suppression));
            if (charLevel >= CHAR_FLOOR_START) {
                float t = (charLevel - CHAR_FLOOR_START) / CHAR_FLOOR_RANGE;
                float charFloor = lerp(0.0F, CHAR_HOLE_FLOOR_MAX, t);
                revealBoost = lerp(0.0F, CHAR_REVEAL_BOOST_MAX, t);
                holes = Math.max(holes, charFloor);
            }
        }

        // ── Frost: coverage dampening only (skin recolour + detail composite are pixel-level
        // passes that live in CorpseFleshBaker.bake(), not here). ───────────────────────────────
        float frost = clamp(env.frostLevel());
        if (frost > 0.0F) {
            holes *= (1.0F - FROST_HOLE_SUPPRESS * frost);
            primaryCoverage *= (1.0F - FROST_BLOTCH_SUPPRESS * frost);
            secondaryCoverage *= (1.0F - FROST_BLOTCH_SUPPRESS * frost);
        }

        Endpoint result = new Endpoint(fullness, hue, saturation, value, primaryRgb, primaryCoverage,
                minDiameter, maxDiameter, secondaryRgb, secondaryCoverage, secondaryMinDiameter,
                secondaryMaxDiameter, holes);
        return new CorpseRenderState(decayStage, stageProgress, overallProgress, visualStep, result,
                revealBoost);
    }

    /**
     * SOL-DEATHCAUSE-INJURY-DESIGN.md section C1's age-only hole curve {@code H(p)}, evaluated from a
     * quantized visual step (F1 step 8 — "one package-private pure helper", nothing else: no cause,
     * part maps, or injury scalars are added to this class, and {@link #withEnvironment} is untouched).
     * {@code CorpseInjuryMask}'s local-hole acceleration samples this same 64-step-quantized curve at
     * both the corpse's real {@code visualStep} and an accelerated {@code pLocal}-derived step, so the
     * two calls always agree on quantization with the shipped global hole curve. Pure: reads only its
     * own argument, independent of environment/cause/any other per-corpse state — environment's effect
     * on the local-hole result is applied entirely by the caller via {@code environmentDecayMultiplier},
     * exactly as this class's own age-only {@link #ENDPOINTS} stay uncontaminated until {@link
     * #withEnvironment} blends them.
     */
    static float holeCurve(int visualStep) {
        float p = visualStep / (float) (VISUAL_STEPS - 1);
        if (p < 5.0F / 14.0F) return 0.0F;
        if (p < 9.0F / 14.0F) return lerp(0.00F, 0.12F, (p - 5.0F / 14.0F) / (4.0F / 14.0F));
        if (p < 12.0F / 14.0F) return lerp(0.12F, 0.34F, (p - 9.0F / 14.0F) / (3.0F / 14.0F));
        return lerp(0.34F, 0.68F, Math.min(1.0F, (p - 12.0F / 14.0F) / (2.0F / 14.0F)));
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    private static int lerpRgb(int from, int to, float progress) {
        int red = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, progress));
        int green = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, progress));
        int blue = Math.round(lerp(from & 0xFF, to & 0xFF, progress));
        return red << 16 | green << 8 | blue;
    }

    private record Endpoint(float fullness, float hue, float saturation, float value,
                            int primaryRgb, float primaryCoverage, float minDiameter,
                            float maxDiameter, int secondaryRgb, float secondaryCoverage,
                            float secondaryMinDiameter, float secondaryMaxDiameter, float holes) {

        private static Endpoint lerp(Endpoint from, Endpoint to, float progress) {
            return new Endpoint(
                    lerp(from.fullness, to.fullness, progress),
                    lerp(from.hue, to.hue, progress),
                    lerp(from.saturation, to.saturation, progress),
                    lerp(from.value, to.value, progress),
                    lerpRgb(from.primaryRgb, to.primaryRgb, progress),
                    lerp(from.primaryCoverage, to.primaryCoverage, progress),
                    lerp(from.minDiameter, to.minDiameter, progress),
                    lerp(from.maxDiameter, to.maxDiameter, progress),
                    to.secondaryRgb == 0 ? from.secondaryRgb : to.secondaryRgb,
                    lerp(from.secondaryCoverage, to.secondaryCoverage, progress),
                    lerp(from.secondaryMinDiameter, to.secondaryMinDiameter, progress),
                    lerp(from.secondaryMaxDiameter, to.secondaryMaxDiameter, progress),
                    lerp(from.holes, to.holes, progress));
        }

        private static float lerp(float from, float to, float progress) {
            return from + (to - from) * progress;
        }

        private static int lerpRgb(int from, int to, float progress) {
            int red = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, progress));
            int green = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, progress));
            int blue = Math.round(lerp(from & 0xFF, to & 0xFF, progress));
            return red << 16 | green << 8 | blue;
        }
    }
}
