package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Category;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.RevealArchetype;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SplitMix64;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SurfaceTexel;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import javax.annotation.Nullable;

import net.minecraft.util.FastColor;

/**
 * The appearance-<em>seeded</em> half of what {@code CorpseFleshMask} used to own outright: the
 * hole rank, independent primary/secondary blotch streams, the full-part reveal archetype and its
 * activation score, and (reserved for the not-yet-built patch-library pass) six per-slot patch seed
 * tuples. SOL-PATCH-STITCH-DESIGN.md A.3 replaces the old owner-UUID rot signature with {@code
 * CorpseEntity#getAppearanceSeed()} as the sole root of procedural appearance — this class is the
 * new home for everything that used to be derived from that owner UUID.
 * <p>
 * Keyed by {@code (appearanceSeed, slim)}, not corpse UUID: a corpse's {@code appearanceSeed} is
 * either explicitly set (a {@code /summon} test) or derived once from its own UUID as a stable
 * fallback (see {@code CorpseEntity#getAppearanceSeed()}), so two corpses that happen to share one
 * {@code appearanceSeed} deliberately share one layout — the documented, desired behaviour (this is
 * also exactly why owner UUID must never leak back into this class: it would silently reintroduce
 * the original "every death of one account looks the same" defect A.3 was written to remove).
 * <p>
 * A synchronized access-order LRU capped at 128 entries (SOL-PATCH-STITCH-DESIGN.md A.4), mirroring
 * the pattern {@code CorpseFleshBaker}'s own {@code baked}/{@code sourceImages} maps already use.
 * Reachable from {@code CorpseFleshBaker.bake()}, which runs on a background worker thread — every
 * public entry point is {@code synchronized} for that reason.
 */
final class CorpseAppearanceLayout {

    // ── SOL-PATCH-STITCH-DESIGN.md A.2's stable public contract — once shipped, do not renumber or
    // reuse any of these salts. Five of them (SELC/SLOT/SHPE/SEAM/TONE) are not yet consumed by any
    // drawing logic in this pass; they exist so the later patch-library pass has the exact verbatim
    // per-slot sub-streams ready rather than inventing its own. ─────────────────────────────────────
    private static final long PST_HOLE = 0x5053545F484F4C45L;
    private static final long PST_PRIM = 0x5053545F5052494DL;
    private static final long PST_SECN = 0x5053545F5345434EL;
    private static final long PST_ARCH = 0x5053545F41524348L;
    private static final long PST_ACTV = 0x5053545F41435456L;
    private static final long PST_SELC = 0x5053545F53454C43L;
    private static final long PST_SLOT = 0x5053545F534C4F54L;
    private static final long PST_SHPE = 0x5053545F53485045L;
    private static final long PST_SEAM = 0x5053545F5345414DL;
    private static final long PST_TONE = 0x5053545F544F4E45L;
    /** New axis, added for the death-cause injury pass (SOL-DEATHCAUSE-INJURY-DESIGN.md C2): "which
     *  eligible weighted candidate wins" needs its own independent draw, separate from {@link
     *  #activationScore}, which only ever decided WHETHER a reveal fires — not where. Still a PST_*
     *  axis (not an {@code INJ_*} one) because it lives entirely inside this class's own legacy
     *  reveal-selection mechanism; the cause-specific WEIGHTS it draws among come from the caller. */
    private static final long PST_RSEL = 0x5053545F5253454CL;

    private static final int MAX_LAYOUTS = 128;
    private static final int PATCH_SLOT_COUNT = 6;

    private static final Map<LayoutKey, CorpseAppearanceLayout> CACHE =
            new LinkedHashMap<>(16, 0.75F, true);

    private final CorpseFleshMask mask;
    private final int[] holeRank;
    private final long primaryBlotchSeed;
    private final long secondaryBlotchSeed;
    private final RevealArchetype revealArchetype;
    private final float activationScore;
    private final long revealSelectionSeed;
    private final PatchSlotSeeds[] patchSlots;

    private CorpseAppearanceLayout(long appearanceSeed, CorpseFleshMask mask) {
        this.mask = mask;
        this.holeRank = buildHoleRank(axisSeed(appearanceSeed, PST_HOLE));
        this.primaryBlotchSeed = axisSeed(appearanceSeed, PST_PRIM);
        this.secondaryBlotchSeed = axisSeed(appearanceSeed, PST_SECN);
        this.revealArchetype = drawRevealArchetype(axisSeed(appearanceSeed, PST_ARCH));
        this.activationScore = drawActivationScore(axisSeed(appearanceSeed, PST_ACTV));
        this.revealSelectionSeed = axisSeed(appearanceSeed, PST_RSEL);
        this.patchSlots = buildPatchSlotSeeds(appearanceSeed);
        validate();
    }

    static synchronized CorpseAppearanceLayout get(long appearanceSeed, boolean slim, CorpseFleshMask mask) {
        LayoutKey key = new LayoutKey(appearanceSeed, slim);
        CorpseAppearanceLayout layout = CACHE.get(key);
        if (layout == null) {
            layout = new CorpseAppearanceLayout(appearanceSeed, mask);
            CACHE.put(key, layout);
            while (CACHE.size() > MAX_LAYOUTS) {
                CACHE.remove(CACHE.keySet().iterator().next());
            }
        }
        return layout;
    }

    static synchronized void clearCache() {
        CACHE.clear();
    }

    int holeIndex(int rank) {
        return holeRank[rank];
    }

    int maximumHoleCount() {
        return holeRank.length;
    }

    BlotchTexel[] primaryBlotchRank(float minimumDiameter, float maximumDiameter) {
        return buildBlotchRank(primaryBlotchSeed, minimumDiameter, maximumDiameter);
    }

    BlotchTexel[] secondaryBlotchRank(float minimumDiameter, float maximumDiameter) {
        return buildBlotchRank(secondaryBlotchSeed, minimumDiameter, maximumDiameter);
    }

    /** Playtest fix (2026-08-23, "large, single shaded green patches"): a blotch-rank entry paired
     *  with a normalized 0..1 depth (1 = nearest a blob's own centre, 0 = blob edge or uniform
     *  fallback texel) — see {@link #buildBlotchRank}. Lets {@code CorpseFleshBaker} shade each
     *  texel individually instead of stamping one flat colour across an entire blotch footprint. */
    record BlotchTexel(int index, float depth) {}

    /** Reserved for the not-yet-built patch-library pass (SOL-PATCH-STITCH-DESIGN.md B/C) — the
     *  five independent sub-streams for composition slot {@code slot} ({@code 0..5}), already isolated
     *  from every other axis per A.2. Nothing in this pass draws from these; they exist so the later
     *  pass never has an excuse to invent a different derivation. */
    PatchSlotSeeds patchSlot(int slot) {
        return patchSlots[slot];
    }

    /**
     * @param externalRevealBoost floor applied on top of the age-driven {@code cumulativeChance}
     *                            (lava's {@code charRevealBoost}, {@code lava_progressive_incineration.md}
     *                            §2 — 0.0 for every corpse without a matching environmental case).
     *                            A nonzero boost also widens {@link RevealArchetype#isEligible}
     *                            eligibility to every stage/archetype: age-only eligibility hard-
     *                            excludes FRESH/EARLY_DECAY, which would otherwise silently block
     *                            lava's own headline "near-bare skeleton within seconds" claim.
     */
    void applyFullPartReveal(int[] output, DecayStage stage, float stageProgress,
                             float externalRevealBoost) {
        applyFullPartReveal(output, stage, stageProgress, externalRevealBoost, null);
    }

    /**
     * SOL-DEATHCAUSE-INJURY-DESIGN.md F1 step 9 / C2: as before, EXCEPT that once a reveal is already
     * active (the activation gate below is completely unchanged — see {@link #isFullPartRevealActive}
     * — cause injury never touches it), an optional {@code preference} may steer WHICH part gets
     * cleared. {@code cumulativeChance}/{@code activationScore}/external-boost eligibility/clearing
     * behaviour are all byte-for-byte what they were before this parameter existed.
     */
    void applyFullPartReveal(int[] output, DecayStage stage, float stageProgress,
                             float externalRevealBoost, @Nullable RevealPreference preference) {
        RevealArchetype resolved = resolveActiveArchetype(stage, stageProgress, externalRevealBoost, preference);
        if (resolved == null) return;

        for (int i = 0; i < mask.surfaceSize(); i++) {
            SurfaceTexel texel = mask.texel(i);
            if (!resolved.contains(texel.part())) continue;
            output[texel.baseIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
            output[texel.outerIndex()] = FastColor.ABGR32.color(0, 0, 0, 0);
        }
    }

    /**
     * Exposes the exact same activation-and-selection decision {@link #applyFullPartReveal} makes,
     * without touching {@code output} — so {@code CorpseHeldItemLayer} can ask "is a RIGHT_ARM reveal
     * active right now" (RECONCILIATION.md R5's forced-SKELETAL-pose rule) using the identical
     * pure-function decision the baked flesh itself uses, rather than re-deriving (and risking
     * disagreeing with) it. Returns {@code null} whenever no reveal is currently active — same gate as
     * {@link #applyFullPartReveal}, so a caller need only null-check once.
     */
    @Nullable
    RevealArchetype resolveActiveArchetype(DecayStage stage, float stageProgress, float externalRevealBoost,
                                           @Nullable RevealPreference preference) {
        if (!isFullPartRevealActive(stage, stageProgress, externalRevealBoost)) return null;
        return resolveRevealArchetype(stage, stageProgress, externalRevealBoost, preference);
    }

    /**
     * C2's location-only selection: {@code preference}'s primary/secondary candidates are filtered by
     * their OWN {@link RevealArchetype#isEligible} (bypassed, like the base activation gate, whenever
     * {@code externalRevealBoost > 0}), the legacy {@link #revealArchetype} is always kept as a
     * fallback candidate (its own eligibility already gated the caller reaching this method at all —
     * C2's "a retained 5-10% legacy route preserves variety"), weights are renormalised by simply
     * zeroing an ineligible candidate's share before the single draw, and a {@code null} preference
     * (no cause data, or {@code DeathCause.NATURAL}) reproduces the pre-injury legacy-only behaviour
     * exactly.
     */
    private RevealArchetype resolveRevealArchetype(DecayStage stage, float stageProgress,
                                                    float externalRevealBoost, @Nullable RevealPreference preference) {
        if (preference == null) return revealArchetype;
        boolean boosted = externalRevealBoost > 0.0F;
        int primaryWeight = preference.primary() != null
                && (boosted || preference.primary().isEligible(stage, stageProgress))
                ? preference.primaryWeight() : 0;
        int secondaryWeight = preference.secondary() != null
                && (boosted || preference.secondary().isEligible(stage, stageProgress))
                ? preference.secondaryWeight() : 0;
        int legacyWeight = preference.legacyWeight();
        int total = primaryWeight + secondaryWeight + legacyWeight;
        if (total <= 0) return revealArchetype;
        int roll = (int) Long.remainderUnsigned(revealSelectionSeed, total);
        if (roll < primaryWeight) return preference.primary();
        if (roll < primaryWeight + secondaryWeight) return preference.secondary();
        return revealArchetype;
    }

    private boolean isFullPartRevealActive(DecayStage stage, float stageProgress,
                                           float externalRevealBoost) {
        float chance = Math.max(cumulativeChance(stage, stageProgress), externalRevealBoost);
        boolean eligible = revealArchetype.isEligible(stage, stageProgress) || externalRevealBoost > 0.0F;
        return eligible && activationScore < chance;
    }

    /** C2's weighted primary/secondary/legacy reveal-location preference — see {@link
     *  DeathCauseInjuryProfile#revealPreferenceFor} for construction. {@code primary}/{@code
     *  secondary} may independently be {@code null} (no candidate for that slot); weights are the
     *  literal A1 percentages ({@code primaryWeight + secondaryWeight + legacyWeight} need not sum to
     *  100 once ineligible candidates are filtered — {@link #resolveRevealArchetype} renormalises by
     *  construction, since it only ever compares against their own filtered sum). */
    record RevealPreference(@Nullable RevealArchetype primary, int primaryWeight,
                            @Nullable RevealArchetype secondary, int secondaryWeight, int legacyWeight) {}

    private static RevealArchetype drawRevealArchetype(long axisSeed) {
        int draw = (int) Long.remainderUnsigned(axisSeed, 100);
        if (draw < 16) return RevealArchetype.RIGHT_ARM;
        if (draw < 32) return RevealArchetype.LEFT_ARM;
        if (draw < 50) return RevealArchetype.RIGHT_LEG;
        if (draw < 68) return RevealArchetype.LEFT_LEG;
        if (draw < 82) return RevealArchetype.TORSO;
        if (draw < 86) return RevealArchetype.BOTH_ARMS;
        if (draw < 92) return RevealArchetype.BOTH_LEGS;
        return RevealArchetype.HEAD;
    }

    private static float drawActivationScore(long axisSeed) {
        return (axisSeed >>> 40) * 0x1.0p-24F;
    }

    /**
     * ADVANCED_DECAY is the OLD SKELETAL stage (same fraction/position, just renamed — see
     * {@code DecayStage}'s header comment), so it inherits that case's ramp byte-for-byte: a corpse
     * a player already saw ramping 0.40->0.85 through this stage must keep doing exactly that. The
     * NEW SKELETAL (true bare exoskeleton, no flesh at all — {@code CorpseFleshBaker#resolve} never
     * even bakes flesh at this stage, so this value is defensive-only for any other caller that still
     * constructs a layout at this stage) gets the same terminal 0.85F cap ADVANCED_DECAY's ramp tops
     * out at: it is a strict superset of decay, so there is no lower value that would make sense.
     */
    private static float cumulativeChance(DecayStage stage, float stageProgress) {
        float progress = Math.max(0.0F, Math.min(1.0F, stageProgress));
        return switch (stage) {
            case FRESH, EARLY_DECAY -> 0.0F;
            case MID_DECAY -> progress < 0.67F
                    ? 0.0F : lerp(0.0F, 0.12F, (progress - 0.67F) / 0.33F);
            case LATE_DECAY -> lerp(0.12F, 0.40F, progress);
            case ADVANCED_DECAY -> lerp(0.40F, 0.85F, progress);
            case SKELETAL, COLLAPSE -> 0.85F;
        };
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    /** {@code SplitMix64.mix(appearanceSeed ^ salt)} — SOL-PATCH-STITCH-DESIGN.md A.2's construction
     *  for a non-slotted axis. Every top-level axis (hole rank, primary/secondary blotch, reveal
     *  archetype/activation) draws its own independent {@code appearanceSeed ^ AXIS_SALT} value so
     *  none of them can move another's outcome. */
    private static long axisSeed(long appearanceSeed, long salt) {
        return SplitMix64.mix(appearanceSeed ^ salt);
    }

    /** A.2's attempt-derivation rule, exposed for the later patch-library pass so a per-attempt draw
     *  (e.g. C.3's up-to-16 candidate anchors) never consumes a shared sequential stream — each
     *  attempt gets its own independent value derived directly from the axis seed and its own index. */
    static long attemptSeed(long axisSeed, int attempt) {
        return SplitMix64.mix(axisSeed ^ ((attempt + 1L) * 0xD1B54A32D192ED03L));
    }

    private static PatchSlotSeeds[] buildPatchSlotSeeds(long appearanceSeed) {
        PatchSlotSeeds[] slots = new PatchSlotSeeds[PATCH_SLOT_COUNT];
        for (int i = 0; i < PATCH_SLOT_COUNT; i++) {
            long slotScramble = SplitMix64.mix((i + 1L) * 0x9E3779B97F4A7C15L);
            long selectionSeed = SplitMix64.mix(appearanceSeed ^ PST_SELC ^ slotScramble);
            long placementSeed = SplitMix64.mix(appearanceSeed ^ PST_SLOT ^ slotScramble);
            long shapeSeed = SplitMix64.mix(appearanceSeed ^ PST_SHPE ^ slotScramble);
            long seamSeed = SplitMix64.mix(appearanceSeed ^ PST_SEAM ^ slotScramble);
            long toneSeed = SplitMix64.mix(appearanceSeed ^ PST_TONE ^ slotScramble);
            slots[i] = new PatchSlotSeeds(selectionSeed, placementSeed, shapeSeed, seamSeed, toneSeed);
        }
        return slots;
    }

    // ── Hole rank — SOL-PATCH-STITCH-DESIGN.md A.3: "the current hole construction is retained
    // EXACTLY — same anatomical bands, centre counts, diameter ranges, ranking, and final count
    // round(surfaceSize * 0.68). Only its seed changes." Every constant/step below is byte-identical
    // to the pre-refactor CorpseFleshMask#buildHoleRank; only the seed source moved. ─────────────────
    private int[] buildHoleRank(long axisSeed) {
        boolean[] used = new boolean[mask.surfaceSize()];
        List<Integer> order = new ArrayList<>();
        appendBand(order, used, axisSeed ^ 0x4D49445F484F4C45L, 18, 2, 4,
                Math.round(mask.surfaceSize() * 0.08F), Band.MID);
        appendBand(order, used, axisSeed ^ 0x4C415445484F4C45L, 16, 4, 7,
                Math.round(mask.surfaceSize() * 0.24F), Band.LATE);
        appendBand(order, used, axisSeed ^ 0x534B454C484F4C45L, 24, 6, 10,
                Math.round(mask.surfaceSize() * 0.68F), Band.SKELETAL);
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    private void appendBand(List<Integer> order, boolean[] used, long seed, int centreCount,
                            int minDiameter, int maxDiameter, int targetCount, Band band) {
        SplitMix64 random = new SplitMix64(seed);
        List<BlobCentre> centres = new ArrayList<>();
        addCentres(centres, random, centreCount, minDiameter, maxDiameter, band, false);

        List<ScoredTexel> candidates = scoreCandidates(centres, used, seed);
        int attempts = 0;
        while (candidates.size() < targetCount - order.size() && attempts++ < 32) {
            addCentres(centres, random, 8, minDiameter, maxDiameter, band, true);
            candidates = scoreCandidates(centres, used, seed);
        }

        candidates.sort(Comparator.comparingDouble(ScoredTexel::score)
                .thenComparingLong(ScoredTexel::tieBreaker));
        int needed = targetCount - order.size();
        for (int i = 0; i < needed && i < candidates.size(); i++) {
            int index = candidates.get(i).index();
            used[index] = true;
            order.add(index);
        }

        // The uniform-centre expansion should always cover the target. This deterministic final
        // fill keeps exact coverage even if a future model layout removes an anatomical zone.
        if (order.size() < targetCount) {
            List<ScoredTexel> remainder = new ArrayList<>();
            for (int i = 0; i < mask.surfaceSize(); i++) {
                if (!used[i]) {
                    remainder.add(new ScoredTexel(i, 2.0, CorpseFleshMask.texelHash(seed, mask.texel(i))));
                }
            }
            remainder.sort(Comparator.comparingLong(ScoredTexel::tieBreaker));
            for (ScoredTexel scored : remainder) {
                if (order.size() >= targetCount) break;
                used[scored.index()] = true;
                order.add(scored.index());
            }
        }
    }

    private void addCentres(List<BlobCentre> centres, SplitMix64 random, int count,
                            int minDiameter, int maxDiameter, Band band, boolean uniformOnly) {
        for (int i = 0; i < count; i++) {
            Category category = uniformOnly ? Category.UNIFORM : chooseCategory(random, band);
            List<Integer> zone = indicesFor(category, band != Band.SKELETAL);
            if (zone.isEmpty()) zone = indicesFor(Category.UNIFORM, band != Band.SKELETAL);
            SurfaceTexel texel = mask.texel(zone.get(random.nextInt(zone.size())));
            float diameterX = random.nextInt(minDiameter, maxDiameter + 1);
            float diameterY = random.nextInt(minDiameter, maxDiameter + 1);
            centres.add(new BlobCentre(texel.part(), texel.face(), texel.x(), texel.y(),
                    Math.max(1.0F, diameterX / 2.0F), Math.max(1.0F, diameterY / 2.0F)));
        }
    }

    private static Category chooseCategory(SplitMix64 random, Band band) {
        int value = random.nextInt(100);
        if (band == Band.MID) {
            if (value < 30) return Category.LIMB_END;
            if (value < 55) return Category.JOINT;
            if (value < 85) return Category.STERNUM;
            return Category.JAW;
        }
        if (band == Band.LATE) {
            if (value < 25) return Category.LIMB_END;
            if (value < 50) return Category.JOINT;
            if (value < 75) return Category.STERNUM;
            if (value < 90) return Category.JAW;
            return Category.UNIFORM;
        }
        if (value < 20) return Category.LIMB_END;
        if (value < 40) return Category.JOINT;
        if (value < 60) return Category.STERNUM;
        if (value < 70) return Category.JAW;
        return Category.UNIFORM;
    }

    private List<Integer> indicesFor(Category category, boolean excludeEyeBand) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mask.surfaceSize(); i++) {
            SurfaceTexel texel = mask.texel(i);
            if (excludeEyeBand && texel.part() == CorpseFleshMask.Part.HEAD
                    && texel.face() == CorpseFleshMask.Face.NORTH
                    && texel.y() >= 2 && texel.y() <= 4) continue;
            if (CorpseFleshMask.matches(category, texel)) indices.add(i);
        }
        return indices;
    }

    private List<ScoredTexel> scoreCandidates(List<BlobCentre> centres, boolean[] used, long seed) {
        List<ScoredTexel> scores = new ArrayList<>();
        for (int i = 0; i < mask.surfaceSize(); i++) {
            if (used[i]) continue;
            SurfaceTexel texel = mask.texel(i);
            double best = Double.POSITIVE_INFINITY;
            for (BlobCentre centre : centres) {
                if (centre.part() != texel.part() || centre.face() != texel.face()) continue;
                double dx = (texel.x() - centre.x()) / centre.radiusX();
                double dy = (texel.y() - centre.y()) / centre.radiusY();
                best = Math.min(best, dx * dx + dy * dy);
            }
            long hash = CorpseFleshMask.texelHash(seed, texel);
            double jitter = ((hash >>> 11) * 0x1.0p-53) * 0.20;
            if (best + jitter <= 1.20) {
                scores.add(new ScoredTexel(i, best + jitter, hash));
            }
        }
        return scores;
    }

    // ── Blotch rank — same algorithm as the hole bands' uniform-only expansion, now driven by the
    // primary/secondary axis seeds instead of one shared owner-derived blotchSeed (A.3's last
    // sentence: "Primary and secondary blotches receive separate streams, so changing secondary-
    // blotch implementation cannot move the primary pattern."). ────────────────────────────────────
    private BlotchTexel[] buildBlotchRank(long seed, float minimumDiameterF, float maximumDiameterF) {
        int minimumDiameter = Math.max(1, Math.round(minimumDiameterF));
        int maximumDiameter = Math.max(minimumDiameter, Math.round(maximumDiameterF));
        SplitMix64 random = new SplitMix64(seed);
        List<BlobCentre> centres = new ArrayList<>();
        addCentres(centres, random, 72, minimumDiameter, maximumDiameter, Band.SKELETAL, true);
        List<ScoredTexel> scores = new ArrayList<>();
        boolean[] unused = new boolean[mask.surfaceSize()];
        scores.addAll(scoreCandidates(centres, unused, seed));
        boolean[] included = new boolean[mask.surfaceSize()];
        for (ScoredTexel score : scores) included[score.index()] = true;
        for (int i = 0; i < mask.surfaceSize(); i++) {
            if (!included[i]) scores.add(new ScoredTexel(i, 2.0, CorpseFleshMask.texelHash(seed, mask.texel(i))));
        }
        scores.sort(Comparator.comparingDouble(ScoredTexel::score)
                .thenComparingLong(ScoredTexel::tieBreaker));
        // `score` is the same normalized-squared-distance-to-nearest-blob-centre value
        // scoreCandidates() ranks by (0 at a blob's own centre, up to the 1.20 inclusion boundary at
        // its edge; the 2.0 sentinel marks uniform-fallback texels never claimed by a real blob) —
        // reuse it as a depth so the baker can shade core vs. edge instead of one flat colour.
        BlotchTexel[] result = new BlotchTexel[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            ScoredTexel s = scores.get(i);
            float depth = 1.0F - (float) Math.min(1.0, s.score() / 1.20);
            result[i] = new BlotchTexel(s.index(), depth);
        }
        return result;
    }

    /** F.1 step 5: hard-fail, never silently degrade. Mirrors {@code CorpseFleshMask#validate}'s own
     *  policy, split to check exactly the seeded invariants that moved into this class. */
    private void validate() {
        int expected = Math.round(mask.surfaceSize() * 0.68F);
        if (holeRank.length != expected) {
            throw new IllegalStateException("Invalid corpse appearance hole rank length");
        }
        boolean[] ranked = new boolean[mask.surfaceSize()];
        for (int index : holeRank) {
            if (index < 0 || index >= mask.surfaceSize() || ranked[index]) {
                throw new IllegalStateException("Unstable corpse appearance hole rank");
            }
            ranked[index] = true;
        }
    }

    private record LayoutKey(long appearanceSeed, boolean slim) {}
    private record BlobCentre(CorpseFleshMask.Part part, CorpseFleshMask.Face face, int x, int y,
                              float radiusX, float radiusY) {}
    private record ScoredTexel(int index, double score, long tieBreaker) {}
    private enum Band { MID, LATE, SKELETAL }

    /** Reserved scaffolding for the not-yet-built patch-library pass — see {@link #patchSlot(int)}. */
    record PatchSlotSeeds(long selectionSeed, long placementSeed, long shapeSeed, long seamSeed,
                          long toneSeed) {}
}
