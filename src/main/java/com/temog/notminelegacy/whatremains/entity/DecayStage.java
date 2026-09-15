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
    // and given a successor. The NEW SKELETAL starts at that same 13/14 position, representing
    // total flesh loss — a literal bare exoskeleton with no flesh baked onto it at all (see
    // CorpseFleshBaker#resolve's SKELETAL guard) and no fly interest (see
    // buildingbridges.compat.alexsmobs.CorpseFlyAccessibility#isAccessible) — there's nothing left
    // to feed on. Its DURATION, though, is deliberately NOT the leftover 1/14 sliver this fraction
    // table implies: bone degrades far slower than flesh, so SKELETAL reads its own independently
    // configured (and by default much longer) duration from WhatRemainsConfig.skeletalStageLifespanTicks
    // instead of being scaled off the shared fraction/total like every other stage — see
    // getDuration()/getStartTick() and getFullLifespanTicks()'s own notes for exactly how that's
    // wired without disturbing FRESH..ADVANCED_DECAY's existing (unrelated) pacing or the render
    // pipeline's flesh-decay gradient. COLLAPSE begins wherever that extended SKELETAL stage
    // actually ends, whatever tick that turns out to be — its own "14/14" fraction below is now
    // purely nominal/unused (see getStartTick()'s COLLAPSE override).
    FRESH          (  0.0     ),  // 0/14
    EARLY_DECAY    (  1.0 / 14),  // 1/14
    MID_DECAY      (  5.0 / 14),  // 5/14
    LATE_DECAY     (  9.0 / 14),  // 9/14
    ADVANCED_DECAY ( 12.0 / 14),  // 12/14 — old SKELETAL's position, look unchanged
    SKELETAL       ( 13.0 / 14),  // 13/14 start; duration is its OWN config value, not 1/14 of total
    COLLAPSE       (  1.0     );  // fraction unused -- see getStartTick()'s COLLAPSE override

    // ── Fields ──────────────────────────────────────────────────────────────

    private final double fraction;

    DecayStage(double fraction) {
        this.fraction = fraction;
    }

    /**
     * The tick at which this stage begins.
     * <p>
     * Every stage except {@link #COLLAPSE} is scaled off {@link #getTotalLifespanTicks()} exactly
     * as before. {@link #COLLAPSE} is the one exception (see its own note on
     * {@link #getFullLifespanTicks()}): it begins wherever {@link #SKELETAL} actually ends, which is
     * no longer a fixed fraction of {@link #getTotalLifespanTicks()} now that Skeletal has its own,
     * independently configured (and typically much longer) duration.
     */
    public int getStartTick() {
        if (this == COLLAPSE) {
            return SKELETAL.getStartTick() + SKELETAL.getDuration();
        }
        return (int) (fraction * getTotalLifespanTicks());
    }

    /**
     * The tick at which this stage ends (exclusive). Unchanged for every stage except
     * {@link #COLLAPSE} (the last stage, effectively terminal — nothing meaningfully reads its end
     * tick) — {@link #SKELETAL}'s own end automatically falls out of this method correctly once
     * {@link #COLLAPSE#getStartTick()} reflects its extended duration, since this still just returns
     * "the next stage's start tick" for every non-terminal stage.
     */
    public int getEndTick() {
        DecayStage[] stages = values();
        int idx = ordinal();
        if (idx + 1 < stages.length) {
            return stages[idx + 1].getStartTick();
        }
        return getStartTick() + 1;
    }

    /**
     * Duration of this stage in ticks.
     * <p>
     * {@link #SKELETAL} is special-cased to read its own independently configured duration
     * ({@link WhatRemainsConfig#skeletalStageLifespanTicks}) directly, rather than deriving it from
     * {@code getEndTick() - getStartTick()} — that generic formula would recurse infinitely here,
     * since {@code SKELETAL.getEndTick()} resolves through {@code COLLAPSE.getStartTick()}, which
     * itself is defined in terms of {@code SKELETAL.getDuration()} (see {@link #getStartTick()}).
     */
    public int getDuration() {
        if (this == SKELETAL) {
            return WhatRemainsConfig.skeletalStageLifespanTicks;
        }
        return getEndTick() - getStartTick();
    }

    // ── Configurable lifespan ───────────────────────────────────────────────

    /**
     * Lifespan in ticks covering FRESH..ADVANCED_DECAY only (i.e. everything up to where Skeletal
     * begins), read from config. Deliberately NOT the whole corpse lifecycle — see
     * {@link #getFullLifespanTicks()} for that, and its own note on why the two must stay separate.
     */
    public static int getTotalLifespanTicks() {
        return WhatRemainsConfig.decayLifespanTicks;
    }

    /**
     * The REAL total corpse lifespan in ticks, including Skeletal's own (typically much longer,
     * independently configured) duration — i.e. the tick at which {@link #COLLAPSE} begins. Use this
     * (not {@link #getTotalLifespanTicks()}) for anything reporting overall time-to-live, such as a
     * "time until collapse" UI line.
     * <p>
     * Deliberately kept separate from {@link #getTotalLifespanTicks()} rather than folding Skeletal's
     * duration into that same figure: {@link com.temog.notminelegacy.whatremains.client.CorpseRenderState#fromAge}
     * quantises FRESH..ADVANCED_DECAY's flesh-decay gradient into a fixed number of visual steps
     * scaled against {@link #getTotalLifespanTicks()}, and {@link com.temog.notminelegacy.whatremains.client.CorpseFleshBaker#resolve}
     * never bakes flesh for SKELETAL/COLLAPSE at all (they render as a bare, static skeleton) — so
     * widening that denominator to include a long Skeletal stage would only crush the already-tuned
     * FRESH..ADVANCED_DECAY gradient down to a handful of visual steps, for a stage that never reads
     * the result anyway.
     */
    public static int getFullLifespanTicks() {
        return COLLAPSE.getStartTick();
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
