package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.temog.notminelegacy.whatremains.client.CorpseAnatomy.Landmark;
import com.temog.notminelegacy.whatremains.client.CorpseAnatomy.Selector;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Face;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Part;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.RevealArchetype;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SplitMix64;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SurfaceTexel;
import com.temog.notminelegacy.whatremains.entity.DeathCause;

import javax.annotation.Nullable;

/**
 * SOL-DEATHCAUSE-INJURY-DESIGN.md section A1's per-{@link DeathCause} numeric table, encoded as an
 * exhaustive {@code switch(DeathCause)} with NO {@code default} branch — a future {@link DeathCause}
 * constant must fail compilation here until this table is extended for it (F1 step 2's explicit
 * requirement).
 * <p>
 * <b>Two-phase resolution, deliberately split so {@code CorpseHeldItemLayer} never needs {@link
 * CorpseAnatomy}/{@code slim}:</b>
 * <ol>
 *   <li>{@link #resolveLocus} — cheap, pure, needs only {@link DeathCause}, {@code appearanceSeed}
 *   and (for {@code RANGED}) the killing anchor. Resolves every corpse-specific "which concrete
 *   {@link Part}(s) does this cause target" draw (dominant limb, seeded side, weighted part roll,
 *   ...) into a {@link ResolvedLocus} — the SAME top-level parts both the pigment/hole geometry
 *   (below) and {@code CorpseHeldItemLayer}'s pose rule (RECONCILIATION.md R5) must agree on. Safe to
 *   call every render frame.</li>
 *   <li>{@link #resolveBands} — needs {@link CorpseAnatomy}/{@link CorpseFleshMask} to turn a
 *   {@link ResolvedLocus} into actual surface-texel index sets. Only ever called once per corpse by
 *   {@link CorpseInjuryMask} (which caches the result), never per-frame.</li>
 * </ol>
 * Both phases redraw the identical seeded sub-choices from the identical {@code causeSeed} rather
 * than passing an opaque "choices" bag between them — SplitMix64 determinism guarantees this is
 * exactly as safe as caching the choices would be, and it is what keeps phase 1 free of any
 * {@link CorpseAnatomy} dependency.
 * <p>
 * <b>Documented interpretation gaps</b> (mirroring {@link CorpsePatchStitcher}'s own "the design text
 * is prose, not pseudocode, in a few places" precedent — this table has the same kind):
 * <ul>
 *   <li>Per-cause literal per-lobe texel counts (e.g. BURNED's "22 texels with an 8-texel dry
 *   branch") are summed into one {@code primaryFreshTarget} and grown as a single connected blob
 *   from one anchor, rather than as separately-placed lobes. {@link MorphologyTemplate#directional}
 *   still distinguishes a line-like walk (SLASH, EDGE_SHEAR, the LIGHTNING branch) from radial/blob
 *   growth (everything else) — see {@link CorpseInjuryMask}'s growth engine.</li>
 *   <li>Where a row's primary/secondary column spans more than one top-level {@link Part} (e.g.
 *   BURNED's "STERNUM plus one seed-chosen arm"), the {@link ResolvedLocus} reveal candidate is the
 *   seed-DRAWN part (the arm), not the fixed landmark's part (BODY) — the drawn choice is the more
 *   narratively distinctive candidate and is explicitly named in A2's stage prose ("hand an existing
 *   reveal to the injured arm").</li>
 *   <li>FROZEN/FALL/STARVED's three listed P/S bias numbers (dominant/other/secondary) are three
 *   {@link CorpseInjuryMask} bands, not two — C1's "primary and secondary selector" wording still
 *   holds once "primary" is read as itself containing a dominant/other split (matching how the A1
 *   region column literally describes it: "one seed-chosen dominant ... THEN the other three").</li>
 *   <li>D2's per-cell "special" recolour/behaviour prose (the 7x14 matrix) is NOT individually
 *   encoded here — only D1's general numeric attenuation (bucket-representative min-of-lerp) plus the
 *   two cells that give an explicit NUMBER rather than prose (BURNED×char>=0.5, DROWNED×ocean) are
 *   implemented, in {@code CorpseFleshBaker}. The remaining ~90 cells are unverifiable without
 *   screenshots this session and are flavour text over an already-attenuated result, not a distinct
 *   numeric rule — see the handover report for this explicit, deliberate scope reduction.</li>
 * </ul>
 */
final class DeathCauseInjuryProfile {

    // ── Deterministic seed discipline (design's "Shared anatomical vocabulary" -> "Common procedural
    // and colour rules" section, RECONCILIATION.md R2/CRITICAL CORRECTNESS: seeded from appearanceSeed
    // with injury-only salts, never a PST_* salt, never corpse UUID). ASCII-encoded 8-byte constants,
    // same construction CorpseAppearanceLayout's PST_* table uses — first four are the design doc's own
    // literal INJURY_*_SALT values (renamed to the INJ_ house style; the underlying bits are unchanged
    // and therefore still guaranteed distinct from every PST_* salt), the rest are additional axes this
    // implementation needs that the design's own shorter salt list didn't enumerate individually. ─────
    private static final long INJ_LAYOUT = 0x494E4A5552595F31L;
    private static final long INJ_VARIETY = 0x564152494554595FL;
    private static final long INJ_REVEAL = 0x52455645414C5F50L;
    private static final long INJ_MORPH = 0x4D4F5250485F504BL;
    private static final long INJ_CHOICE = 0x494E4A5F43484F49L;
    private static final long INJ_SECONDARY = 0x494E4A5F5345434FL;
    private static final long INJ_PART = 0x494E4A5F50415254L;
    private static final long INJ_ROOT = 0x494E4A5F524F4F54L;
    private static final long INJ_ANCHOR = 0x494E4A5F414E4348L;
    private static final long INJ_CUTOUT = 0x494E4A5F4355544FL;

    private static final Part[] LIMBS =
            {Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG};

    private DeathCauseInjuryProfile() {}

    // ── Phase 0: cause seed / shared draw helpers ───────────────────────────────────────────────────

    /** {@code causeSeed = mix(appearanceSeed ^ INJ_LAYOUT ^ name().hashCode()*GOLDEN)} — the design's
     *  literal construction, salted so a future enum reorder cannot move a corpse's injury layout. */
    static long causeSeed(long appearanceSeed, DeathCause cause) {
        long nameBits = Integer.toUnsignedLong(cause.name().hashCode());
        return SplitMix64.mix(appearanceSeed ^ INJ_LAYOUT ^ (nameBits * 0x9E3779B97F4A7C15L));
    }

    private static long draw(long causeSeed, long salt) {
        return SplitMix64.mix(causeSeed ^ salt);
    }

    /** Indexed sub-draw so a cause needing more than one independent value off the same salt (e.g.
     *  "dominant limb" then "one of the other three") never reuses a stream, mirroring {@code
     *  CorpseAppearanceLayout#attemptSeed}'s own indexed-draw pattern. */
    private static long draw(long causeSeed, long salt, int index) {
        return SplitMix64.mix(causeSeed ^ salt ^ ((index + 1L) * 0xD1B54A32D192ED03L));
    }

    private static int percentRoll(long value) {
        return (int) Long.remainderUnsigned(value, 100);
    }

    private static float unitFloat(long value) {
        return (value >>> 40) * 0x1.0p-24F;
    }

    /** A1's "±10% in size/strength" per-corpse variety scalar — one value per corpse, applied to
     *  every band's bias AND every band's pigment footprint/weight (the "Common procedural and colour
     *  rules" section's explicit multiplicative role for {@code variety}). */
    static float variety(long causeSeed) {
        return 0.90F + 0.20F * unitFloat(draw(causeSeed, INJ_VARIETY));
    }

    static long revealDrawSeed(long causeSeed) {
        return draw(causeSeed, INJ_REVEAL);
    }

    static long morphDrawSeed(long causeSeed) {
        return draw(causeSeed, INJ_MORPH);
    }

    static long cutoutDrawSeed(long causeSeed) {
        return draw(causeSeed, INJ_CUTOUT);
    }

    static long anchorDrawSeed(long causeSeed) {
        return draw(causeSeed, INJ_ANCHOR);
    }

    private static Part drawLimb(long causeSeed, int index) {
        long v = draw(causeSeed, INJ_CHOICE, index);
        return LIMBS[(int) Long.remainderUnsigned(v, LIMBS.length)];
    }

    private static Part drawOtherLimb(long causeSeed, int index, Part exclude) {
        Part[] remaining = new Part[3];
        int c = 0;
        for (Part p : LIMBS) if (p != exclude) remaining[c++] = p;
        long v = draw(causeSeed, INJ_CHOICE, index);
        return remaining[(int) Long.remainderUnsigned(v, remaining.length)];
    }

    private static boolean drawBoolean(long causeSeed, long salt, int index) {
        return percentRoll(draw(causeSeed, salt, index)) < 50;
    }

    /** First-match-wins weighted draw over the literal cumulative percentage thresholds a row gives
     *  (e.g. EXPLODED's "BODY 0-34, HEAD 35-44, ..."). {@code upperBoundsExclusive} are cumulative. */
    private static Part weightedPart(long value, Part[] parts, int[] upperBoundsExclusive) {
        int roll = percentRoll(value);
        for (int i = 0; i < parts.length; i++) {
            if (roll < upperBoundsExclusive[i]) return parts[i];
        }
        return parts[parts.length - 1];
    }

    // ── A1's "adjacent-part fallback", used by EXPLODED/MAGIC/MELEE/RANGED's secondary column ───────
    // BODY -> a seeded arm root then JAW; HEAD -> STERNUM; ARM -> that arm's JOINT plus same-side BODY
    // strip; LEG -> that leg's JOINT plus PELVIS. The reveal candidate for the fallback is BODY in
    // every case except BODY's own fallback, where it is the seeded arm (again: prefer the drawn part).
    private static Part fallbackReveal(Part seededPart, long causeSeed) {
        if (seededPart == Part.BODY) {
            return drawBoolean(causeSeed, INJ_SECONDARY, 0) ? Part.RIGHT_ARM : Part.LEFT_ARM;
        }
        return Part.BODY;
    }

    // ── EXPLODED (A1: BODY 0-34, HEAD 35-44, RA 45-58, LA 59-72, RL 73-86, LL 87-99) ────────────────
    private static final Part[] EXPLODED_PARTS =
            {Part.BODY, Part.HEAD, Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG};
    private static final int[] EXPLODED_BOUNDS = {35, 45, 59, 73, 87, 100};

    // ── MAGIC (A1: BODY 0-44, HEAD 45-69, RA 70-76, LA 77-83, RL 84-91, LL 92-99) ───────────────────
    private static final Part[] MAGIC_PARTS =
            {Part.BODY, Part.HEAD, Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG};
    private static final int[] MAGIC_BOUNDS = {45, 70, 77, 84, 92, 100};

    // ── RANGED fallback-without-projectile (A1: BODY 45%, HEAD 15%, each limb 10%) ──────────────────
    private static final Part[] RANGED_FALLBACK_PARTS =
            {Part.BODY, Part.HEAD, Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG};
    private static final int[] RANGED_FALLBACK_BOUNDS = {45, 60, 70, 80, 90, 100};

    // ── MELEE (A1: BODY 35%, arms 30% total, legs 25% total, HEAD 10%) ─────────────────────────────
    private static final Part[] MELEE_PARTS =
            {Part.BODY, Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG, Part.HEAD};
    private static final int[] MELEE_BOUNDS = {35, 50, 65, 78, 90, 100};

    // ── Phase 1: top-level part resolution (no CorpseAnatomy needed) ───────────────────────────────

    /**
     * Every {@link Part}-level seeded choice a cause makes, resolved once from {@code causeSeed} (and
     * the killing anchor, for RANGED). {@code primaryPart}/{@code secondaryPart} double as BOTH the
     * full-part-reveal preference candidates (C2) AND {@code CorpseHeldItemLayer}'s "primary top-level
     * part" (RECONCILIATION.md R5) — null only for {@link DeathCause#NATURAL}, which has no cause
     * reveal preference at all (100% legacy).
     */
    record ResolvedLocus(@Nullable Part primaryPart, @Nullable Part secondaryPart,
                         boolean rootAtSternum, boolean sideIsRight) {}

    static ResolvedLocus resolveLocus(DeathCause cause, long appearanceSeed, @Nullable InjuryRenderInputs.Anchor killingAnchor) {
        long seed = causeSeed(appearanceSeed, cause);
        return switch (cause) {
            case BURNED -> {
                Part arm = drawBoolean(seed, INJ_CHOICE, 0) ? Part.RIGHT_ARM : Part.LEFT_ARM;
                Part other = arm == Part.RIGHT_ARM ? Part.LEFT_ARM : Part.RIGHT_ARM;
                yield new ResolvedLocus(arm, other, false, false);
            }
            case FROZEN -> {
                Part dominant = drawLimb(seed, 0);
                Part other = drawOtherLimb(seed, 1, dominant);
                yield new ResolvedLocus(dominant, other, false, false);
            }
            case DROWNED -> {
                Part other = drawLimb(seed, 0);
                yield new ResolvedLocus(Part.HEAD, other, false, false);
            }
            case LIGHTNING -> {
                boolean sternum = drawBoolean(seed, INJ_ROOT, 0);
                Part exit = drawLimb(seed, 0);
                yield new ResolvedLocus(exit, Part.BODY, sternum, false);
            }
            case EXPLODED -> {
                Part part = weightedPart(draw(seed, INJ_PART, 0), EXPLODED_PARTS, EXPLODED_BOUNDS);
                yield new ResolvedLocus(part, fallbackReveal(part, seed), false, false);
            }
            case VOID -> {
                boolean right = drawBoolean(seed, INJ_CHOICE, 0);
                yield new ResolvedLocus(right ? Part.RIGHT_ARM : Part.LEFT_ARM, Part.BODY, false, right);
            }
            case FALL -> {
                boolean rightDominant = drawBoolean(seed, INJ_CHOICE, 0);
                Part dominant = rightDominant ? Part.RIGHT_LEG : Part.LEFT_LEG;
                Part other = rightDominant ? Part.LEFT_LEG : Part.RIGHT_LEG;
                yield new ResolvedLocus(dominant, other, false, false);
            }
            case CRUSHED -> {
                Part[] pool = {Part.HEAD, Part.RIGHT_ARM, Part.LEFT_ARM};
                Part pick = pool[(int) Long.remainderUnsigned(draw(seed, INJ_SECONDARY, 0), pool.length)];
                yield new ResolvedLocus(Part.BODY, pick, false, false);
            }
            case WITHERED -> new ResolvedLocus(Part.BODY, drawLimb(seed, 0), false, false);
            case STARVED -> {
                Part dominant = drawLimb(seed, 0);
                Part other = drawOtherLimb(seed, 1, dominant);
                yield new ResolvedLocus(dominant, other, false, false);
            }
            case MAGIC -> {
                Part part = weightedPart(draw(seed, INJ_PART, 0), MAGIC_PARTS, MAGIC_BOUNDS);
                yield new ResolvedLocus(part, fallbackReveal(part, seed), false, false);
            }
            case RANGED -> {
                Part part;
                if (killingAnchor != null) {
                    part = Part.values()[killingAnchor.part()];
                } else {
                    part = weightedPart(draw(seed, INJ_PART, 0), RANGED_FALLBACK_PARTS, RANGED_FALLBACK_BOUNDS);
                }
                yield new ResolvedLocus(part, fallbackReveal(part, seed), false, false);
            }
            case MELEE -> {
                Part part = weightedPart(draw(seed, INJ_PART, 0), MELEE_PARTS, MELEE_BOUNDS);
                yield new ResolvedLocus(part, fallbackReveal(part, seed), false, false);
            }
            case NATURAL -> new ResolvedLocus(null, null, false, false);
        };
    }

    // ── Static per-cause constants (F1 step 2's literal A1 numbers) ────────────────────────────────

    record Profile(boolean directional, int primaryFreshTarget, int coreRgb, int edgeRgb,
                   float pigmentWeight, boolean paintsPigment, int fixedCutoutCount,
                   boolean cutoutDelayedToEarlyDecay, int primaryRevealWeight,
                   int secondaryRevealWeight, int legacyRevealWeight) {}

    static Profile profileFor(DeathCause cause) {
        return switch (cause) {
            case BURNED -> new Profile(false, 30, 0x241B18, 0x6A3A2A, 0.58F, true, 0, false, 70, 20, 10);
            case FROZEN -> new Profile(false, 32, 0x526879, 0xAAB9C4, 0.52F, true, 0, false, 60, 30, 10);
            case DROWNED -> new Profile(false, 27, 0x475E68, 0x78939A, 0.50F, true, 0, false, 65, 25, 10);
            case LIGHTNING -> new Profile(true, 33, 0x2A161F, 0x6B3D49, 0.62F, true, 0, false, 70, 20, 10);
            case EXPLODED -> new Profile(false, 22, 0x251B18, 0x704336, 0.64F, true, 4, false, 75, 15, 10);
            case VOID -> new Profile(true, 19, 0x171223, 0x44335E, 0.60F, true, 2, true, 65, 25, 10);
            case FALL -> new Profile(false, 36, 0x46242A, 0x75434B, 0.62F, true, 0, false, 80, 15, 5);
            case CRUSHED -> new Profile(false, 36, 0x3A272A, 0x6C494A, 0.60F, true, 0, false, 70, 20, 10);
            case WITHERED -> new Profile(false, 0, 0x201014, 0x201819, 0.0F, false, 0, false, 70, 20, 10);
            case STARVED -> new Profile(false, 35, 0x615A4D, 0x968B72, 0.42F, true, 0, false, 60, 30, 10);
            case MAGIC -> new Profile(false, 22, 0x32203D, 0x6A466B, 0.58F, true, 0, false, 70, 20, 10);
            case RANGED -> new Profile(false, 9, 0x421D24, 0x7A3D43, 0.66F, true, 1, false, 85, 10, 5);
            case MELEE -> new Profile(true, 17, 0x4B2327, 0x824248, 0.62F, true, 0, false, 75, 15, 10);
            case NATURAL -> new Profile(false, 20, 0x4B3B45, 0x6F5A5F, 0.30F, true, 0, false, 0, 0, 100);
        };
    }

    /**
     * Builds the {@link CorpseAppearanceLayout.RevealPreference} C2 asks {@code
     * CorpseAppearanceLayout#applyFullPartReveal} to bias its already-active reveal event toward —
     * deliberately anatomy-free (only {@link Part}/{@link DeathCause} enums) so {@code
     * CorpseHeldItemLayer} can call it every render frame (RECONCILIATION.md R5's "force SKELETAL
     * beside-hand pose when a RIGHT_ARM reveal is active" rule) without constructing a full {@link
     * CorpseInjuryMask}, which is otherwise the only other consumer. Returns {@code null} for {@link
     * DeathCause#NATURAL} (100% legacy — no cause preference at all, matching A1's own "no cause
     * preference" row), exactly as the null-preference branch in {@code applyFullPartReveal} expects.
     */
    static CorpseAppearanceLayout.RevealPreference revealPreferenceFor(DeathCause cause, ResolvedLocus locus) {
        if (locus.primaryPart() == null) return null;
        Profile profile = profileFor(cause);
        RevealArchetype primary = archetypeFor(locus.primaryPart());
        RevealArchetype secondary = locus.secondaryPart() != null ? archetypeFor(locus.secondaryPart()) : null;
        return new CorpseAppearanceLayout.RevealPreference(primary, profile.primaryRevealWeight(),
                secondary, profile.secondaryRevealWeight(), profile.legacyRevealWeight());
    }

    private static RevealArchetype archetypeFor(Part part) {
        return switch (part) {
            case HEAD -> RevealArchetype.HEAD;
            case BODY -> RevealArchetype.TORSO;
            case RIGHT_ARM -> RevealArchetype.RIGHT_ARM;
            case LEFT_ARM -> RevealArchetype.LEFT_ARM;
            case RIGHT_LEG -> RevealArchetype.RIGHT_LEG;
            case LEFT_LEG -> RevealArchetype.LEFT_LEG;
        };
    }

    // ── Phase 2: region-band resolution (needs CorpseAnatomy/CorpseFleshMask) ──────────────────────

    /** One anatomical target region with its own C1 local-hole/pigment bias. Index 0 is always the
     *  cause's "primary"/dominant band — the only one that receives the full morphology treatment
     *  ({@link Profile#directional}, {@link Profile#primaryFreshTarget}); later bands always receive
     *  simple single-blob growth scaled by their bias ratio to band 0 (see {@link CorpseInjuryMask}). */
    record RegionBand(int[] texels, float bias) {}

    static List<RegionBand> resolveBands(DeathCause cause, ResolvedLocus locus, CorpseAnatomy anatomy,
                                         CorpseFleshMask mask, @Nullable InjuryRenderInputs.Anchor killingAnchor) {
        List<RegionBand> bands = new ArrayList<>(3);
        switch (cause) {
            case BURNED -> {
                Part arm = locus.primaryPart();
                Part other = locus.secondaryPart();
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.STERNUM, t) || t.part() == arm), 0.18F));
                bands.add(new RegionBand(select(mask, t -> (t.part() == other && anatomy.hasLandmark(t, Landmark.LIMB_END))
                        || anatomy.matches(Selector.JAW, t)), 0.08F));
            }
            case FROZEN -> {
                Part dominant = locus.primaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == dominant && anatomy.hasLandmark(t, Landmark.LIMB_END)), 0.18F));
                bands.add(new RegionBand(select(mask, t -> isLimb(t.part()) && t.part() != dominant
                        && anatomy.hasLandmark(t, Landmark.LIMB_END)), 0.13F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.JAW, t) || anatomy.matches(Selector.ALL_JOINTS, t)), 0.07F));
            }
            case DROWNED -> {
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.JAW, t) || lowerSternum(anatomy, t)), 0.14F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.ALL_LIMB_ENDS, t)), 0.07F));
            }
            case LIGHTNING -> {
                Part exit = locus.primaryPart();
                boolean sternum = locus.rootAtSternum();
                bands.add(new RegionBand(select(mask, t -> (sternum ? anatomy.matches(Selector.STERNUM, t) : backCenter(t))
                        || (t.part() == exit && anatomy.hasLandmark(t, Landmark.LIMB_END))), 0.22F));
                bands.add(new RegionBand(select(mask, t -> t.part() == exit && isLimbRoot(anatomy, t)), 0.11F));
            }
            case EXPLODED -> {
                Part part = locus.primaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == part), 0.32F));
                bands.add(new RegionBand(fallbackRegion(anatomy, mask, part, locus.secondaryPart()), 0.14F));
            }
            case VOID -> {
                boolean right = locus.sideIsRight();
                bands.add(new RegionBand(select(mask, t -> isSideChain(anatomy, t, right)), 0.24F));
                bands.add(new RegionBand(select(mask, t -> isSideStrip(anatomy, t, !right) || anatomy.matches(Selector.JAW, t)), 0.10F));
            }
            case FALL -> {
                Part dominant = locus.primaryPart();
                Part other = locus.secondaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == dominant
                        && (anatomy.hasLandmark(t, Landmark.JOINT) || anatomy.hasLandmark(t, Landmark.LIMB_END))), 0.30F));
                bands.add(new RegionBand(select(mask, t -> t.part() == other && anatomy.hasLandmark(t, Landmark.JOINT)), 0.22F));
                bands.add(new RegionBand(select(mask, DeathCauseInjuryProfile::pelvis), 0.08F));
            }
            case CRUSHED -> {
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.STERNUM, t) || backCenter(t)), 0.28F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.JAW, t)
                        || (isArm(t.part()) && isLimbRoot(anatomy, t)) || pelvis(t)), 0.12F));
            }
            case WITHERED -> {
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.STERNUM, t)), 0.24F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.ALL_LIMB_ROOTS, t)), 0.10F));
            }
            case STARVED -> {
                Part dominant = locus.primaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == dominant && anatomy.hasLandmark(t, Landmark.LIMB_END)), 0.16F));
                bands.add(new RegionBand(select(mask, t -> (isLimb(t.part()) && t.part() != dominant
                        && anatomy.hasLandmark(t, Landmark.LIMB_END)) || anatomy.matches(Selector.JAW, t)), 0.13F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.ALL_JOINTS, t) || anatomy.matches(Selector.STERNUM, t)), 0.07F));
            }
            case MAGIC -> {
                Part part = locus.primaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == part), 0.21F));
                bands.add(new RegionBand(fallbackRegion(anatomy, mask, part, locus.secondaryPart()), 0.09F));
            }
            case RANGED -> {
                Part hitPart = locus.primaryPart();
                if (killingAnchor != null) {
                    int[] ring = anatomy.anchorDisc(hitPart, killingAnchor.px(), killingAnchor.py(), killingAnchor.pz(), 1);
                    bands.add(new RegionBand(ring, 0.26F));
                    int[] halo = anatomy.anchorDisc(hitPart, killingAnchor.px(), killingAnchor.py(), killingAnchor.pz(), 2);
                    int[] fallback = fallbackRegion(anatomy, mask, hitPart, locus.secondaryPart());
                    bands.add(new RegionBand(concat(halo, fallback), 0.11F));
                } else {
                    bands.add(new RegionBand(select(mask, t -> t.part() == hitPart), 0.26F));
                    bands.add(new RegionBand(fallbackRegion(anatomy, mask, hitPart, locus.secondaryPart()), 0.11F));
                }
            }
            case MELEE -> {
                Part part = locus.primaryPart();
                bands.add(new RegionBand(select(mask, t -> t.part() == part), 0.23F));
                bands.add(new RegionBand(fallbackRegion(anatomy, mask, part, locus.secondaryPart()), 0.10F));
            }
            case NATURAL -> {
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.STERNUM, t) || anatomy.matches(Selector.JAW, t)), 0.08F));
                bands.add(new RegionBand(select(mask, t -> anatomy.matches(Selector.ALL_LIMB_ENDS, t)), 0.04F));
            }
        }
        return bands;
    }

    /** The exact killing-projectile anchor texel (radius 0), for RANGED's single fixed cutout — must
     *  sit precisely under the rendered arrow, never a randomly-picked texel within a region (E1). */
    static int rangedKillingSocket(CorpseAnatomy anatomy, InjuryRenderInputs.Anchor anchor) {
        int[] socket = anatomy.anchorDisc(Part.values()[anchor.part()], anchor.px(), anchor.py(), anchor.pz(), 0);
        return socket.length > 0 ? socket[0] : -1;
    }

    // ── Region predicates not already expressible as a CorpseAnatomy.Selector — see RECONCILIATION.md
    // R1's PELVIS/BACK_CENTER/SIDE_STRIP/SIDE_CHAIN definitions, applied directly to SurfaceTexel since
    // CorpseAnatomy's own Selector enum (patch-stitch's base) does not carry these five names. ────────
    private static boolean pelvis(SurfaceTexel t) {
        if (t.part() != Part.BODY) return false;
        if (t.face() == Face.DOWN) return true;
        if (t.face() == Face.UP) return false;
        return t.y() >= 8 && t.y() <= 11;
    }

    private static boolean backCenter(SurfaceTexel t) {
        return t.part() == Part.BODY && t.face() == Face.SOUTH && t.x() >= 2 && t.x() <= 5 && t.y() >= 2 && t.y() <= 9;
    }

    /** "Lower STERNUM" (DROWNED) — the bottom half of the existing STERNUM landmark, using the same
     *  y<=5 upper / y>=6 lower split {@code CorpseAnatomy}'s own (private) upper/lower torso boundary
     *  uses, so this reads as one consistent torso midline across both classes. */
    private static boolean lowerSternum(CorpseAnatomy anatomy, SurfaceTexel t) {
        return anatomy.hasLandmark(t, Landmark.STERNUM) && t.y() >= 6;
    }

    /** SIDE_STRIP(side): BODY west/east face (matching that side) plus the same-side arm/leg roots.
     *  WEST = RIGHT, EAST = LEFT — {@code CorpseFleshModel#createBodyLayer} pivots {@code right_arm}
     *  at negative local X and {@code left_arm} at positive local X, and a box's WEST face is always
     *  its negative-X face, so WEST is anatomically the corpse's own right side (matching {@code
     *  StuckProjectile.PART_*}, never screen-left/right after the face-down render rotation). */
    private static boolean isSideStrip(CorpseAnatomy anatomy, SurfaceTexel t, boolean right) {
        Face sideFace = right ? Face.WEST : Face.EAST;
        if (t.part() == Part.BODY && t.face() == sideFace) return true;
        Part arm = right ? Part.RIGHT_ARM : Part.LEFT_ARM;
        Part leg = right ? Part.RIGHT_LEG : Part.LEFT_LEG;
        return (t.part() == arm || t.part() == leg) && isLimbRoot(anatomy, t);
    }

    private static boolean isSideChain(CorpseAnatomy anatomy, SurfaceTexel t, boolean right) {
        Part arm = right ? Part.RIGHT_ARM : Part.LEFT_ARM;
        Part leg = right ? Part.RIGHT_LEG : Part.LEFT_LEG;
        return t.part() == arm || t.part() == leg || isSideStrip(anatomy, t, right);
    }

    private static boolean isLimbRoot(CorpseAnatomy anatomy, SurfaceTexel t) {
        return anatomy.matches(Selector.ALL_LIMB_ROOTS, t);
    }

    private static int[] fallbackRegion(CorpseAnatomy anatomy, CorpseFleshMask mask, Part seededPart, Part fallbackArm) {
        return switch (seededPart) {
            case BODY -> select(mask, t -> (t.part() == fallbackArm && isLimbRoot(anatomy, t)) || anatomy.matches(Selector.JAW, t));
            case HEAD -> select(mask, t -> anatomy.matches(Selector.STERNUM, t));
            case RIGHT_ARM, LEFT_ARM -> select(mask, t -> (t.part() == seededPart && anatomy.hasLandmark(t, Landmark.JOINT))
                    || isSideStrip(anatomy, t, seededPart == Part.RIGHT_ARM));
            case RIGHT_LEG, LEFT_LEG -> select(mask, t -> (t.part() == seededPart && anatomy.hasLandmark(t, Landmark.JOINT)) || pelvis(t));
        };
    }

    private static boolean isArm(Part part) {
        return part == Part.RIGHT_ARM || part == Part.LEFT_ARM;
    }

    private static boolean isLimb(Part part) {
        return part == Part.RIGHT_ARM || part == Part.LEFT_ARM || part == Part.RIGHT_LEG || part == Part.LEFT_LEG;
    }

    private static int[] select(CorpseFleshMask mask, Predicate<SurfaceTexel> predicate) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < mask.surfaceSize(); i++) {
            if (predicate.test(mask.texel(i))) hits.add(i);
        }
        int[] result = new int[hits.size()];
        for (int i = 0; i < result.length; i++) result[i] = hits.get(i);
        return result;
    }

    private static int[] concat(int[] a, int[] b) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (int v : a) set.add(v);
        for (int v : b) set.add(v);
        int[] result = new int[set.size()];
        int i = 0;
        for (int v : set) result[i++] = v;
        return result;
    }
}
