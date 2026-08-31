package com.temog.notminelegacy.whatremains.entity;

import com.temog.notminelegacy.whatremains.WhatRemainsConfig;

/**
 * Named decay stages for a corpse's lifecycle.
 * <p>
 * Each stage has a fractional threshold [0.0, 1.0] of the total lifespan.
 * The actual lifespan in days is configurable via {@link WhatRemainsConfig}.
 * <p>
 * Thresholds are easy to rebalance — just change the fractions below.
 */
public enum DecayStage {

    // ── Stages (order matters — first match wins) ───────────────────────────
    //                   fraction   proportional position
    // FRESH shortened from 2/14 to 1/14 (permanent, not the debugFliesIgnoreDecayStage testing
    // flag) — a corpse now visibly starts decaying, and starts pulling in flies, in roughly half
    // the time it used to. Only the FRESH->EARLY_DECAY threshold moved; MID_DECAY/LATE_DECAY/
    // SKELETAL/COLLAPSE keep their same fractional positions, so EARLY_DECAY itself runs a little
    // longer (4/14 instead of 3/14) rather than compressing the whole curve.
    //
    // ADVANCED_DECAY inserted between LATE_DECAY and SKELETAL (playtest feedback: the jump from
    // "late decay" straight to "bare skeleton" was too abrupt, and — separately — SKELETAL itself
    // was never actually a bare exoskeleton, just the tail end of the same flesh curve LATE_DECAY
    // used). ADVANCED_DECAY takes the OLD SKELETAL's exact old fraction (12/14) and every render
    // endpoint/table entry that used to live at that position, so everything a player has already
    // seen at "12/14 decay" looks IDENTICAL post-change — nothing regresses, it's purely renamed
    // and given a successor. The NEW SKELETAL is a short final 1/14-wide stage (13/14) representing
    // total flesh loss — a literal bare exoskeleton with no flesh baked onto it at all (see
    // CorpseFleshBaker#resolve's SKELETAL guard) and no fly interest (see
    // buildingbridges.compat.alexsmobs.CorpseFlyAccessibility#isAccessible) — there's nothing left
    // to feed on. COLLAPSE (the near-instant despawn/drop-all trigger) keeps its 14/14 position.
    FRESH          (  0.0     ),  // 0/14
    EARLY_DECAY    (  1.0 / 14),  // 1/14
    MID_DECAY      (  5.0 / 14),  // 5/14
    LATE_DECAY     (  9.0 / 14),  // 9/14
    ADVANCED_DECAY ( 12.0 / 14),  // 12/14 — old SKELETAL's position, look unchanged
    SKELETAL       ( 13.0 / 14),  // 13/14 — NEW: short final bare-exoskeleton stage
    COLLAPSE       (  1.0     );  // 14/14

    // ── Fields ──────────────────────────────────────────────────────────────

    private final double fraction;

    DecayStage(double fraction) {
        this.fraction = fraction;
    }

    /** The tick at which this stage begins, scaled to the configured lifespan. */
    public int getStartTick() {
        return (int) (fraction * getTotalLifespanTicks());
    }

    /**
     * The tick at which this stage ends (exclusive).
     * For the last stage this returns {@code getTotalLifespanTicks() + 1}.
     */
    public int getEndTick() {
        DecayStage[] stages = values();
        int idx = ordinal();
        if (idx + 1 < stages.length) {
            return stages[idx + 1].getStartTick();
        }
        return getTotalLifespanTicks() + 1;
    }

    /** Duration of this stage in ticks. */
    public int getDuration() {
        return getEndTick() - getStartTick();
    }

    // ── Configurable lifespan ───────────────────────────────────────────────

    /** Total corpse lifespan in ticks, read from config. */
    public static int getTotalLifespanTicks() {
        return WhatRemainsConfig.decayLifespanTicks;
    }

    // ── Lookup helpers ──────────────────────────────────────────────────────

    /**
     * Returns the decay stage for the given corpse age in ticks.
     * Clamps to {@link #COLLAPSE} if age exceeds total lifespan.
     */
    public static DecayStage fromAge(int ageTicks) {
        DecayStage[] stages = values();
        for (int i = stages.length - 1; i >= 0; i--) {
            if (ageTicks >= stages[i].getStartTick()) {
                return stages[i];
            }
        }
        return FRESH;
    }

    /**
     * Returns fractional progress [0.0, 1.0) within the current stage.
     * At exactly the start of a stage this returns 0.0.
     * Just before the next stage threshold this approaches 1.0.
     */
    public static float progressWithinStage(int ageTicks) {
        DecayStage stage = fromAge(ageTicks);
        int elapsed = ageTicks - stage.getStartTick();
        int duration = stage.getDuration();
        if (duration <= 0) return 1.0f;
        return Math.min((float) elapsed / duration, 1.0f);
    }

    /**
     * Returns overall decay progress [0.0, 1.0] across the entire lifespan.
     * 0.0 = just spawned, 1.0 = at or past collapse.
     */
    public static float overallProgress(int ageTicks) {
        int total = getTotalLifespanTicks();
        if (total <= 0) return 1.0f;
        return Math.min((float) ageTicks / total, 1.0f);
    }

    /** True if the corpse has reached or passed the COLLAPSE stage. */
    public static boolean isReadyForFinalDrop(int ageTicks) {
        return ageTicks >= COLLAPSE.getStartTick();
    }
}
