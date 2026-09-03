package com.temog.notminelegacy.whatremains.client;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * STEP 1 of the corpse flat-green "patch" fix (see PLAN.md / SOL-DESIGN.md): a headless baseline
 * harness against the CURRENT, UNMODIFIED {@link CorpseAppearanceLayout} blotch algorithm.
 * <p>
 * This class makes NO algorithmic change to production code. It drives {@link CorpseFleshMask#get}
 * and {@link CorpseAppearanceLayout#primaryBlotchRank}/{@link CorpseAppearanceLayout#secondaryBlotchRank}
 * directly (both are pure int/float math with no live-Minecraft-client dependency), and separately
 * mirrors — not calls — {@code CorpseFleshBaker#applyBlotchRank}'s shade/weight arithmetic, copied
 * verbatim below, so the shade-signature statistics reflect today's real baked behaviour without
 * ever loading {@code CorpseFleshBaker} itself (that class pulls in {@code Minecraft}/
 * {@code NativeImage}/{@code DynamicTexture} and cannot be driven off the render thread).
 * <p>
 * No visibility changes were needed anywhere in production code: this class lives in the exact same
 * package as {@code CorpseFleshMask}/{@code CorpseAppearanceLayout}, so their existing
 * package-private surface (already package-private specifically so sibling classes could read it)
 * is sufficient.
 * <p>
 * Run with an explicit {@code --out <dir>} argument; refuses to run if that path resolves inside
 * this repository (see {@link #resolveOutDir}).
 */
public final class CorpseBlotchLayoutHarness {

    private static final int FACE_COUNT = CorpseFleshMask.Face.values().length;
    private static final int REGION_COUNT = CorpseFleshMask.Part.values().length * FACE_COUNT;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    // ── Mirrored verbatim from CorpseFleshBaker.applyBlotchRank / darkenRgb / lerpRgb / lerpChannel /
    // lerpF / clamp01 / clampF / clampChannel (CorpseFleshBaker.java:893-929, 723-733, 1300-1302).
    // Behaviour must stay byte-identical to that file; do not "clean up" this arithmetic. ───────────
    private static int darkenRgb(int rgb, float factor) {
        int r = clampChannel(Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = clampChannel(Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = clampChannel(Math.round((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpRgb(int from, int to, float weight) {
        float w = clamp01(weight);
        int r = lerpChannel((from >> 16) & 0xFF, (to >> 16) & 0xFF, w);
        int g = lerpChannel((from >> 8) & 0xFF, (to >> 8) & 0xFF, w);
        int b = lerpChannel(from & 0xFF, to & 0xFF, w);
        return (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float weight) {
        return Math.round(from + (to - from) * Math.min(1.0F, weight));
    }

    private static int clampChannel(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float lerpF(float from, float to, float weight) {
        return from + (to - from) * weight;
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clampF(value, 0.0F, 1.0F);
    }

    // ── Corpus: 517 seeds (SOL-DESIGN.md section 3) ─────────────────────────────────────────────
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
     * The 8 required configurations (SOL-DESIGN.md section 3 "Corpus" table). RGB/coverage/diameter
     * values are taken verbatim either straight from {@code CorpseRenderState.ENDPOINTS} /
     * {@code OCEAN_ENDPOINTS} (EARLY/MID/LATE/ADVANCED/OCEAN_MID/OCEAN_LATE), or derived once by hand
     * from {@code CorpseRenderState.withEnvironment}'s documented nether/desert scorch formulas at
     * full (1.0) scorch applied to the LATE endpoint (NETHER_SULFUR/DESERT_LATE) — reproduced here as
     * literal numbers rather than by invoking {@code withEnvironment} itself, to keep the harness
     * decoupled from that class's environmental plumbing. A secondaryRgb of 0 means "no secondary
     * stream", matching {@code applyProceduralBlotches}'s own {@code secondaryBlotchRgb == 0 ? 0 : ...}
     * needed-count guard.
     */
    private record CaseSpec(String name, int primaryRgb, float primaryCoverage, float primaryMinD,
                            float primaryMaxD, int secondaryRgb, float secondaryCoverage,
                            float secondaryMinD, float secondaryMaxD) {
        boolean hasSecondary() {
            return secondaryRgb != 0;
        }
    }

    private static final CaseSpec[] CASES = {
            // EARLY_DECAY endpoint: ENDPOINTS[1]. No secondary blotch at this stage.
            new CaseSpec("EARLY", 0x483E2B, 0.04F, 2F, 5F, 0, 0F, 0F, 0F),
            // MID_DECAY endpoint: ENDPOINTS[2].
            new CaseSpec("MID", 0x4C632F, 0.24F, 4F, 8F, 0x653827, 0.08F, 2F, 5F),
            // LATE_DECAY endpoint: ENDPOINTS[3].
            new CaseSpec("LATE", 0x385226, 0.42F, 6F, 12F, 0x532D22, 0.04F, 2F, 5F),
            // ADVANCED_DECAY endpoint: ENDPOINTS[4]. No secondary blotch at this stage.
            new CaseSpec("ADVANCED", 0x3B2F1B, 0.20F, 3F, 7F, 0, 0F, 0F, 0F),
            // ocean_bloat MID_DECAY: OCEAN_ENDPOINTS[2].
            new CaseSpec("OCEAN_MID", 0x6B6072, 0.24F, 4F, 10F, 0x5F6B74, 0.08F, 2F, 5F),
            // ocean_bloat LATE_DECAY: OCEAN_ENDPOINTS[3].
            new CaseSpec("OCEAN_LATE", 0x5B4F5E, 0.42F, 6F, 14F, 0x4F595F, 0.04F, 2F, 5F),
            // Full (scorch=1.0) nether_ash scorch over LATE_DECAY: primaryRgb -> NETHER_ASH_GREY_RGB
            // (0x3A3634), primaryCoverage = 0.42*(1-0.35) = 0.273; secondaryRgb -> NETHER_SULFUR_RGB
            // (0x968045) fully gated, secondaryCoverage -> NETHER_SULFUR_TARGET_COVERAGE (0.10);
            // diameters untouched by the scorch branch.
            new CaseSpec("NETHER_SULFUR", 0x3A3634, 0.273F, 6F, 12F, 0x968045, 0.10F, 2F, 5F),
            // Full (scorch=1.0) desert_sun scorch over LATE_DECAY: primaryCoverage = 0.42*(1-0.90) =
            // 0.042, secondaryCoverage = 0.04*(1-0.90) = 0.004; desert scorch never touches rgb or
            // diameters, so LATE's own palette/diameters carry through unchanged.
            new CaseSpec("DESERT_LATE", 0x385226, 0.042F, 6F, 12F, 0x532D22, 0.004F, 2F, 5F),
    };

    private record PaintedTexel(int index, float depth, int shadeSignature) {}

    private record PaintResult(List<PaintedTexel> painted) {}

    private record RowStats(int surfaceSize, int requested, int applied, int zeroCentreRegions,
                            int paintedBelow005, double minDepth, double meanDepth, double stddevDepth,
                            double medianDepth, double p05Depth, double p95Depth, double maxDepth,
                            int paintedRegions, int largestComponent, String sha256) {}

    private record WorstCandidate(String model, long seed, int metric) {}

    public static void main(String[] args) throws Exception {
        Path outDir = resolveOutDir(args);
        Files.createDirectories(outDir);
        Path pngDir = outDir.resolve("png");
        Files.createDirectories(pngDir);

        // aggKey -> per-seed largestComponent samples (for p95/p99), plus running totals.
        Map<String, List<Integer>> componentSamples = new LinkedHashMap<>();
        Map<String, long[]> totals = new LinkedHashMap<>(); // [zeroCentreRegionsTotal, depthBelow005Total, seedsWithZeroCentre]
        Map<String, List<WorstCandidate>> worstByCase = new LinkedHashMap<>();
        for (CaseSpec c : CASES) worstByCase.put(c.name(), new ArrayList<>());

        long rowCount = 0;
        long startNanos = System.nanoTime();

        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(outDir.resolve("per_seed.csv"),
                StandardCharsets.UTF_8))) {
            csv.println("model,seed,case,stream,surfaceSize,requested,applied,zeroCentreRegions,"
                    + "paintedBelow005Depth,minDepth,meanDepth,stddevDepth,medianDepth,p05Depth,p95Depth,"
                    + "maxDepth,paintedRegions,largestIdenticalShadeComponent,sha256");

            for (boolean slim : new boolean[]{false, true}) {
                String model = slim ? "slim" : "classic";
                CorpseFleshMask mask = CorpseFleshMask.get(slim);
                Map<Long, Integer> coordIndex = buildCoordIndex(mask);

                for (long seed : SEEDS) {
                    CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(seed, slim, mask);

                    for (CaseSpec c : CASES) {
                        CorpseAppearanceLayout.BlotchTexel[] secondaryRank =
                                layout.secondaryBlotchRank(c.secondaryMinD(), c.secondaryMaxD());
                        CorpseAppearanceLayout.BlotchTexel[] primaryRank =
                                layout.primaryBlotchRank(c.primaryMinD(), c.primaryMaxD());

                        boolean[] occupied = new boolean[mask.surfaceSize()];
                        int secondaryNeeded = c.hasSecondary()
                                ? Math.round(c.secondaryCoverage() * mask.surfaceSize()) : 0;
                        PaintResult secondaryPaint = paintStream(secondaryRank, occupied, mask,
                                c.secondaryRgb(), secondaryNeeded);
                        int primaryNeeded = Math.round(c.primaryCoverage() * mask.surfaceSize());
                        PaintResult primaryPaint = paintStream(primaryRank, occupied, mask,
                                c.primaryRgb(), primaryNeeded);

                        RowStats secondaryStats = computeRowStats(mask, coordIndex, secondaryRank,
                                secondaryPaint, secondaryNeeded);
                        RowStats primaryStats = computeRowStats(mask, coordIndex, primaryRank,
                                primaryPaint, primaryNeeded);

                        writeCsvRow(csv, model, seed, c.name(), "SECONDARY", secondaryStats);
                        writeCsvRow(csv, model, seed, c.name(), "PRIMARY", primaryStats);
                        rowCount += 2;

                        accumulate(componentSamples, totals, model, c.name(), "SECONDARY", secondaryStats);
                        accumulate(componentSamples, totals, model, c.name(), "PRIMARY", primaryStats);

                        int worstMetric = Math.max(secondaryStats.largestComponent(),
                                primaryStats.largestComponent());
                        worstByCase.get(c.name()).add(new WorstCandidate(model, seed, worstMetric));
                    }
                }
            }
        }

        writeAggregate(outDir.resolve("aggregate.json"), componentSamples, totals);
        writeWorstPngs(pngDir, worstByCase);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        System.out.println("CorpseBlotchLayoutHarness complete: " + rowCount + " CSV rows, "
                + (SEEDS.length * 2) + " (model,seed) combinations, " + elapsedMs + " ms.");
        System.out.println("Output: " + outDir);
    }

    // ── --out argument handling / repo-boundary refusal ─────────────────────────────────────────
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

    // ── Region indexing: part.ordinal() * Face.values().length + face.ordinal() ─────────────────
    private static int regionIndex(CorpseFleshMask.Part part, CorpseFleshMask.Face face) {
        return part.ordinal() * FACE_COUNT + face.ordinal();
    }

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

    // ── Mirrors CorpseFleshBaker.applyBlotchRank's occupied/needed loop exactly (secondary-then-
    // primary order is enforced by the caller invoking this for secondary first). ─────────────────
    private static PaintResult paintStream(CorpseAppearanceLayout.BlotchTexel[] rank, boolean[] occupied,
                                           CorpseFleshMask mask, int rgb, int needed) {
        List<PaintedTexel> painted = new ArrayList<>();
        int darkRgb = darkenRgb(rgb, 0.60F);
        int applied = 0;
        for (CorpseAppearanceLayout.BlotchTexel ranked : rank) {
            if (applied >= needed) break;
            int index = ranked.index();
            if (occupied[index]) continue;
            float depth = clamp01(ranked.depth());
            int shaded = lerpRgb(rgb, darkRgb, depth * 0.65F);
            float weight = lerpF(0.60F, 0.92F, depth);
            int shadeSignature = (shaded << 8) | Math.round(weight * 255F);
            painted.add(new PaintedTexel(index, depth, shadeSignature));
            occupied[index] = true;
            applied++;
        }
        return new PaintResult(painted);
    }

    private static RowStats computeRowStats(CorpseFleshMask mask, Map<Long, Integer> coordIndex,
                                            CorpseAppearanceLayout.BlotchTexel[] rank, PaintResult paint,
                                            int requested) {
        // "Region has a centre" <=> some texel in that region has depth > 0 under the CURRENT depth
        // formula (depth = 1 - min(1, score/1.20)). Provably equivalent to "a BlobCentre was placed in
        // this region": a centre's own originating texel always scores best=0 against itself, giving
        // depth = 1 - jitter/1.20 > 0 (jitter in [0,0.20)); a texel in a truly centreless region always
        // has best=+Inf against every centre (centres only match same part+face), so it is never added
        // by scoreCandidates and falls to the 2.0 sentinel, i.e. depth == 0.0F exactly.
        boolean[] regionHasCentre = new boolean[REGION_COUNT];
        for (CorpseAppearanceLayout.BlotchTexel bt : rank) {
            if (bt.depth() > 0.0F) {
                CorpseFleshMask.SurfaceTexel t = mask.texel(bt.index());
                regionHasCentre[regionIndex(t.part(), t.face())] = true;
            }
        }
        int zeroCentreRegions = 0;
        for (boolean has : regionHasCentre) if (!has) zeroCentreRegions++;

        List<PaintedTexel> painted = paint.painted();
        int applied = painted.size();
        int below005 = 0;
        double[] depths = new double[applied];
        Set<Integer> paintedRegions = new HashSet<>();
        int[] sig = new int[mask.surfaceSize()];
        boolean[] isPainted = new boolean[mask.surfaceSize()];
        for (int i = 0; i < applied; i++) {
            PaintedTexel pt = painted.get(i);
            depths[i] = pt.depth();
            if (pt.depth() < 0.05F) below005++;
            CorpseFleshMask.SurfaceTexel t = mask.texel(pt.index());
            paintedRegions.add(regionIndex(t.part(), t.face()));
            sig[pt.index()] = pt.shadeSignature();
            isPainted[pt.index()] = true;
        }

        double minD, meanD, stddevD, medianD, p05D, p95D, maxD;
        if (applied == 0) {
            minD = meanD = stddevD = medianD = p05D = p95D = maxD = Double.NaN;
        } else {
            double[] sorted = depths.clone();
            Arrays.sort(sorted);
            minD = sorted[0];
            maxD = sorted[sorted.length - 1];
            double sum = 0;
            for (double d : sorted) sum += d;
            meanD = sum / sorted.length;
            double sqSum = 0;
            for (double d : sorted) sqSum += (d - meanD) * (d - meanD);
            stddevD = Math.sqrt(sqSum / sorted.length);
            medianD = percentile(sorted, 0.50);
            p05D = percentile(sorted, 0.05);
            p95D = percentile(sorted, 0.95);
        }

        int largestComponent = largestIdenticalShadeComponent(mask, coordIndex, painted, sig, isPainted);

        return new RowStats(mask.surfaceSize(), requested, applied, zeroCentreRegions, below005,
                minD, meanD, stddevD, medianD, p05D, p95D, maxD, paintedRegions.size(), largestComponent,
                hashRank(rank));
    }

    /** Nearest-rank percentile over an ascending-sorted array: index = round(p * (n-1)), clamped. */
    private static double percentile(double[] sortedAsc, double p) {
        int n = sortedAsc.length;
        if (n == 1) return sortedAsc[0];
        int idx = (int) Math.round(p * (n - 1));
        idx = Math.max(0, Math.min(n - 1, idx));
        return sortedAsc[idx];
    }

    /** 4-neighbour ((x±1,y)/(x,y±1)) flood fill restricted to identical part+face+shadeSignature. */
    private static int largestIdenticalShadeComponent(CorpseFleshMask mask, Map<Long, Integer> coordIndex,
                                                       List<PaintedTexel> painted, int[] sig,
                                                       boolean[] isPainted) {
        if (painted.isEmpty()) return 0;
        boolean[] visited = new boolean[mask.surfaceSize()];
        int best = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] dx = {1, -1, 0, 0};
        int[] dy = {0, 0, 1, -1};
        for (PaintedTexel pt : painted) {
            int start = pt.index();
            if (visited[start]) continue;
            int size = 0;
            queue.clear();
            queue.add(start);
            visited[start] = true;
            while (!queue.isEmpty()) {
                int cur = queue.poll();
                size++;
                CorpseFleshMask.SurfaceTexel t = mask.texel(cur);
                int curSig = sig[cur];
                for (int k = 0; k < 4; k++) {
                    Long key = coordKey(t.part(), t.face(), t.x() + dx[k], t.y() + dy[k]);
                    Integer nb = coordIndex.get(key);
                    if (nb == null) continue;
                    int n = nb;
                    if (!isPainted[n] || visited[n]) continue;
                    if (sig[n] != curSig) continue;
                    visited[n] = true;
                    queue.add(n);
                }
            }
            if (size > best) best = size;
        }
        return best;
    }

    private static String hashRank(CorpseAppearanceLayout.BlotchTexel[] rank) {
        byte[] buf = new byte[rank.length * 8];
        int p = 0;
        for (CorpseAppearanceLayout.BlotchTexel bt : rank) {
            int idx = bt.index();
            int bits = Float.floatToIntBits(bt.depth());
            buf[p++] = (byte) (idx >>> 24);
            buf[p++] = (byte) (idx >>> 16);
            buf[p++] = (byte) (idx >>> 8);
            buf[p++] = (byte) idx;
            buf[p++] = (byte) (bits >>> 24);
            buf[p++] = (byte) (bits >>> 16);
            buf[p++] = (byte) (bits >>> 8);
            buf[p++] = (byte) bits;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(buf);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeCsvRow(PrintWriter csv, String model, long seed, String caseName,
                                    String stream, RowStats s) {
        csv.println(model + "," + seed + "," + caseName + "," + stream + "," + s.surfaceSize() + ","
                + s.requested() + "," + s.applied() + "," + s.zeroCentreRegions() + ","
                + s.paintedBelow005() + "," + fmt(s.minDepth()) + "," + fmt(s.meanDepth()) + ","
                + fmt(s.stddevDepth()) + "," + fmt(s.medianDepth()) + "," + fmt(s.p05Depth()) + ","
                + fmt(s.p95Depth()) + "," + fmt(s.maxDepth()) + "," + s.paintedRegions() + ","
                + s.largestComponent() + "," + s.sha256());
    }

    private static String fmt(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String aggKey(String model, String caseName, String stream) {
        return model + "|" + caseName + "|" + stream;
    }

    private static void accumulate(Map<String, List<Integer>> componentSamples,
                                   Map<String, long[]> totals, String model, String caseName,
                                   String stream, RowStats s) {
        String key = aggKey(model, caseName, stream);
        componentSamples.computeIfAbsent(key, k -> new ArrayList<>()).add(s.largestComponent());
        long[] t = totals.computeIfAbsent(key, k -> new long[3]);
        t[0] += s.zeroCentreRegions();
        t[1] += s.paintedBelow005();
        if (s.zeroCentreRegions() > 0) t[2]++;
    }

    private static void writeAggregate(Path path, Map<String, List<Integer>> componentSamples,
                                       Map<String, long[]> totals) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"_comment\": \"BASELINE captured against the CURRENT, unmodified ")
          .append("CorpseAppearanceLayout blotch algorithm. seedCount=517 per (model,case,stream). ")
          .append("Percentiles use nearest-rank: index = round(p*(n-1)).\",\n");
        sb.append("  \"seedCount\": ").append(SEEDS.length).append(",\n");
        sb.append("  \"cases\": [\n");
        boolean firstEntry = true;
        for (boolean slim : new boolean[]{false, true}) {
            String model = slim ? "slim" : "classic";
            for (CaseSpec c : CASES) {
                if (!firstEntry) sb.append(",\n");
                firstEntry = false;
                sb.append("    {\n");
                sb.append("      \"model\": \"").append(model).append("\",\n");
                sb.append("      \"case\": \"").append(c.name()).append("\",\n");
                sb.append("      \"primary\": ").append(streamSummaryJson(componentSamples, totals,
                        aggKey(model, c.name(), "PRIMARY"))).append(",\n");
                sb.append("      \"secondary\": ").append(streamSummaryJson(componentSamples, totals,
                        aggKey(model, c.name(), "SECONDARY"))).append("\n");
                sb.append("    }");
            }
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String streamSummaryJson(Map<String, List<Integer>> componentSamples,
                                            Map<String, long[]> totals, String key) {
        List<Integer> samples = componentSamples.getOrDefault(key, List.of());
        long[] t = totals.getOrDefault(key, new long[3]);
        int max = 0, p95 = 0, p99 = 0;
        if (!samples.isEmpty()) {
            double[] sorted = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) sorted[i] = samples.get(i);
            Arrays.sort(sorted);
            max = (int) sorted[sorted.length - 1];
            p95 = (int) percentile(sorted, 0.95);
            p99 = (int) percentile(sorted, 0.99);
        }
        return "{ \"zeroCentreRegionsTotal\": " + t[0] + ", \"seedsWithZeroCentreRegion\": " + t[2]
                + ", \"depthBelow005PaintedTotal\": " + t[1]
                + ", \"largestShadeComponent\": { \"max\": " + max + ", \"p95\": " + p95
                + ", \"p99\": " + p99 + " } }";
    }

    // ── 8 worst (largest identical-shade component) (model, seed) pairs per case -> grayscale PNGs.
    // Recomputes ranks/paint results on demand for just those ~64 combinations rather than holding
    // every rank array from the main sweep in memory. ─────────────────────────────────────────────
    private static void writeWorstPngs(Path pngDir, Map<String, List<WorstCandidate>> worstByCase)
            throws IOException {
        for (CaseSpec c : CASES) {
            List<WorstCandidate> candidates = worstByCase.get(c.name());
            candidates.sort(Comparator
                    .comparingInt(WorstCandidate::metric).reversed()
                    .thenComparing(WorstCandidate::model)
                    .thenComparingLong(WorstCandidate::seed));
            int limit = Math.min(8, candidates.size());
            for (int i = 0; i < limit; i++) {
                WorstCandidate wc = candidates.get(i);
                boolean slim = "slim".equals(wc.model());
                CorpseFleshMask mask = CorpseFleshMask.get(slim);
                CorpseAppearanceLayout layout = CorpseAppearanceLayout.get(wc.seed(), slim, mask);
                CorpseAppearanceLayout.BlotchTexel[] secondaryRank =
                        layout.secondaryBlotchRank(c.secondaryMinD(), c.secondaryMaxD());
                CorpseAppearanceLayout.BlotchTexel[] primaryRank =
                        layout.primaryBlotchRank(c.primaryMinD(), c.primaryMaxD());
                boolean[] occupied = new boolean[mask.surfaceSize()];
                int secondaryNeeded = c.hasSecondary()
                        ? Math.round(c.secondaryCoverage() * mask.surfaceSize()) : 0;
                PaintResult secondaryPaint = paintStream(secondaryRank, occupied, mask, c.secondaryRgb(),
                        secondaryNeeded);
                int primaryNeeded = Math.round(c.primaryCoverage() * mask.surfaceSize());
                PaintResult primaryPaint = paintStream(primaryRank, occupied, mask, c.primaryRgb(),
                        primaryNeeded);

                BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
                for (int y = 0; y < 64; y++) {
                    for (int x = 0; x < 64; x++) {
                        img.getRaster().setSample(x, y, 0, 20); // background: unpainted texel
                    }
                }
                paintDepthOnto(img, mask, secondaryPaint);
                paintDepthOnto(img, mask, primaryPaint);

                String fileName = String.format(Locale.ROOT, "depth_%s_%s_seed%d_lc%d.png",
                        c.name(), wc.model(), wc.seed(), wc.metric());
                ImageIO.write(img, "png", pngDir.resolve(fileName).toFile());
            }
        }
    }

    /**
     * Grayscale mapping: unpainted texel = 20 (near-black background). Painted texel = 60 +
     * round(depth*195), clamped to [60,255] — a depth of exactly 0.0 still renders as a visible flat
     * mid-gray block (60), clearly distinct from the unpainted background (20), which is the whole
     * point: a large solid block at value 60 is exactly the "flat, unshaded stamp" defect.
     */
    private static void paintDepthOnto(BufferedImage img, CorpseFleshMask mask, PaintResult result) {
        for (PaintedTexel pt : result.painted()) {
            CorpseFleshMask.SurfaceTexel t = mask.texel(pt.index());
            int v = 60 + Math.round(clamp01(pt.depth()) * 195F);
            v = Math.max(60, Math.min(255, v));
            setPixel(img, t.baseIndex(), v);
            setPixel(img, t.outerIndex(), v);
        }
    }

    private static void setPixel(BufferedImage img, int uvIndex, int gray) {
        int x = uvIndex % 64;
        int y = uvIndex / 64;
        if (x < 0 || x >= 64 || y < 0 || y >= 64) return;
        img.getRaster().setSample(x, y, 0, gray);
    }

    private CorpseBlotchLayoutHarness() {
    }
}
