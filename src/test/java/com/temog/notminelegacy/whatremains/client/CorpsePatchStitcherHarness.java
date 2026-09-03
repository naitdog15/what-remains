package com.temog.notminelegacy.whatremains.client;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import com.temog.notminelegacy.whatremains.entity.DecayStage;

import net.minecraft.util.FastColor;

/**
 * STEP 2 of the corpse "unnatural green patches" investigation: a headless measurement harness
 * against the CURRENT, UNMODIFIED {@link CorpsePatchStitcher} motif-stamping pass, run side by side
 * with the already-measured {@link CorpseAppearanceLayout}/blotch pass so the two are directly
 * comparable on identical statistics.
 * <p>
 * This class makes NO algorithmic change to production code. The only production change anywhere
 * in this investigation is two visibility-only widenings in {@code CorpseRenderState.java}
 * (private constructor and private nested {@code Endpoint} record both -> package-private, see the
 * comments left at both sites) — needed only because {@code CorpseRenderState.fromAge(int)} reads
 * {@code WhatRemainsConfig.decayLifespanTicks}, a Forge-config value that is only ever populated by
 * a live {@code ModConfigEvent}, which never fires headlessly. Every other class this harness drives
 * ({@code CorpseFleshMask}, {@code CorpseAnatomy}, {@code CorpseAppearanceLayout},
 * {@code CorpsePatchStitcher}, {@code EnvironmentalRenderInputs.NONE}, {@code DecayStage} used only
 * as a plain enum constant) needed zero changes — see the class-level "headless-drivability" verdict
 * in the run's own console summary and the accompanying report.
 * <p>
 * <b>How "touched" is measured for the stitcher</b> — via {@link CorpsePatchStitcher#debugSlotTexelIndices},
 * an existing package-private test-only hook already built into production code specifically so a
 * harness would not need reflection. Its javadoc states it is "not called by any production code
 * path." Reconstructing {@code fillPatch}'s ownership rule (lower slot number wins any overlapping
 * texel, revealed texels only — the first {@code revealCount} entries of each instance's stable rank
 * order) from that hook's output is proven equivalent to the real bake because {@code applySeams}
 * only ever re-blends texels that are already members of some instance's own revealed set (see
 * {@code findBoundary}: both endpoints of every seam pair come from {@code revealedA}/{@code
 * revealedB}), so seams can never add a new texel to the footprint, only re-colour one already
 * counted. This is cross-checked at runtime: {@link #verifyFootprintAgainstRealBake} calls the real
 * {@link CorpsePatchStitcher#applyPatchStitch} and confirms every pixel it actually changed lies
 * inside our reconstructed footprint (and vice versa), logging any mismatch instead of silently
 * trusting the reconstruction.
 * <p>
 * <b>How blend weight is measured</b> — {@code weightFor(Kind, float)}'s per-texel {@code z} value is
 * generated deep inside each of the eight private shape generators and is not exposed by any debug
 * hook, so it cannot be read directly without reflection or new production code (both out of scope).
 * Instead this harness runs {@code applyPatchStitch} TWICE per (seed, model, stage) against two
 * otherwise-identical, fully-opaque uniform base textures (solid black, solid white) — shape/placement
 * selection never reads the output buffer, so both runs place identical patches. Because every colour
 * resolution in {@code resolveColor}/{@code retint}/{@code blendInto} is affine in the incoming pixel
 * colour, the two results at a given texel obey {@code blended = current*(1-k) + k*target} for some
 * per-texel constant {@code k} — recovered exactly as {@code k = 1 - (redWhiteRun - redBlackRun)/255}.
 * This {@code k} equals the raw {@code weightFor()} value for archetennoy/kind combinations whose
 * colour target does not depend on the pre-existing pixel, and equals {@code weight * blendFraction}
 * for the "soft" kinds (RIM/HALO/EDGE/etc.) whose {@code resolveColor} branch itself blends toward the
 * existing colour before the outer {@code blendInto} blends again — i.e. it is the TRUE net pull
 * toward the patch colour, which is the more meaningful "how visible does this texel become" quantity
 * for testing the user's "unnatural patches" complaint than the pre-multiplier weight alone. This
 * equivalence holds for "hard-target" kinds exactly (k == weightFor()) and for "soft" kinds up to the
 * archetype/kind's own fixed blend fraction (k == weightFor() * blendFraction).
 * <p>
 * Run with an explicit {@code --out <dir>} argument; refuses to run if that path resolves inside
 * this repository (identical guard to {@link CorpseBlotchLayoutHarness}).
 */
public final class CorpsePatchStitcherHarness {

    private static final int UV_SIZE = 4096; // 64x64, matches CorpseFleshMask's baseIndex/outerIndex range.
    private static final int ARCHETYPE_COUNT = 8;
    private static final String[] ARCHETYPE_NAMES = {
            "LIVID_POOL", "VESSEL_FAN", "ROT_ISLAND", "CREASE_BAND", "DISTAL_SLOUGH",
            "STERNAL_LADDER", "PRESSURE_PANEL", "TENDON_LACE"
    };
    // ── Corpus: same 517 seeds as CorpseBlotchLayoutHarness (SOL-DESIGN.md section 3), duplicated
    // here rather than shared so this new harness never touches the already-verified baseline file. ─
    private static long[] buildSeeds() {
        long[] seeds = new long[517];
        for (int i = 0; i < 512; i++) seeds[i] = i;
        seeds[512] = Long.MIN_VALUE;
        seeds[513] = -1L;
        seeds[514] = Long.MAX_VALUE;
        seeds[515] = 0x5555555555555555L;
        seeds[516] = 0x7FFFFFFFFFFFFFFEL;
        return seeds;
    }

    private static final long[] SEEDS = buildSeeds();

    /**
     * EARLY/MID/LATE/ADVANCED, literal values copied by hand from {@code CorpseRenderState.ENDPOINTS}
     * indices 1-4 (identical numbers {@link CorpseBlotchLayoutHarness}'s {@code CaseSpec} table
     * already uses for EARLY/MID/LATE/ADVANCED) plus the non-blotch Endpoint fields (fullness/hue/
     * saturation/value/holes) the stitcher itself never reads but the widened {@code Endpoint} record
     * still requires a value for. {@code stageProgress} is fixed at 1.0F for every case: that is what
     * makes {@code CorpsePatchStitcher#interpolate} return the ENDPOINT of {@code STAGE_BUDGET_FRACTION}
     * exactly (verified below to reproduce the task's own stated 13.0%/18.0% MID/LATE figures to the
     * last digit), matching the "MID"/"LATE" naming convention already established by the blotch
     * harness's own CaseSpec (which also names its cases after the raw ENDPOINTS row, not a partial
     * blend into it).
     */
    private record StageSpec(String name, DecayStage stage, int primaryRgb, float primaryCoverage,
                             float primaryMinD, float primaryMaxD, int secondaryRgb,
                             float secondaryCoverage, float secondaryMinD, float secondaryMaxD,
                             float fullness, float hue, float saturation, float value, float holes) {
        boolean hasSecondary() {
            return secondaryRgb != 0;
        }
    }

    private static final StageSpec[] STAGES = {
            new StageSpec("EARLY", DecayStage.EARLY_DECAY,
                    0x483E2B, 0.04F, 2F, 5F, 0, 0F, 0F, 0F,
                    0.90F, 6.0F, 0.98F, 0.93F, 0.0F),
            new StageSpec("MID", DecayStage.MID_DECAY,
                    0x4C632F, 0.24F, 4F, 8F, 0x653827, 0.08F, 2F, 5F,
                    0.60F, 36.0F, 1.10F, 0.54F, 0.12F),
            new StageSpec("LATE", DecayStage.LATE_DECAY,
                    0x385226, 0.42F, 6F, 12F, 0x532D22, 0.04F, 2F, 5F,
                    0.22F, 58.0F, 1.25F, 0.36F, 0.34F),
            new StageSpec("ADVANCED", DecayStage.ADVANCED_DECAY,
                    0x3B2F1B, 0.20F, 3F, 7F, 0, 0F, 0F, 0F,
                    0.00F, 18.0F, 0.18F, 0.34F, 0.68F),
    };

    // Representative seeds for the PNG panels — evenly spaced indices across the 0..511 plain-integer
    // portion of the corpus (NOT the exotic tail), classic model only. "Representative" here means
    // "spread across the seed space", not cherry-picked worst-case (that selection already happened
    // for the blotch pass's own worst-of-8 PNGs; this task's PNGs are a general visual sanity check).
    private static final long[] PNG_SEEDS = {0, 64, 128, 192, 256, 320, 384, 450};

    public static void main(String[] args) throws Exception {
        Path outDir = resolveOutDir(args);
        Files.createDirectories(outDir);
        Path pngDir = outDir.resolve("png");
        Files.createDirectories(pngDir);

        long startNanos = System.nanoTime();
        int mismatchCount = 0;

        // model|stage -> pooled samples
        Map<String, List<Integer>> stitcherComponentPool = new LinkedHashMap<>();
        Map<String, List<Integer>> blotchComponentPool = new LinkedHashMap<>();
        Map<String, List<Float>> blendStrengthPool = new LinkedHashMap<>();
        Map<String, long[]> archetypeChosenTotals = new LinkedHashMap<>(); // len 8
        Map<String, long[]> archetypeTexelTotals = new LinkedHashMap<>(); // len 8
        Map<String, double[]> fractionTotals = new LinkedHashMap<>(); // [sumStitcherFrac, sumBlotchFrac, n]

        long rowCount = 0;

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(
                outDir.resolve("per_seed_comparison.csv"), StandardCharsets.UTF_8))) {
            csv.println("model,stage,seed,surfaceSize,"
                    + "stitcherTouched,stitcherFraction,stitcherDistinctArchetypes,"
                    + "stitcherComponentCount,stitcherLargestComponent,stitcherMeanComponent,"
                    + "stitcherBlendMean,stitcherBlendMedian,stitcherBlendP95,stitcherBlendMax,"
                    + "blotchTouched,blotchFraction,blotchComponentCount,blotchLargestComponent,"
                    + "blotchMeanComponent,footprintMismatch,"
                    + archetypeCsvHeader("texels") + "," + archetypeCsvHeader("chosen"));

            for (boolean slim : new boolean[]{false, true}) {
                String model = slim ? "slim" : "classic";
                CorpseFleshMask mask = CorpseFleshMask.get(slim);
                CorpseAnatomy anatomy = CorpseAnatomy.get(slim);
                Map<Long, Integer> coordIndex = buildCoordIndex(mask);

                for (StageSpec stage : STAGES) {
                    String key = model + "|" + stage.name();
                    stitcherComponentPool.put(key, new ArrayList<>());
                    blotchComponentPool.put(key, new ArrayList<>());
                    blendStrengthPool.put(key, new ArrayList<>());
                    archetypeChosenTotals.put(key, new long[ARCHETYPE_COUNT]);
                    archetypeTexelTotals.put(key, new long[ARCHETYPE_COUNT]);
                    fractionTotals.put(key, new double[3]);

                    CorpseRenderState state = buildState(stage);

                    for (long seed : SEEDS) {
                        CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(seed, slim, mask);

                        boolean[] blotchTouched = blotchTouched(mask, layout, stage);
                        StitcherResult sr = stitcherFootprint(mask, anatomy, layout, state);

                        boolean mismatch = !verifyFootprintAgainstRealBake(mask, anatomy, layout, state, sr.touched);
                        if (mismatch) mismatchCount++;

                        List<Integer> stitcherComponents = touchedComponents(mask, coordIndex, sr.touched);
                        List<Integer> blotchComponents = touchedComponents(mask, coordIndex, blotchTouched);
                        stitcherComponentPool.get(key).addAll(stitcherComponents);
                        blotchComponentPool.get(key).addAll(blotchComponents);

                        float[] strengths = blendStrengths(mask, anatomy, layout, state, sr.touched);
                        List<Float> pool = blendStrengthPool.get(key);
                        for (float s : strengths) pool.add(s);

                        long[] archChosen = archetypeChosenTotals.get(key);
                        long[] archTexels = archetypeTexelTotals.get(key);
                        for (int a = 0; a < ARCHETYPE_COUNT; a++) {
                            archChosen[a] += sr.archetypeChosenCounts[a];
                            archTexels[a] += sr.archetypeTexelCounts[a];
                        }

                        int stitcherTouchedCount = countTrue(sr.touched);
                        int blotchTouchedCount = countTrue(blotchTouched);
                        double[] fracTot = fractionTotals.get(key);
                        fracTot[0] += stitcherTouchedCount / (double) mask.surfaceSize();
                        fracTot[1] += blotchTouchedCount / (double) mask.surfaceSize();
                        fracTot[2] += 1;

                        int stitcherLargest = maxOrZero(stitcherComponents);
                        int blotchLargest = maxOrZero(blotchComponents);
                        double stitcherMeanComp = meanOrZero(stitcherComponents);
                        double blotchMeanComp = meanOrZero(blotchComponents);

                        float[] sortedStrengths = strengths.clone();
                        Arrays.sort(sortedStrengths);
                        double blendMean = mean(sortedStrengths);
                        double blendMedian = percentileF(sortedStrengths, 0.50);
                        double blendP95 = percentileF(sortedStrengths, 0.95);
                        double blendMax = sortedStrengths.length == 0 ? Double.NaN
                                : sortedStrengths[sortedStrengths.length - 1];

                        csv.println(model + "," + stage.name() + "," + seed + "," + mask.surfaceSize() + ","
                                + stitcherTouchedCount + "," + fmt(stitcherTouchedCount / (double) mask.surfaceSize())
                                + "," + sr.distinctArchetypes + "," + stitcherComponents.size() + ","
                                + stitcherLargest + "," + fmt(stitcherMeanComp) + ","
                                + fmt(blendMean) + "," + fmt(blendMedian) + "," + fmt(blendP95) + "," + fmt(blendMax)
                                + "," + blotchTouchedCount + "," + fmt(blotchTouchedCount / (double) mask.surfaceSize())
                                + "," + blotchComponents.size() + "," + blotchLargest + "," + fmt(blotchMeanComp)
                                + "," + (mismatch ? 1 : 0) + "," + intArrayCsv(sr.archetypeTexelCounts)
                                + "," + intArrayCsv(sr.archetypeChosenCounts));
                        rowCount++;
                    }
                }
            }
        }

        writeAggregate(outDir.resolve("aggregate.json"), stitcherComponentPool, blotchComponentPool,
                blendStrengthPool, archetypeChosenTotals, archetypeTexelTotals, fractionTotals);
        writePngs(pngDir);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        System.out.println("CorpsePatchStitcherHarness complete: " + rowCount + " CSV rows, "
                + elapsedMs + " ms, footprint reconstruction mismatches: " + mismatchCount
                + " (should be 0).");
        System.out.println("Output: " + outDir);
    }

    // ── --out argument handling / repo-boundary refusal (identical guard to CorpseBlotchLayoutHarness) ─
    private static Path resolveOutDir(String[] args) {
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--out requires a directory argument");
                }
                outArg = args[i + 1];
                break;
            }
        }
        if (outArg == null) {
            throw new IllegalArgumentException(
                    "Missing required --out <dir> argument. Refusing to run without an explicit, "
                            + "out-of-repo output directory.");
        }
        Path outDir = Path.of(outArg).toAbsolutePath().normalize();
        Path repoRoot = Path.of("").toAbsolutePath().normalize();
        String outStr = outDir.toString().toLowerCase(Locale.ROOT);
        String repoStr = repoRoot.toString().toLowerCase(Locale.ROOT);
        if (outStr.equals(repoStr) || outStr.startsWith(repoStr + java.io.File.separator)) {
            throw new IllegalStateException("Refusing to write harness output inside the repository: "
                    + outDir + " (repo root resolved from user.dir as " + repoRoot + "). Pass --out "
                    + "pointing somewhere outside the repository.");
        }
        return outDir;
    }

    // ── CorpseRenderState construction via the widened package-private ctor/Endpoint record ────────
    private static CorpseRenderState buildState(StageSpec s) {
        CorpseRenderState.Endpoint endpoint = new CorpseRenderState.Endpoint(
                s.fullness(), s.hue(), s.saturation(), s.value(),
                s.primaryRgb(), s.primaryCoverage(), s.primaryMinD(), s.primaryMaxD(),
                s.secondaryRgb(), s.secondaryCoverage(), s.secondaryMinD(), s.secondaryMaxD(),
                s.holes());
        return new CorpseRenderState(s.stage(), 1.0F, 1.0F, 63, endpoint, 0.0F);
    }

    // ── Blotch-pass "touched" set (mirrors CorpseBlotchLayoutHarness#paintStream, minus shading) ────
    private static boolean[] blotchTouched(CorpseFleshMask mask, CorpseAppearanceLayout layout, StageSpec s) {
        boolean[] occupied = new boolean[mask.surfaceSize()];
        CorpseAppearanceLayout.BlotchTexel[] secondaryRank =
                layout.secondaryBlotchRank(s.secondaryMinD(), s.secondaryMaxD());
        int secondaryNeeded = s.hasSecondary() ? Math.round(s.secondaryCoverage() * mask.surfaceSize()) : 0;
        markPainted(secondaryRank, occupied, secondaryNeeded);
        CorpseAppearanceLayout.BlotchTexel[] primaryRank =
                layout.primaryBlotchRank(s.primaryMinD(), s.primaryMaxD());
        int primaryNeeded = Math.round(s.primaryCoverage() * mask.surfaceSize());
        markPainted(primaryRank, occupied, primaryNeeded);
        return occupied;
    }

    private static void markPainted(CorpseAppearanceLayout.BlotchTexel[] rank, boolean[] occupied, int needed) {
        int applied = 0;
        for (CorpseAppearanceLayout.BlotchTexel ranked : rank) {
            if (applied >= needed) break;
            int index = ranked.index();
            if (occupied[index]) continue;
            occupied[index] = true;
            applied++;
        }
    }

    // ── Stitcher-pass "touched" set + per-archetype attribution, reconstructed from debugSlotTexelIndices ─
    private record StitcherResult(boolean[] touched, int[] archetypeTexelCounts, int[] archetypeChosenCounts,
                                  int distinctArchetypes) {}

    private static StitcherResult stitcherFootprint(CorpseFleshMask mask, CorpseAnatomy anatomy,
                                                     CorpseAppearanceLayout layout, CorpseRenderState state) {
        List<int[]> rows = CorpsePatchStitcher.debugSlotTexelIndices(mask, anatomy, layout, state);
        boolean[] touched = new boolean[mask.surfaceSize()];
        int[] archTexelCounts = new int[ARCHETYPE_COUNT];
        int[] archChosenCounts = new int[ARCHETYPE_COUNT];
        Set<Integer> distinct = new HashSet<>();
        for (int[] row : rows) {
            int archOrd = row[0];
            int revealCount = row[1];
            archChosenCounts[archOrd]++;
            distinct.add(archOrd);
            int available = row.length - 2;
            int limit = Math.min(revealCount, available);
            for (int r = 0; r < limit; r++) {
                int texelIdx = row[2 + r];
                if (touched[texelIdx]) continue; // already owned by an earlier slot -> fillPatch's rule
                touched[texelIdx] = true;
                archTexelCounts[archOrd]++;
            }
        }
        return new StitcherResult(touched, archTexelCounts, archChosenCounts, distinct.size());
    }

    /** Cross-check: calls the REAL {@code applyPatchStitch} against a uniform opaque base and confirms
     *  every pixel it actually changed corresponds to a surface texel our reconstructed footprint also
     *  marks touched, and vice versa. Returns false (and the caller counts a mismatch) if they disagree. */
    private static boolean verifyFootprintAgainstRealBake(CorpseFleshMask mask, CorpseAnatomy anatomy,
                                                           CorpseAppearanceLayout layout, CorpseRenderState state,
                                                           boolean[] reconstructedTouched) {
        int[] output = new int[UV_SIZE];
        int base = FastColor.ABGR32.color(255, 77, 77, 77);
        Arrays.fill(output, base);
        CorpsePatchStitcher.applyPatchStitch(output, mask, anatomy, layout, state, EnvironmentalRenderInputs.NONE);
        for (int i = 0; i < mask.surfaceSize(); i++) {
            CorpseFleshMask.SurfaceTexel t = mask.texel(i);
            boolean changed = output[t.baseIndex()] != base || output[t.outerIndex()] != base;
            if (changed != reconstructedTouched[i]) return false;
        }
        return true;
    }

    // ── Touched-component sizes: 4-neighbour, same part+face, ANY touched texel (no shade condition) ─
    private static List<Integer> touchedComponents(CorpseFleshMask mask, Map<Long, Integer> coordIndex,
                                                    boolean[] touched) {
        List<Integer> sizes = new ArrayList<>();
        boolean[] visited = new boolean[touched.length];
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < touched.length; i++) {
            if (!touched[i] || visited[i]) continue;
            int size = 0;
            queue.clear();
            queue.add(i);
            visited[i] = true;
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                size++;
                CorpseFleshMask.SurfaceTexel t = mask.texel(cur);
                for (int k = 0; k < 4; k++) {
                    Long key = coordKey(t.part(), t.face(), t.x() + dx[k], t.y() + dy[k]);
                    Integer nb = coordIndex.get(key);
                    if (nb == null) continue;
                    int n = nb;
                    if (!touched[n] || visited[n]) continue;
                    visited[n] = true;
                    queue.add(n);
                }
            }
            sizes.add(size);
        }
        return sizes;
    }

    // ── Blend-weight two-probe measurement (see class javadoc for the derivation) ───────────────────
    private static float[] blendStrengths(CorpseFleshMask mask, CorpseAnatomy anatomy,
                                          CorpseAppearanceLayout layout, CorpseRenderState state,
                                          boolean[] touched) {
        int[] outBlack = new int[UV_SIZE];
        int[] outWhite = new int[UV_SIZE];
        Arrays.fill(outBlack, FastColor.ABGR32.color(255, 0, 0, 0));
        Arrays.fill(outWhite, FastColor.ABGR32.color(255, 255, 255, 255));
        CorpsePatchStitcher.applyPatchStitch(outBlack, mask, anatomy, layout, state, EnvironmentalRenderInputs.NONE);
        CorpsePatchStitcher.applyPatchStitch(outWhite, mask, anatomy, layout, state, EnvironmentalRenderInputs.NONE);

        List<Float> strengths = new ArrayList<>();
        for (int i = 0; i < mask.surfaceSize(); i++) {
            if (!touched[i]) continue;
            CorpseFleshMask.SurfaceTexel t = mask.texel(i);
            int redBlack = FastColor.ABGR32.red(outBlack[t.baseIndex()]);
            int redWhite = FastColor.ABGR32.red(outWhite[t.baseIndex()]);
            float slope = (redWhite - redBlack) / 255.0F;
            float strength = 1.0F - slope;
            strengths.add(Math.max(0.0F, Math.min(1.0F, strength)));
        }
        float[] arr = new float[strengths.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = strengths.get(i);
        return arr;
    }

    // ── Shared coord-index helper (same key formula CorpsePatchStitcher itself uses internally) ─────
    private static long coordKey(CorpseFleshMask.Part part, CorpseFleshMask.Face face, int x, int y) {
        return ((long) part.ordinal() << 40) | ((long) face.ordinal() << 32)
                | ((long) (x & 0xFFFF) << 16) | (y & 0xFFFFL);
    }

    private static Map<Long, Integer> buildCoordIndex(CorpseFleshMask mask) {
        Map<Long, Integer> map = new HashMap<>(mask.surfaceSize() * 2);
        for (int i = 0; i < mask.surfaceSize(); i++) {
            CorpseFleshMask.SurfaceTexel t = mask.texel(i);
            map.put(coordKey(t.part(), t.face(), t.x(), t.y()), i);
        }
        return map;
    }

    // ── Small numeric helpers ────────────────────────────────────────────────────────────────────
    private static int countTrue(boolean[] arr) {
        int n = 0;
        for (boolean b : arr) if (b) n++;
        return n;
    }

    private static int maxOrZero(List<Integer> values) {
        int max = 0;
        for (int v : values) if (v > max) max = v;
        return max;
    }

    private static double meanOrZero(List<Integer> values) {
        if (values.isEmpty()) return 0.0;
        long sum = 0;
        for (int v : values) sum += v;
        return sum / (double) values.size();
    }

    private static double mean(float[] sortedAsc) {
        if (sortedAsc.length == 0) return Double.NaN;
        double sum = 0;
        for (float v : sortedAsc) sum += v;
        return sum / sortedAsc.length;
    }

    /** Nearest-rank percentile over an ascending-sorted array: index = round(p * (n-1)), clamped. */
    private static double percentileF(float[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sortedAsc[0];
        int idx = (int) Math.round(p * (n - 1));
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedAsc[idx];
    }

    private static double percentileI(int[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sortedAsc[0];
        int idx = (int) Math.round(p * (n - 1));
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedAsc[idx];
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String archetypeCsvHeader(String suffix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ARCHETYPE_COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(ARCHETYPE_NAMES[i]).append('_').append(suffix);
        }
        return sb.toString();
    }

    private static String intArrayCsv(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    // ── Aggregate JSON: the headline stitcher-vs-blotch touched-component comparison table ─────────
    private static void writeAggregate(Path path, Map<String, List<Integer>> stitcherComponentPool,
                                       Map<String, List<Integer>> blotchComponentPool,
                                       Map<String, List<Float>> blendStrengthPool,
                                       Map<String, long[]> archetypeChosenTotals,
                                       Map<String, long[]> archetypeTexelTotals,
                                       Map<String, double[]> fractionTotals) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"Stitcher-vs-blotch touched-component size comparison. ")
          .append("'touched components' = 4-neighbour flood fill restricted to the same part+face, ")
          .append("over ANY painted/touched texel (no shade-identity condition) -- pooled across all 517 ")
          .append("seeds per (model,stage), NOT per-seed maxima. blendStrength = two-probe-derived net ")
          .append("pull toward the patch colour (see class javadoc), pooled over every stitcher-touched ")
          .append("texel across all 517 seeds per (model,stage). Percentiles use nearest-rank: ")
          .append("index = round(p*(n-1)).\",\n");
        sb.append("  \"seedCount\": ").append(SEEDS.length).append(",\n");
        sb.append("  \"documentedStageBudgetFraction\": {\"FRESH\":0.035,\"EARLY\":0.075,\"MID\":0.130,")
          .append("\"LATE\":0.180,\"ADVANCED\":0.140},\n");
        sb.append("  \"cases\": [\n");
        boolean first = true;
        for (boolean slim : new boolean[]{false, true}) {
            String model = slim ? "slim" : "classic";
            for (StageSpec stage : STAGES) {
                String key = model + "|" + stage.name();
                if (!first) sb.append(",\n");
                first = false;
                sb.append("    {\n");
                sb.append("      \"model\": \"").append(model).append("\",\n");
                sb.append("      \"stage\": \"").append(stage.name()).append("\",\n");
                double[] frac = fractionTotals.get(key);
                double meanStitcherFrac = frac[2] == 0 ? 0 : frac[0] / frac[2];
                double meanBlotchFrac = frac[2] == 0 ? 0 : frac[1] / frac[2];
                sb.append("      \"meanStitcherTouchedFraction\": ").append(fmt(meanStitcherFrac)).append(",\n");
                sb.append("      \"meanBlotchTouchedFraction\": ").append(fmt(meanBlotchFrac)).append(",\n");
                sb.append("      \"stitcherComponents\": ")
                        .append(componentSummaryJson(stitcherComponentPool.get(key))).append(",\n");
                sb.append("      \"blotchComponents\": ")
                        .append(componentSummaryJson(blotchComponentPool.get(key))).append(",\n");
                sb.append("      \"stitcherBlendStrength\": ")
                        .append(blendSummaryJson(blendStrengthPool.get(key))).append(",\n");
                long[] chosen = archetypeChosenTotals.get(key);
                long[] texels = archetypeTexelTotals.get(key);
                sb.append("      \"archetypes\": [\n");
                for (int a = 0; a < ARCHETYPE_COUNT; a++) {
                    sb.append("        {\"name\": \"").append(ARCHETYPE_NAMES[a]).append("\", ")
                      .append("\"chosenCount\": ").append(chosen[a]).append(", ")
                      .append("\"touchedTexelTotal\": ").append(texels[a]).append("}");
                    if (a < ARCHETYPE_COUNT - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("      ]\n");
                sb.append("    }");
            }
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String componentSummaryJson(List<Integer> pool) {
        if (pool.isEmpty()) {
            return "{ \"count\": 0, \"min\": 0, \"mean\": 0, \"median\": 0, \"p95\": 0, \"max\": 0 }";
        }
        int[] sorted = new int[pool.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = pool.get(i);
        Arrays.sort(sorted);
        long sum = 0;
        for (int v : sorted) sum += v;
        double mean = sum / (double) sorted.length;
        double median = percentileI(sorted, 0.50);
        double p95 = percentileI(sorted, 0.95);
        int min = sorted[0];
        int max = sorted[sorted.length - 1];
        return "{ \"count\": " + sorted.length + ", \"min\": " + min + ", \"mean\": " + fmt(mean)
                + ", \"median\": " + fmt(median) + ", \"p95\": " + fmt(p95) + ", \"max\": " + max + " }";
    }

    private static String blendSummaryJson(List<Float> pool) {
        if (pool.isEmpty()) {
            return "{ \"count\": 0, \"min\": 0, \"mean\": 0, \"median\": 0, \"p95\": 0, \"max\": 0 }";
        }
        float[] sorted = new float[pool.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = pool.get(i);
        Arrays.sort(sorted);
        double mean = mean(sorted);
        double median = percentileF(sorted, 0.50);
        double p95 = percentileF(sorted, 0.95);
        float min = sorted[0];
        float max = sorted[sorted.length - 1];
        return "{ \"count\": " + sorted.length + ", \"min\": " + fmt(min) + ", \"mean\": " + fmt(mean)
                + ", \"median\": " + fmt(median) + ", \"p95\": " + fmt(p95) + ", \"max\": " + fmt(max) + " }";
    }

    // ── D. Visual comparisons: 3-panel PNG per (stage, representative seed), classic model ─────────
    private static void writePngs(Path pngDir) throws IOException {
        CorpseFleshMask mask = CorpseFleshMask.get(false);
        CorpseAnatomy anatomy = CorpseAnatomy.get(false);
        for (StageSpec stage : STAGES) {
            CorpseRenderState state = buildState(stage);
            for (long seed : PNG_SEEDS) {
                CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(seed, false, mask);
                boolean[] blotchTouched = blotchTouched(mask, layout, stage);
                StitcherResult sr = stitcherFootprint(mask, anatomy, layout, state);
                String title = String.format(Locale.ROOT, "%s stage - seed %d - classic model",
                        stage.name(), seed);
                Path file = pngDir.resolve(String.format(Locale.ROOT, "compare_%s_classic_seed%d.png",
                        stage.name(), seed));
                writeComparisonPng(file, mask, blotchTouched, sr.touched(), title);
            }
        }
    }

    private static void writeComparisonPng(Path outFile, CorpseFleshMask mask, boolean[] blotchTouched,
                                           boolean[] stitcherTouched, String title) throws IOException {
        int scale = 8;
        int panelSize = 64 * scale;
        int gap = 20;
        int titleH = 30;
        int captionH = 24;
        int legendH = 30;
        int width = panelSize * 3 + gap * 4;
        int height = titleH + captionH + panelSize + legendH + gap;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 18));
        g.drawString(title, gap, 22);

        int background = 0xFF202020;
        int blotchColor = 0xFF3B82F6;   // blue
        int stitcherColor = 0xFFF97316; // orange
        int bothColor = 0xFFFFFFFF;     // white

        int y0 = titleH + captionH;
        int[] xs = {gap, gap * 2 + panelSize, gap * 3 + panelSize * 2};
        String[] labels = {
                "1: BLOTCH FOOTPRINT (speckle)",
                "2: STITCHER FOOTPRINT (motifs)",
                "3: COMBINED (blue=blotch orange=stitcher white=both)"
        };
        g.setFont(new Font("Monospaced", Font.PLAIN, 13));
        for (int p = 0; p < 3; p++) {
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(labels[p], xs[p], titleH + 18);
        }

        renderPanel(img, xs[0], y0, scale, mask, blotchTouched, null, blotchColor, stitcherColor, bothColor, background);
        renderPanel(img, xs[1], y0, scale, mask, null, stitcherTouched, blotchColor, stitcherColor, bothColor, background);
        renderPanel(img, xs[2], y0, scale, mask, blotchTouched, stitcherTouched, blotchColor, stitcherColor, bothColor, background);

        int ly = y0 + panelSize + 12;
        g.setFont(new Font("Monospaced", Font.PLAIN, 13));
        g.setColor(new Color(blotchColor));
        g.fillRect(gap, ly, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString("blotch only", gap + 20, ly + 12);
        g.setColor(new Color(stitcherColor));
        g.fillRect(gap + 150, ly, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString("stitcher only", gap + 170, ly + 12);
        g.setColor(new Color(bothColor));
        g.fillRect(gap + 320, ly, 14, 14);
        g.setColor(Color.WHITE);
        g.drawString("both (overlap)", gap + 340, ly + 12);

        g.dispose();
        ImageIO.write(img, "png", outFile.toFile());
    }

    private static void renderPanel(BufferedImage img, int ox, int oy, int scale, CorpseFleshMask mask,
                                    boolean[] setA, boolean[] setB, int colorAOnly, int colorBOnly,
                                    int colorBoth, int background) {
        int[] canvas = new int[UV_SIZE];
        Arrays.fill(canvas, background);
        for (int i = 0; i < mask.surfaceSize(); i++) {
            boolean a = setA != null && setA[i];
            boolean b = setB != null && setB[i];
            if (!a && !b) continue;
            int color = (a && b) ? colorBoth : (a ? colorAOnly : colorBOnly);
            CorpseFleshMask.SurfaceTexel t = mask.texel(i);
            paintCanvas(canvas, t.baseIndex(), color);
            paintCanvas(canvas, t.outerIndex(), color);
        }
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int c = canvas[y * 64 + x];
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(ox + x * scale + dx, oy + y * scale + dy, c);
                    }
                }
            }
        }
    }

    private static void paintCanvas(int[] canvas, int uvIndex, int color) {
        if (uvIndex < 0 || uvIndex >= UV_SIZE) return;
        canvas[uvIndex] = color;
    }

    private CorpsePatchStitcherHarness() {
    }
}
