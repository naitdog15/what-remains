package com.temog.notminelegacy.whatremains.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.temog.notminelegacy.whatremains.client.CorpseAnatomy.Landmark;
import com.temog.notminelegacy.whatremains.client.CorpseAnatomy.Region;
import com.temog.notminelegacy.whatremains.client.CorpseAppearanceLayout.PatchSlotSeeds;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Face;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Part;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SplitMix64;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SurfaceTexel;
import com.temog.notminelegacy.whatremains.entity.DecayStage;

import net.minecraft.util.FastColor;

/**
 * SOL-PATCH-STITCH-DESIGN.md section B/C/D, gap 3 of {@code CorpseFleshBaker.bake()}'s pass-order
 * contract (RECONCILIATION.md R4 step 3 / F.1 step 9 / C.6). Draws a fixed six-instance library of
 * "patch" motifs (B.2's eight {@link PatchArchetype}s) over a corpse's flesh surface, purely as a
 * deterministic function of the appearance-seeded {@link CorpseAppearanceLayout#patchSlot(int)}
 * streams, the current {@link CorpseRenderState}, and {@link EnvironmentalRenderInputs} — never the
 * corpse UUID, never owner identity, never wall-clock/world RNG (A.2/A.3).
 * <p>
 * <b>Thread contract (F.1 step 6):</b> this class touches only {@code int[]}, and the immutable
 * {@link CorpseRenderState}, {@link EnvironmentalRenderInputs}, {@link CorpseFleshMask}, {@link
 * CorpseAnatomy}, and {@link CorpseAppearanceLayout} types handed to {@link #applyPatchStitch}. It
 * never references {@code Minecraft}, {@code TextureManager}, {@code DynamicTexture}, GL, a render
 * buffer, a live entity, or {@code CorpseFleshBaker}'s render-thread-only {@code owners} map — every
 * method here is safe to run on {@code CorpseFleshBaker}'s background bake worker. It never allocates
 * a {@code NativeImage} and never loads an asset; it only mutates the existing {@code int[] output}
 * pixel buffer in place.
 * <p>
 * <b>Placement is chosen ONCE per corpse, coverage is not.</b> Six {@link PatchInstance}s (archetype +
 * full generated geometry) are derived purely from {@code appearanceSeed} and the {@code slim} flag
 * via the layout's five per-slot seeds and are therefore stable across every stage and every
 * environmental case (G's "Stage transition pops" prevention rule — "select one immutable six-patch
 * layout; only ranked coverage/opacity interpolates"). Only how much of each instance's stable rank
 * order is <em>revealed</em>, and how each revealed texel's blend weight/colour is attenuated/retinted,
 * varies with {@link CorpseRenderState#decayStage}/{@code stageProgress} (B.3) and {@link
 * EnvironmentalRenderInputs} (D).
 * <p>
 * <b>Documented interpretation gaps (the design text is prose, not pseudocode, in a few places):</b>
 * <ul>
 *   <li>Per-archetype "favoured anatomy" prose that names a landmark rather than a whole {@link
 *   Region} (VESSEL_FAN/CREASE_BAND/TENDON_LACE's "JAW", STERNAL_LADDER's "STERNUM", PRESSURE_PANEL's
 *   "POSTERIOR_LIMB") is expressed via {@link CorpseAnatomy#hasLandmark} bonuses layered onto the
 *   region weight function ({@link #texelWeight}), not as a second selector type.</li>
 *   <li>"inner UPPER_ARM"/"inner THIGH" (VESSEL_FAN) is simplified to the whole {@link Region#UPPER_ARM}/
 *   {@link Region#THIGH} region — the design never specifies which cube face counts as "inner" for a
 *   side-dependent limb, and guessing a specific face risks being wrong in a way a full-region
 *   approximation is not.</li>
 *   <li>C.3's "remains inside its allowed anatomical regions" candidate-validity bullet is enforced by
 *   construction (every generator only ever walks/samples texels already filtered to non-forbidden
 *   regions for that archetype), not by generating an out-of-region shape and rejecting it after the
 *   fact.</li>
 *   <li>C.3's "6-18 graph-adjacent boundary edges, ≤12% overlap" adjacency-mode target for slots 4/5 is
 *   approximated by restricting those slots' anchor candidates to texels touching the parent patch (so
 *   genuine contact — the property that actually matters for C.5 seam stitching — is guaranteed) rather
 *   than additionally rejecting anchors whose exact boundary-edge count falls outside [6,18].</li>
 *   <li>PRESSURE_PANEL's "may spill exactly one texel over a WEST or EAST seam" is not implemented;
 *   the panel stays on its own face. A single spillover texel is not visually load-bearing and cross-
 *   face ellipse math for one archetype's one edge case is disproportionate here.</li>
 *   <li>Per-archetype evolution prose that describes a stage-specific *reordering* rather than a
 *   monotonic reveal count (VESSEL_FAN's SKELETAL "retains 35% of branch intersections nearest
 *   surviving flesh", DISTAL_SLOUGH's "existing hole rank — not this archetype — removing the sloughed
 *   centre") is realised through the ordinary mechanism the prose itself names: patches draw before
 *   holes (C.6), so the later, unrelated hole/reveal pass naturally erases part of whatever a patch
 *   drew, without this class needing bespoke per-stage reordering logic.</li>
 * </ul>
 */
final class CorpsePatchStitcher {

    private static final int SLOT_COUNT = 6;
    private static final float[] SLOT_SHARE = {0.25F, 0.19F, 0.17F, 0.15F, 0.13F, 0.11F};

    /** C.4's coverage budget table, FRESH..SKELETAL (index = {@code DecayStage.ordinal()}). */
    private static final float[] STAGE_BUDGET_FRACTION = {0.035F, 0.075F, 0.130F, 0.180F, 0.140F};

    /** C.3: {@code {0.80, 1.00, 1.25}} with integer weights {@code {3, 5, 2}}. */
    private static final float[] SCALE_VALUES = {0.80F, 1.00F, 1.25F};
    private static final int[] SCALE_WEIGHTS = {3, 5, 2};

    private static final Part[] LIMB_PARTS = {Part.RIGHT_ARM, Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG};

    private static final long SALT_SCALE = 0x5343414C45000000L;
    private static final long SALT_ROTATION = 0x524F544154000000L;
    private static final long SALT_DIRECTION = 0x4449524543544E00L;
    private static final long SALT_MIRROR = 0x4D4952524F520000L;

    private CorpsePatchStitcher() {}

    // ── Entry point ──────────────────────────────────────────────────────────────────────────────

    /**
     * F.1 step 9 / C.6: draws the six-patch library into {@code output} in place. Must be called
     * after the age-driven wet-rot detail crossfade and before wither/scorch/calcify/frost detail
     * (see the ordering comment on {@code CorpseFleshBaker.bake()}) and before {@code
     * applyProceduralBlotches}/{@code applyHoles}/{@code applyFullPartReveal} (C.6's own explicit
     * ordering — patches sit BELOW ordinary blotches and are erased, never restored, by holes/reveal).
     */
    static void applyPatchStitch(int[] output, CorpseFleshMask mask, CorpseAnatomy anatomy,
                                 CorpseAppearanceLayout layout, CorpseRenderState state,
                                 EnvironmentalRenderInputs env) {
        Geometry geo = Geometry.get(mask, anatomy);
        List<PatchInstance> instances = buildInstances(geo, layout, state);

        boolean[] owned = new boolean[geo.surfaceSize];
        int primary = state.primaryBlotchRgb;
        int secondary = state.secondaryBlotchRgb != 0 ? state.secondaryBlotchRgb : dark(primary, 0.72F);
        Map<PatchArchetype, EnvAdjustment> adjustments = new EnumMap<>(PatchArchetype.class);
        for (PatchArchetype archetype : PatchArchetype.values()) {
            adjustments.put(archetype, computeEnvAdjustment(archetype, env));
        }

        for (PatchInstance instance : instances) {
            fillPatch(output, geo, instance, owned, primary, secondary, adjustments.get(instance.archetype));
        }
        applySeams(output, geo, instances, primary, secondary, adjustments);
    }

    /**
     * Test-only introspection hook — package-private so a same-package harness can verify C.2/C.3/
     * C.4's slot invariants (six descriptors, archetype/limb diversity, caps, determinism) without
     * reflecting into this class's private nested types. Not called by any production code path; the
     * one real entry point is {@link #applyPatchStitch}. Returns one {@code int[]} per slot, in slot
     * order: {@code {archetypeOrdinal, revealCount, texelSurfaceIndex...}}.
     */
    static List<int[]> debugSlotTexelIndices(CorpseFleshMask mask, CorpseAnatomy anatomy,
                                             CorpseAppearanceLayout layout, CorpseRenderState state) {
        Geometry geo = Geometry.get(mask, anatomy);
        List<PatchInstance> instances = buildInstances(geo, layout, state);
        List<int[]> result = new ArrayList<>(instances.size());
        for (PatchInstance instance : instances) {
            int[] row = new int[2 + instance.texels.size()];
            row[0] = instance.archetype.ordinal();
            row[1] = instance.revealCount;
            for (int i = 0; i < instance.texels.size(); i++) row[2 + i] = instance.texels.get(i).surfaceIndex();
            result.add(row);
        }
        return result;
    }

    /** Test-only: exposes {@link #computeEnvAdjustment}'s per-archetype weight multiplier for a given
     *  environment snapshot (in {@link PatchArchetype#ordinal()} order), so a harness can directly
     *  verify D.1's per-case {@code M} values — e.g. that char suppresses patch blend weight into the
     *  0.06-0.28 range at full intensity — without re-deriving the environment matrix independently. */
    static float[] debugWeightMultipliers(EnvironmentalRenderInputs env) {
        float[] result = new float[PatchArchetype.values().length];
        for (PatchArchetype a : PatchArchetype.values()) result[a.ordinal()] = computeEnvAdjustment(a, env).weightMultiplier();
        return result;
    }

    /** Test-only: applies {@link #retint} directly for a given archetype/raw-colour/environment,
     *  decoupled from the whole-pixel blend pipeline's own low weight ceiling (B.1 caps ordinary
     *  patch blend weight at 0.52) — that ceiling means "current" always still dominates a full-bake
     *  RGB lerp somewhat, which would make an absolute post-blend hue threshold an unreliable way to
     *  test retinting in isolation. Ordinal order matches {@link PatchArchetype#values()}. */
    static int debugRetint(int archetypeOrdinal, int rawColorRgb, EnvironmentalRenderInputs env, boolean fill) {
        PatchArchetype archetype = PatchArchetype.values()[archetypeOrdinal];
        return retint(archetype, rawColorRgb, computeEnvAdjustment(archetype, env), fill);
    }

    private static List<PatchInstance> buildInstances(Geometry geo, CorpseAppearanceLayout layout,
                                                       CorpseRenderState state) {
        int referenceBudget = stageBudget(geo.surfaceSize, DecayStage.LATE_DECAY, 1.0F);
        int stageBudget = stageBudget(geo.surfaceSize, state.decayStage, state.stageProgress);

        List<PatchInstance> instances = new ArrayList<>(SLOT_COUNT);
        Set<PatchArchetype> distinctChosen = new LinkedHashSet<>();
        Map<PatchArchetype, Integer> archetypeCounts = new EnumMap<>(PatchArchetype.class);
        Map<Part, Integer> partTexelTotals = new EnumMap<>(Part.class);
        Part slot2Part = null;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            PatchSlotSeeds seeds = layout.patchSlot(slot);
            SlotRole slotRole = resolveSlotRegions(slot, geo, instances, partTexelTotals, seeds, slot2Part);
            EnumSet<Region> role = slotRole.regions();
            if (slot == 2) slot2Part = slotRole.requiredPart();
            PatchArchetype archetype = drawArchetype(geo, role, seeds.selectionSeed(), distinctChosen, archetypeCounts);
            PatchInstance instance = placePatch(slot, archetype, role, slotRole.requiredPart(), geo, seeds, instances,
                    partTexelTotals, referenceBudget);
            instances.add(instance);
            distinctChosen.add(archetype);
            archetypeCounts.merge(archetype, 1, Integer::sum);
            for (Part part : instance.parts) {
                partTexelTotals.merge(part, instance.texels.size(), Integer::sum);
            }
        }

        assignRevealCounts(instances, stageBudget, state);
        return instances;
    }

    // ── C.2: six composition slots ───────────────────────────────────────────────────────────────

    /**
     * C.2's six slot roles. {@code slot2Part} is threaded in explicitly (rather than kept as shared
     * mutable state across the call) purely so slot 3's "different Part" exclusion is checked against
     * slot 2's ORIGINAL role choice — not re-derived from slot 2's generated {@code
     * PatchInstance#parts}, which a bilateral {@code CREASE_BAND} mirror could otherwise widen to two
     * Parts and make ambiguous. {@code CorpsePatchStitcher} is called from {@code CorpseFleshBaker}'s
     * background bake pool, which runs more than one {@code BakeTask} concurrently — a {@code static}
     * field here would race between two simultaneous bakes and silently break determinism, so every
     * per-call value lives on the call stack instead.
     */
    /**
     * {@code Region} is deliberately side-agnostic (E.3: identical {@code SHOULDER}/{@code
     * UPPER_ARM}/{@code ELBOW}/{@code FOREARM}/{@code WRIST_HAND} names cover BOTH arms; identical
     * {@code HIP}/{@code THIGH}/{@code KNEE}/{@code SHIN}/{@code ANKLE_FOOT} cover BOTH legs) — so a
     * region set alone cannot pin a slot to one specific {@link Part}. {@code requiredPart} carries
     * that extra constraint for the three slots that need it (2, 3, and 5, each targeting exactly one
     * limb/least-used Part per C.2); it is {@code null} for slots 0/1/4, whose own role is legitimately
     * satisfied by any Part the region set happens to match (slot 1's {@code SHOULDER} branch, for
     * instance, is free to land on either arm).
     */
    private record SlotRole(EnumSet<Region> regions, Part requiredPart) {}

    private static SlotRole resolveSlotRegions(int slot, Geometry geo, List<PatchInstance> placed,
                                               Map<Part, Integer> partTexelTotals, PatchSlotSeeds seeds,
                                               Part slot2Part) {
        return switch (slot) {
            case 0 -> new SlotRole(EnumSet.of(Region.CHEST, Region.ABDOMEN, Region.UPPER_BACK, Region.LOWER_BACK,
                    Region.TORSO_FLANK), null);
            case 1 -> new SlotRole(EnumSet.of(Region.HEAD_CROWN, Region.FACE_UPPER, Region.FACE_LOWER,
                    Region.FACE_SIDE, Region.HEAD_BACK, Region.SHOULDER), null);
            case 2 -> {
                Part part = pickLimbPart(seeds, -1);
                yield new SlotRole(regionsOfPart(part), part);
            }
            case 3 -> {
                Part part = pickLimbPart(seeds, slot2Part.ordinal());
                yield new SlotRole(regionsOfPart(part), part);
            }
            case 4 -> new SlotRole(adjacencyRegions(geo, placed.get(slot4ParentIndex(seeds))), null);
            case 5 -> {
                Part part = leastUsedPart(partTexelTotals);
                yield new SlotRole(regionsOfPart(part), part);
            }
            default -> throw new IllegalStateException("Unexpected patch slot " + slot);
        };
    }

    /** C.2: slot 4's role is "adjacency/bridge patch near slot 0 or 2" — always resolved against
     *  whichever of those two the seed names, independent of C.3's separate 0.65-probability
     *  adjacency-MODE roll in {@link #placePatch} (that roll only tightens anchor selection within
     *  this already-parent-based role; it does not change which parent the role itself is "near"). */
    private static int slot4ParentIndex(PatchSlotSeeds seeds) {
        return Long.remainderUnsigned(seeds.selectionSeed() ^ 0x1L, 2) == 0 ? 0 : 2;
    }

    private static Part pickLimbPart(PatchSlotSeeds seeds, int excludeOrdinal) {
        List<Part> candidates = new ArrayList<>(4);
        for (Part part : LIMB_PARTS) {
            if (part.ordinal() != excludeOrdinal) candidates.add(part);
        }
        // Slot 2 has no exclusion (excludeOrdinal == -1, four candidates); slot 3 excludes slot 2's
        // own Part (three candidates) — C.2: "Slots 2 and 3 must use different Parts."
        long draw = Long.remainderUnsigned(SplitMix64.mix(seeds.selectionSeed() ^ 0x4C494D4200000000L),
                candidates.size());
        return candidates.get((int) draw);
    }

    private static EnumSet<Region> regionsOfPart(Part part) {
        return switch (part) {
            case HEAD -> EnumSet.of(Region.HEAD_CROWN, Region.FACE_UPPER, Region.FACE_LOWER, Region.FACE_SIDE,
                    Region.HEAD_BACK);
            case BODY -> EnumSet.of(Region.CHEST, Region.ABDOMEN, Region.UPPER_BACK, Region.LOWER_BACK,
                    Region.TORSO_FLANK);
            case RIGHT_ARM, LEFT_ARM -> EnumSet.of(Region.SHOULDER, Region.UPPER_ARM, Region.ELBOW,
                    Region.FOREARM, Region.WRIST_HAND);
            case RIGHT_LEG, LEFT_LEG -> EnumSet.of(Region.HIP, Region.THIGH, Region.KNEE, Region.SHIN,
                    Region.ANKLE_FOOT);
        };
    }

    /** C.2: slot 4's role is "adjacency/bridge patch near slot 0 or 2" — its eligible region set is
     *  whatever the chosen parent patch's own footprint, plus its one-hop neighbour regions, actually
     *  touches, so its anchor candidates are guaranteed to include texels genuinely near the parent. */
    private static EnumSet<Region> adjacencyRegions(Geometry geo, PatchInstance parent) {
        EnumSet<Region> regions = EnumSet.noneOf(Region.class);
        for (RawTexel t : parent.texels) {
            addRegionIfVisible(geo, regions, t.surfaceIndex());
            for (int neighbor : geo.anatomy.neighbors(t.surfaceIndex())) {
                addRegionIfVisible(geo, regions, neighbor);
            }
        }
        return regions.isEmpty() ? regionsOfPart(geo.mask.texel(parent.texels.get(0).surfaceIndex()).part()) : regions;
    }

    private static void addRegionIfVisible(Geometry geo, EnumSet<Region> regions, int surfaceIndex) {
        Region region = geo.anatomy.region(geo.mask.texel(surfaceIndex));
        if (region != Region.HIDDEN) regions.add(region);
    }

    private static Part leastUsedPart(Map<Part, Integer> partTexelTotals) {
        Part best = Part.HEAD;
        int bestCount = Integer.MAX_VALUE;
        for (Part part : Part.values()) {
            int count = partTexelTotals.getOrDefault(part, 0);
            if (count < bestCount) {
                bestCount = count;
                best = part;
            }
        }
        return best;
    }

    // ── C.2 archetype draw: weight 4 favoured / 1 permitted / 0 forbidden, forced-distinct first
    // four slots, repeat cap of two. Forcing the first four draws to be pairwise distinct guarantees
    // "at least four distinct archetypes" unconditionally rather than merely probabilistically. ─────
    private static PatchArchetype drawArchetype(Geometry geo, EnumSet<Region> role, long selectionSeed,
                                                Set<PatchArchetype> chosen, Map<PatchArchetype, Integer> counts) {
        boolean forceDistinct = chosen.size() < 4;
        List<PatchArchetype> candidates = new ArrayList<>(PatchArchetype.values().length);
        List<Integer> weights = new ArrayList<>(PatchArchetype.values().length);
        int total = 0;
        for (PatchArchetype archetype : PatchArchetype.values()) {
            if (forceDistinct && chosen.contains(archetype)) continue;
            if (counts.getOrDefault(archetype, 0) >= 2) continue;
            int weight = slotWeight(geo, archetype, role);
            if (weight <= 0) continue;
            candidates.add(archetype);
            weights.add(weight);
            total += weight;
        }
        if (candidates.isEmpty()) {
            // Every archetype forbids this slot's role regions outright (should not happen given the
            // role tables above always include at least one non-forbidden region per archetype), or
            // the repeat cap exhausted every remaining option. Fall back to the least-restrictive
            // archetype (ROT_ISLAND favours the broadest region set) rather than throwing mid-bake.
            return PatchArchetype.ROT_ISLAND;
        }
        long draw = Long.remainderUnsigned(SplitMix64.mix(selectionSeed), total);
        int cursor = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cursor += weights.get(i);
            if (draw < cursor) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    private static int slotWeight(Geometry geo, PatchArchetype archetype, EnumSet<Region> role) {
        int best = 0;
        for (Region region : role) {
            int[] indices = geo.regionIndices.get(region);
            if (indices == null || indices.length == 0) continue;
            // Sampling every texel of every role region for every archetype/slot is cheap (at most a
            // few hundred texels, done a handful of times per bake) and — unlike reducing a region to
            // one representative weight — correctly picks up a landmark bonus (JAW/STERNUM/POSTERIOR_
            // CONTACT) that only covers part of that region.
            for (int index : indices) {
                int weight = texelWeight(geo, archetype, geo.mask.texel(index));
                if (weight > best) best = weight;
            }
            if (best >= 4) return best;
        }
        return best;
    }

    private static int texelWeight(Geometry geo, PatchArchetype archetype, SurfaceTexel texel) {
        Region region = geo.anatomy.region(texel);
        if (region == Region.HIDDEN || archetype.forbiddenRegions.contains(region)) return 0;
        boolean favoured = archetype.favouredRegions.contains(region)
                || (archetype.favoursJaw && geo.anatomy.hasLandmark(texel, Landmark.JAW))
                || (archetype.favoursSternum && geo.anatomy.hasLandmark(texel, Landmark.STERNUM))
                || (archetype.favoursPosteriorContact && geo.anatomy.hasLandmark(texel, Landmark.POSTERIOR_CONTACT));
        return favoured ? 4 : 1;
    }

    // ── C.3: placement, scale, orientation, and the 16-attempt candidate loop ───────────────────────

    private static PatchInstance placePatch(int slot, PatchArchetype archetype, EnumSet<Region> role,
                                             Part requiredPart, Geometry geo, PatchSlotSeeds seeds,
                                             List<PatchInstance> accepted, Map<Part, Integer> partTexelTotals,
                                             int referenceBudget) {
        // Slot 4/5 adjacency mode (probabilities 0.65/0.45): restrict anchor candidates to texels
        // touching an already-placed parent so genuine contact — the property C.5's seam stitching
        // actually needs — is guaranteed by construction. See this class's javadoc for the documented
        // simplification versus C.3's literal 6-18 edge / 12% overlap target.
        PatchInstance parent = null;
        if (slot == 4 && !accepted.isEmpty()) {
            long roll = Long.remainderUnsigned(SplitMix64.mix(seeds.placementSeed() ^ 0x41444A3400000000L), 100);
            if (roll < 65) parent = accepted.get(slot4ParentIndex(seeds));
        } else if (slot == 5 && !accepted.isEmpty()) {
            long roll = Long.remainderUnsigned(SplitMix64.mix(seeds.placementSeed() ^ 0x41444A3500000000L), 100);
            if (roll < 45) {
                int parentIndex = (int) Long.remainderUnsigned(
                        SplitMix64.mix(seeds.selectionSeed() ^ 0x50415232000000L), accepted.size());
                parent = accepted.get(parentIndex);
            }
        }

        List<Integer> anchorPool = eligibleAnchors(geo, archetype, role, requiredPart, parent);
        if (anchorPool.isEmpty()) {
            anchorPool = eligibleAnchors(geo, archetype, regionsOfWholeCorpse(), requiredPart, parent);
        }

        PatchInstance bestValid = null;
        PatchInstance bestFallback = null;
        long[] bestFallbackTuple = null;

        for (int attempt = 0; attempt < 16; attempt++) {
            long anchorSeed = CorpseAppearanceLayout.attemptSeed(seeds.placementSeed(), attempt);
            Integer anchor = pickWeighted(geo, archetype, anchorPool, anchorSeed);
            if (anchor == null) continue;
            float sizeMultiplier = drawScale(seeds.shapeSeed(), attempt);
            int rotationSteps = drawRotation(seeds.shapeSeed(), attempt);
            boolean reversed = drawReversal(seeds.shapeSeed(), attempt);

            List<RawTexel> shape = generate(archetype, geo, anchor, seeds.shapeSeed(), attempt, sizeMultiplier,
                    rotationSteps, reversed, role);
            if (shape.isEmpty()) continue;
            shape = assignRanks(shape);

            EnumSet<Part> parts = EnumSet.noneOf(Part.class);
            for (RawTexel t : shape) parts.add(geo.mask.texel(t.surfaceIndex()).part());

            ValidityResult vr = evaluate(geo, shape, parts, accepted, partTexelTotals, referenceBudget, archetype.minArea);
            PatchInstance candidate = new PatchInstance(slot, archetype, shape, parts);
            if (vr.valid()) {
                bestValid = candidate;
                break;
            }
            long[] tuple = {vr.tripleOverlapCount(), vr.excessPartBudget(), vr.overlapCount(), anchor};
            if (bestFallbackTuple == null || compareTuples(tuple, bestFallbackTuple) < 0) {
                bestFallbackTuple = tuple;
                bestFallback = candidate;
            }
        }

        if (bestValid != null) return bestValid;
        if (bestFallback != null) return bestFallback;

        // Every one of 16 attempts produced an empty shape (should not happen given non-empty anchor
        // pools) — deterministic minimal fallback: a single-texel patch at the pool's first (lowest
        // stable-ordered) anchor, satisfying "prevents a rare seed from silently losing a slot."
        int anchor = anchorPool.isEmpty() ? firstNonHiddenTexel(geo) : anchorPool.get(0);
        List<RawTexel> single = List.of(new RawTexel(anchor, Kind.CORE, 1.0F));
        EnumSet<Part> parts = EnumSet.of(geo.mask.texel(anchor).part());
        return new PatchInstance(slot, archetype, single, parts);
    }

    private static int firstNonHiddenTexel(Geometry geo) {
        for (int i = 0; i < geo.surfaceSize; i++) {
            if (geo.anatomy.region(geo.mask.texel(i)) != Region.HIDDEN) return i;
        }
        return 0;
    }

    private static int compareTuples(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static EnumSet<Region> regionsOfWholeCorpse() {
        return EnumSet.allOf(Region.class);
    }

    private static List<Integer> eligibleAnchors(Geometry geo, PatchArchetype archetype, EnumSet<Region> role,
                                                  Part requiredPart, PatchInstance parent) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < geo.surfaceSize; i++) {
            SurfaceTexel texel = geo.mask.texel(i);
            if (requiredPart != null && texel.part() != requiredPart) continue;
            Region region = geo.anatomy.region(texel);
            if (!role.contains(region)) continue;
            if (texelWeight(geo, archetype, texel) <= 0) continue;
            if (parent != null && !touchesParent(geo, i, parent)) continue;
            result.add(i);
        }
        return result;
    }

    /**
     * C.3 adjacency mode: "place the new anchor so the two candidate masks have 6-18 graph-adjacent
     * BOUNDARY edges" — genuine boundary contact from outside the parent, not starting the new anchor
     * ON a parent texel. Deliberately EXCLUDES {@code parent.texelSet} membership (an earlier version
     * allowed it, which let a slot-4/5 shape's own centre coincide with the parent's own territory and
     * pushed its overlap far past even the general 18% ceiling in practice — confirmed empirically via
     * the harness's per-pair violation breakdown, where pairs against a parent accounted for ~98% of
     * all 18%-overlap violations before this fix).
     */
    private static boolean touchesParent(Geometry geo, int index, PatchInstance parent) {
        if (parent.texelSet.contains(index)) return false;
        for (int neighbor : geo.anatomy.neighbors(index)) {
            if (parent.texelSet.contains(neighbor)) return true;
        }
        return false;
    }

    private static Integer pickWeighted(Geometry geo, PatchArchetype archetype, List<Integer> pool, long seed) {
        if (pool.isEmpty()) return null;
        int total = 0;
        int[] weights = new int[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            weights[i] = texelWeight(geo, archetype, geo.mask.texel(pool.get(i)));
            total += weights[i];
        }
        if (total <= 0) return pool.get(0);
        long draw = Long.remainderUnsigned(SplitMix64.mix(seed), total);
        int cursor = 0;
        for (int i = 0; i < pool.size(); i++) {
            cursor += weights[i];
            if (draw < cursor) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    private static float drawScale(long shapeSeed, int attempt) {
        long value = CorpseAppearanceLayout.attemptSeed(shapeSeed ^ SALT_SCALE, attempt);
        int total = SCALE_WEIGHTS[0] + SCALE_WEIGHTS[1] + SCALE_WEIGHTS[2];
        long draw = Long.remainderUnsigned(value, total);
        int cursor = 0;
        for (int i = 0; i < SCALE_VALUES.length; i++) {
            cursor += SCALE_WEIGHTS[i];
            if (draw < cursor) return SCALE_VALUES[i];
        }
        return 1.0F;
    }

    private static int drawRotation(long shapeSeed, int attempt) {
        long value = CorpseAppearanceLayout.attemptSeed(shapeSeed ^ SALT_ROTATION, attempt);
        return (int) Long.remainderUnsigned(value, 4);
    }

    private static boolean drawReversal(long shapeSeed, int attempt) {
        long value = CorpseAppearanceLayout.attemptSeed(shapeSeed ^ SALT_DIRECTION, attempt);
        return Long.remainderUnsigned(value, 2) == 0;
    }

    // ── C.3 candidate validity ───────────────────────────────────────────────────────────────────

    private record ValidityResult(boolean valid, long tripleOverlapCount, long excessPartBudget,
                                   long overlapCount, boolean realizable) {}

    private static ValidityResult evaluate(Geometry geo, List<RawTexel> shape, EnumSet<Part> parts,
                                           List<PatchInstance> accepted, Map<Part, Integer> partTexelTotals,
                                           int referenceBudget, int minArea) {
        int overlapCount = 0;
        int tripleOverlapCount = 0;
        for (RawTexel t : shape) {
            int membership = 0;
            for (PatchInstance other : accepted) {
                if (other.texelSet.contains(t.surfaceIndex())) membership++;
            }
            if (membership >= 1) overlapCount++;
            if (membership >= 2) tripleOverlapCount++;
        }
        boolean overlapOk = shape.isEmpty() || (overlapCount / (double) shape.size()) <= 0.18;

        long excessPartBudget = 0;
        int cap = (int) Math.floor(0.35 * referenceBudget);
        Map<Part, Integer> shapeCountByPart = new EnumMap<>(Part.class);
        for (RawTexel t : shape) {
            Part part = geo.mask.texel(t.surfaceIndex()).part();
            shapeCountByPart.merge(part, 1, Integer::sum);
        }
        for (Part part : parts) {
            int existing = partTexelTotals.getOrDefault(part, 0);
            int added = shapeCountByPart.getOrDefault(part, 0);
            long projected = (long) existing + added;
            if (projected > cap) excessPartBudget += projected - cap;
        }

        boolean realizable = shape.size() >= Math.ceil(0.70 * minArea);
        boolean valid = tripleOverlapCount == 0 && overlapOk && excessPartBudget == 0 && realizable;
        return new ValidityResult(valid, tripleOverlapCount, excessPartBudget, overlapCount, realizable);
    }

    // ── C.4 coverage budget ──────────────────────────────────────────────────────────────────────

    private static int stageBudget(int surfaceSize, DecayStage stage, float stageProgress) {
        float fraction = interpolate(STAGE_BUDGET_FRACTION, stage, stageProgress);
        return Math.round(fraction * surfaceSize);
    }

    private static float interpolate(float[] endpoints, DecayStage stage, float stageProgress) {
        int index = stage.ordinal();
        if (index == 0) return endpoints[0];
        int clamped = Math.min(index, endpoints.length - 1);
        return lerp(endpoints[clamped - 1], endpoints[clamped], stageProgress);
    }

    /** C.4: per-slot requested count = budget * slotShare * archetypeExpression, largest-remainder
     *  rounded so the six rounded counts sum to {@code round(sum of the six unrounded requests)}. */
    private static void assignRevealCounts(List<PatchInstance> instances, int stageBudget, CorpseRenderState state) {
        float[] raw = new float[instances.size()];
        double totalRaw = 0.0;
        for (int i = 0; i < instances.size(); i++) {
            float expression = interpolate(instances.get(i).archetype.expression, state.decayStage, state.stageProgress);
            raw[i] = stageBudget * SLOT_SHARE[i] * expression;
            totalRaw += raw[i];
        }
        int totalRounded = (int) Math.round(totalRaw);

        int[] base = new int[raw.length];
        double[] remainder = new double[raw.length];
        int sumBase = 0;
        for (int i = 0; i < raw.length; i++) {
            base[i] = (int) Math.floor(raw[i]);
            remainder[i] = raw[i] - base[i];
            sumBase += base[i];
        }
        int deficit = totalRounded - sumBase;
        Integer[] order = new Integer[raw.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        if (deficit >= 0) {
            java.util.Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> -remainder[i]).thenComparingInt(i -> i));
            for (int k = 0; k < deficit && k < order.length; k++) base[order[k]]++;
        } else {
            java.util.Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> remainder[i]).thenComparingInt(i -> i));
            for (int k = 0; k < -deficit && k < order.length; k++) base[order[k]] = Math.max(0, base[order[k]] - 1);
        }
        for (int i = 0; i < instances.size(); i++) {
            instances.get(i).revealCount = Math.max(0, Math.min(base[i], instances.get(i).texels.size()));
        }
    }

    // ── Fill phase (ownership: lower slot number wins any overlap texel outright) ───────────────────

    private static void fillPatch(int[] output, Geometry geo, PatchInstance instance, boolean[] owned,
                                  int primary, int secondary, EnvAdjustment adjustment) {
        int limit = Math.min(instance.revealCount, instance.texels.size());
        for (int rank = 0; rank < limit; rank++) {
            RawTexel t = instance.texels.get(rank);
            if (owned[t.surfaceIndex()]) continue;
            SurfaceTexel texel = geo.mask.texel(t.surfaceIndex());
            applyOne(output, texel.baseIndex(), instance.archetype, t, primary, secondary, adjustment);
            applyOne(output, texel.outerIndex(), instance.archetype, t, primary, secondary, adjustment);
            owned[t.surfaceIndex()] = true;
        }
    }

    private static void applyOne(int[] output, int index, PatchArchetype archetype, RawTexel t,
                                 int primary, int secondary, EnvAdjustment adjustment) {
        int alpha = FastColor.ABGR32.alpha(output[index]);
        if (alpha == 0) return;
        int currentRgb = packRgb(output[index]);
        int rawColor = resolveColor(archetype, t.kind(), t.rank(), currentRgb, primary, secondary, t.z());
        float rawWeight = weightFor(t.kind(), t.z());
        int finalColor = retint(archetype, rawColor, adjustment, true);
        float finalWeight = rawWeight * adjustment.weightMultiplier();
        blendInto(output, index, finalColor, finalWeight);
    }

    // ── C.5 seam construction ────────────────────────────────────────────────────────────────────

    private static void applySeams(int[] output, Geometry geo, List<PatchInstance> instances, int primary,
                                   int secondary, Map<PatchArchetype, EnvAdjustment> adjustments) {
        long tackCounter = 0;
        for (int i = 0; i < instances.size(); i++) {
            for (int j = i + 1; j < instances.size(); j++) {
                PatchInstance a = instances.get(i);
                PatchInstance b = instances.get(j);
                List<int[]> boundary = findBoundary(geo, a, b);
                if (boundary.isEmpty()) continue;
                boundary.sort(Comparator.<int[]>comparingInt(p -> Math.min(p[0], p[1]))
                        .thenComparingInt(p -> p[0]).thenComparingInt(p -> p[1]));

                long pairId = ((long) i << 8) | j;
                long phaseSeed = SplitMix64.mix(a.seamSeed ^ pairId);
                int phase = (int) Long.remainderUnsigned(phaseSeed, 3);
                boolean suppressTack = false;

                for (int p = 0; p < boundary.size(); p++) {
                    int[] pair = boundary.get(p);
                    RawTexel aTexel = a.bySurfaceIndex.get(pair[0]);
                    RawTexel bTexel = b.bySurfaceIndex.get(pair[1]);
                    if (aTexel == null || bTexel == null) continue;

                    EnvAdjustment adjA = adjustments.get(a.archetype);
                    EnvAdjustment adjB = adjustments.get(b.archetype);
                    int aColor = resolveColor(a.archetype, aTexel.kind(), aTexel.rank(),
                            packRgb(output[geo.mask.texel(pair[0]).baseIndex()]), primary, secondary, aTexel.z());
                    int bColor = resolveColor(b.archetype, bTexel.kind(), bTexel.rank(),
                            packRgb(output[geo.mask.texel(pair[1]).baseIndex()]), primary, secondary, bTexel.z());
                    int aSeamColor = retint(a.archetype, aColor, adjA, false);
                    int bSeamColor = retint(b.archetype, bColor, adjB, false);

                    hemTexel(output, geo.mask.texel(pair[0]), bSeamColor, adjA.weightMultiplier());
                    hemTexel(output, geo.mask.texel(pair[1]), aSeamColor, adjB.weightMultiplier());

                    boolean doTack = !suppressTack && ((p + phase) % 3 == 0);
                    suppressTack = doTack;
                    if (!doTack) continue;

                    boolean aDarker = luminance(aColor) <= luminance(bColor);
                    SurfaceTexel darkTexel = geo.mask.texel(aDarker ? pair[0] : pair[1]);
                    PatchArchetype darkArchetype = aDarker ? a.archetype : b.archetype;
                    EnvAdjustment darkAdj = aDarker ? adjA : adjB;
                    int darkColor = aDarker ? aSeamColor : bSeamColor;

                    // D.2 nether rule: "one of every six eligible tacks may use #968045 sulfur" — only
                    // when nether ash is this tack's actual winning retint case.
                    if (darkAdj.winningCase() == EnvCase.NETHER
                            && Long.remainderUnsigned(SplitMix64.mix(a.seamSeed ^ (tackCounter++)), 6) == 0) {
                        darkColor = 0x968045;
                    }
                    tackTexel(output, darkTexel, darkColor, darkAdj.weightMultiplier());
                }
            }
        }
    }

    private static List<int[]> findBoundary(Geometry geo, PatchInstance a, PatchInstance b) {
        List<int[]> result = new ArrayList<>();
        int limitA = Math.min(a.revealCount, a.texels.size());
        int limitB = Math.min(b.revealCount, b.texels.size());
        Set<Integer> revealedA = new java.util.HashSet<>();
        for (int r = 0; r < limitA; r++) revealedA.add(a.texels.get(r).surfaceIndex());
        Set<Integer> revealedB = new java.util.HashSet<>();
        for (int r = 0; r < limitB; r++) revealedB.add(b.texels.get(r).surfaceIndex());
        for (int aIndex : revealedA) {
            if (revealedB.contains(aIndex)) continue; // overlap texel — ownership already handles it
            for (int neighbor : geo.anatomy.neighbors(aIndex)) {
                if (revealedB.contains(neighbor) && !revealedA.contains(neighbor)) {
                    result.add(new int[]{aIndex, neighbor});
                }
            }
        }
        return result;
    }

    private static void hemTexel(int[] output, SurfaceTexel texel, int towardColor, float multiplier) {
        blendInto(output, texel.baseIndex(), towardColor, 0.28F * multiplier * 0.5F);
        blendInto(output, texel.outerIndex(), towardColor, 0.28F * multiplier * 0.5F);
    }

    private static void tackTexel(int[] output, SurfaceTexel texel, int color, float multiplier) {
        blendInto(output, texel.baseIndex(), color, 0.62F * multiplier);
        blendInto(output, texel.outerIndex(), color, 0.62F * multiplier);
    }

    private static float luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299F * r + 0.587F * g + 0.114F * b;
    }

    // ── B.1/B.2 colour + weight resolution ───────────────────────────────────────────────────────

    private static float weightFor(Kind kind, float z) {
        if (kind.fixedRimWeight) return 0.28F;
        return Math.min(0.52F, 0.22F + 0.30F * Math.max(0.0F, z));
    }

    private static int resolveColor(PatchArchetype archetype, Kind kind, int rank, int current, int P, int Q, float z) {
        return switch (archetype) {
            case LIVID_POOL -> lerpRgb(current, Q, lerp(0.40F, 0.70F, clamp01(z)));
            case VESSEL_FAN -> switch (kind) {
                case TRUNK -> dark(Q, 0.68F);
                case BRANCH -> dark(Q, 0.78F);
                default -> lerpRgb(current, Q, 0.35F); // HALO
            };
            // Playtest fix (2026-08-23): CORE/FIELD used to be two flat solid colours — dark(P,0.62)
            // and a single fixed lerp(P,Q,0.35) — which reads as a flat, under-graduated blob once P/Q
            // go olive/green at MID_DECAY (0x4C632F)/LATE_DECAY (0x385226) (user feedback: "the green
            // patch needs more shades"). This is the archetype the class's own javadoc (~line 1106)
            // already calls out as "discrete core/field/rim bands", and it's the design's own "closest
            // patch analogue to the existing blotch language" (SOL-PATCH-STITCH-DESIGN.md #3) — i.e. the
            // one whose colour is P/Q-driven the most literally, so it reads greenest. generateRotIsland
            // now threads a continuous per-texel depth (z = clamp01(-d), the SAME signed-distance value
            // already used to draw the CORE/FIELD threshold at d<-0.45, instead of the old flat 1.0/0.6
            // constants) so both bands graduate smoothly from their own centre outward — more shading
            // steps without touching how P/Q themselves are computed (CorpseRenderState) or RIM's look.
            case ROT_ISLAND -> switch (kind) {
                case CORE -> dark(lerpRgb(P, Q, lerp(0.10F, 0.45F, clamp01(z))), lerp(0.52F, 0.72F, clamp01(z)));
                case FIELD -> lerpRgb(P, Q, lerp(0.25F, 0.62F, clamp01(z)));
                default -> lerpRgb(current, Q, 0.30F); // RIM
            };
            case CREASE_BAND -> switch (kind) {
                case RIBBON_CENTRE -> (rank % 5 == 4) ? dark(Q, 0.68F) : dark(P, 0.68F);
                default -> lerpRgb(current, P, 0.35F); // RIBBON_ADJACENT
            };
            case DISTAL_SLOUGH -> switch (kind) {
                case DEEP_CAP -> dark(Q, 0.75F);
                case PROXIMAL_RIM -> Q;
                default -> desatLift(current, 0.40F, 0.10F); // DISTAL_FIELD
            };
            case STERNAL_LADDER -> switch (kind) {
                case TRUNK -> dark(Q, 0.72F);
                case RUNG -> P;
                default -> lerpRgb(current, P, 0.28F); // LADDER_HALO
            };
            // Playtest fix (2026-08-23, "very abrupt"): default/EDGE now covers two rings —
            // generatePressurePanel's original one-hop boundary ring (z=0.0F) and a new two-hop
            // "feather" ring beyond it (z=-1.0F sentinel, see that generator's comment). The feather
            // ring blends toward P at a much shallower fraction so the panel fades out over two steps
            // instead of stopping dead at one hard ring.
            case PRESSURE_PANEL -> switch (kind) {
                case FIELD -> adjustValue(lerpRgb(P, Q, 0.50F), 0.78F);
                default -> lerpRgb(current, P, z < 0.0F ? 0.12F : 0.32F); // EDGE (feather z<0 / ring z=0)
            };
            case TENDON_LACE -> switch (kind) {
                case FIBER_SHADE -> dark(Q, 0.75F);
                default -> desatLift(current, 0.25F, 0.22F); // FIBER
            };
        };
    }

    // ── D. Environmental interaction matrix ──────────────────────────────────────────────────────

    private record EnvAdjustment(float weightMultiplier, float retintFraction, EnvCase winningCase) {}

    private static EnvAdjustment computeEnvAdjustment(PatchArchetype archetype, EnvironmentalRenderInputs env) {
        int idx = archetype.ordinal();
        float minM = 1.0F;
        EnvCase bestCase = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        float bestR = 0.0F;
        for (EnvCase c : EnvCase.values()) {
            float e = clamp01(c.intensity(env));
            if (e <= 0.0F) continue;
            float base = c.m[idx];
            minM = Math.min(minM, lerp(1.0F, base, e));
            // D.1: "choose the case maximizing e*(1-M)". Note this CAN be exactly 0 when M==1.0
            // (e.g. OCEAN/DISTAL_SLOUGH's "1.00/0.45" row) — bestCase == null is the correct "no
            // candidate yet" guard here, not score > 0, or the only active case would be silently
            // dropped whenever its own M happens to be exactly 1.0.
            if (bestCase == null || score(e, base) > bestScore
                    || (score(e, base) == bestScore && c.tieRank < bestCase.tieRank)) {
                bestScore = score(e, base);
                bestCase = c;
                bestR = c.r[idx] * e;
            }
        }
        return new EnvAdjustment(minM, bestR, bestCase);
    }

    private static float score(float e, float base) {
        return e * (1.0F - base);
    }

    private static int retint(PatchArchetype archetype, int rawColor, EnvAdjustment adjustment, boolean fill) {
        if (adjustment.winningCase() == null || adjustment.retintFraction() <= 0.0F) return rawColor;
        // D.2: frost's FILL target is "current RGB remapped toward H=208, S<=0.18, V>=0.70" (a
        // function of the archetype's own colour), but its SEAM target is the flat #7F8FA6 like every
        // other case — only the fill branch uses the remap.
        if (adjustment.winningCase() == EnvCase.FROST && fill) {
            return lerpRgb(rawColor, remapFrostTarget(rawColor), adjustment.retintFraction());
        }
        int target = fill ? adjustment.winningCase().fillTarget(archetype) : adjustment.winningCase().seamTarget();
        return lerpRgb(rawColor, target, adjustment.retintFraction());
    }

    private static int remapFrostTarget(int rgb) {
        float[] hsb = new float[3];
        Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
        float hueDeg = hsb[0] * 360.0F;
        float delta = ((208.0F - hueDeg) % 360.0F + 540.0F) % 360.0F - 180.0F;
        float newHueDeg = hueDeg + delta * 0.85F;
        float newHue = ((newHueDeg / 360.0F) % 1.0F + 1.0F) % 1.0F;
        float newSat = Math.min(hsb[1], 0.18F);
        float newVal = Math.max(hsb[2], 0.70F);
        return Color.HSBtoRGB(newHue, newSat, newVal) & 0xFFFFFF;
    }

    private enum EnvCase {
        CHAR(new float[]{0.08F, 0.06F, 0.12F, 0.28F, 0.10F, 0.12F, 0.08F, 0.24F},
                new float[]{0.90F, 0.95F, 0.90F, 0.85F, 0.90F, 0.90F, 0.90F, 0.80F}, 0x2A2220, 0x191B1C, 0) {
            @Override float intensity(EnvironmentalRenderInputs env) { return env.charLevel(); }
        },
        DESERT(new float[]{0.30F, 0.28F, 0.35F, 0.90F, 0.60F, 0.70F, 0.45F, 0.80F},
                new float[]{0.80F, 0.85F, 0.80F, 0.65F, 0.70F, 0.65F, 0.75F, 0.65F}, 0x8B6A42, 0x4A3420, 4) {
            @Override float intensity(EnvironmentalRenderInputs env) {
                return EnvironmentalRenderInputs.SCORCH_STYLE_DESERT.equals(env.scorchStyle()) ? env.scorchLevel() : 0.0F;
            }
        },
        NETHER(new float[]{0.22F, 0.25F, 0.45F, 0.90F, 0.60F, 0.70F, 0.40F, 0.75F},
                new float[]{0.85F, 0.85F, 0.75F, 0.65F, 0.70F, 0.65F, 0.80F, 0.65F}, 0x3A3634, 0x191B1C, 4) {
            @Override float intensity(EnvironmentalRenderInputs env) {
                return EnvironmentalRenderInputs.SCORCH_STYLE_NETHER.equals(env.scorchStyle()) ? env.scorchLevel() : 0.0F;
            }
        },
        FROST(new float[]{0.22F, 0.30F, 0.38F, 0.78F, 0.90F, 0.50F, 0.32F, 0.90F},
                new float[]{0.90F, 0.85F, 0.85F, 0.70F, 0.60F, 0.75F, 0.85F, 0.60F}, 0, 0x7F8FA6, 1) {
            @Override float intensity(EnvironmentalRenderInputs env) { return env.frostLevel(); }
        },
        OCEAN(new float[]{0.75F, 0.45F, 0.60F, 0.50F, 1.00F, 0.40F, 0.80F, 0.45F},
                new float[]{0.60F, 0.75F, 0.65F, 0.70F, 0.45F, 0.75F, 0.55F, 0.75F}, 0, 0x3A3B3E, 2) {
            @Override float intensity(EnvironmentalRenderInputs env) {
                return EnvironmentalRenderInputs.WATERLOGGED_STYLE_OCEAN.equals(env.waterloggedStyle())
                        ? env.waterloggedLevel() : 0.0F;
            }
            @Override int fillTarget(PatchArchetype a) {
                return (a == PatchArchetype.LIVID_POOL || a == PatchArchetype.PRESSURE_PANEL) ? 0x6B6072 : 0x5F6B74;
            }
        },
        CAVE(new float[]{0.20F, 0.25F, 0.45F, 0.90F, 0.70F, 0.80F, 0.40F, 0.85F},
                new float[]{0.90F, 0.90F, 0.80F, 0.60F, 0.65F, 0.60F, 0.85F, 0.55F}, 0xD6D2C4, 0x6E6C68, 3) {
            @Override float intensity(EnvironmentalRenderInputs env) { return env.calcifyLevel(); }
        },
        WITHER(new float[]{0.45F, 0.18F, 0.75F, 0.65F, 0.60F, 0.18F, 0.55F, 0.65F},
                new float[]{0.75F, 0.95F, 0.60F, 0.70F, 0.70F, 0.95F, 0.75F, 0.70F}, 0x201014, 0x292929, 5) {
            @Override float intensity(EnvironmentalRenderInputs env) { return env.witherScarred() ? 1.0F : 0.0F; }
        };

        final float[] m;
        final float[] r;
        final int fillRgb;
        final int seamRgb;
        final int tieRank;

        EnvCase(float[] m, float[] r, int fillRgb, int seamRgb, int tieRank) {
            this.m = m;
            this.r = r;
            this.fillRgb = fillRgb;
            this.seamRgb = seamRgb;
            this.tieRank = tieRank;
        }

        abstract float intensity(EnvironmentalRenderInputs env);

        int fillTarget(PatchArchetype archetype) { return fillRgb; }

        int seamTarget() { return seamRgb; }
    }

    // ── B.2 archetype table ──────────────────────────────────────────────────────────────────────

    /** B.2's eight archetypes, declared in the SAME order as B.2/B.3/D.1's own numbering so {@code
     *  ordinal()} directly indexes {@link EnvCase}'s per-archetype M/R arrays and {@link
     *  PatchArchetype#expression}'s B.3 endpoint row. */
    private enum PatchArchetype {
        LIVID_POOL(EnumSet.of(Region.CHEST, Region.ABDOMEN, Region.UPPER_BACK, Region.LOWER_BACK,
                Region.UPPER_ARM, Region.THIGH),
                EnumSet.of(Region.HEAD_CROWN, Region.WRIST_HAND, Region.ANKLE_FOOT),
                false, false, false, 14, 42,
                new float[]{0.45F, 0.80F, 1.00F, 0.65F, 0.20F}),
        VESSEL_FAN(EnumSet.of(Region.FACE_SIDE, Region.CHEST, Region.SHOULDER, Region.UPPER_ARM, Region.THIGH),
                EnumSet.of(Region.WRIST_HAND, Region.ANKLE_FOOT),
                true, false, false, 18, 40,
                new float[]{0.25F, 0.55F, 1.00F, 0.80F, 0.35F}),
        ROT_ISLAND(EnumSet.of(Region.CHEST, Region.ABDOMEN, Region.UPPER_BACK, Region.LOWER_BACK,
                Region.TORSO_FLANK, Region.SHOULDER, Region.UPPER_ARM, Region.THIGH, Region.HEAD_BACK),
                EnumSet.of(Region.FACE_UPPER, Region.FACE_LOWER, Region.WRIST_HAND, Region.ANKLE_FOOT),
                false, false, false, 16, 48,
                new float[]{0.00F, 0.30F, 0.80F, 1.00F, 0.55F}),
        CREASE_BAND(EnumSet.of(Region.ELBOW, Region.KNEE, Region.WRIST_HAND, Region.ANKLE_FOOT),
                EnumSet.noneOf(Region.class),
                true, false, false, 10, 32,
                new float[]{0.15F, 0.45F, 0.75F, 1.00F, 0.70F}),
        DISTAL_SLOUGH(EnumSet.of(Region.WRIST_HAND, Region.ANKLE_FOOT),
                EnumSet.complementOf(EnumSet.of(Region.WRIST_HAND, Region.ANKLE_FOOT)),
                false, false, false, 12, 36,
                new float[]{0.00F, 0.15F, 0.55F, 1.00F, 0.85F}),
        STERNAL_LADDER(EnumSet.of(Region.UPPER_BACK),
                EnumSet.complementOf(EnumSet.of(Region.CHEST, Region.ABDOMEN, Region.UPPER_BACK)),
                false, true, false, 20, 38,
                new float[]{0.10F, 0.35F, 0.75F, 1.00F, 0.65F}),
        // Playtest fix (2026-08-23, "less common panel designs, it's very abrupt"): favouredRegions
        // used to be {HEAD_BACK, UPPER_BACK, LOWER_BACK} — i.e. this archetype tied for slotWeight's
        // "favoured" (4) tier on slot 0 (torso, the single biggest slot at 25% of SLOT_SHARE, via
        // UPPER_BACK/LOWER_BACK) AND slot 1 (head, 19% share, via HEAD_BACK), the two largest-share
        // slots, making it draw as often as archetypes like ROT_ISLAND/LIVID_POOL there. Emptied to
        // EnumSet.noneOf so region membership alone now only ever yields texelWeight's "permitted" (1)
        // tier there (forbiddenRegions is untouched, so nothing that could draw before is now
        // forbidden) — favoursPosteriorContact=true (unchanged) still gives it the narrower, rarer
        // "favoured" (4) tier wherever Landmark.POSTERIOR_CONTACT actually applies (design doc's
        // "POSTERIOR_LIMB"), so it still CAN win a slot, just far less often than before.
        PRESSURE_PANEL(EnumSet.noneOf(Region.class),
                EnumSet.of(Region.FACE_UPPER, Region.FACE_LOWER, Region.CHEST, Region.ABDOMEN),
                false, false, true, 18, 46,
                new float[]{0.40F, 0.75F, 1.00F, 0.60F, 0.20F}),
        TENDON_LACE(EnumSet.of(Region.FOREARM, Region.SHIN),
                EnumSet.of(Region.FACE_UPPER, Region.FACE_LOWER, Region.HEAD_BACK, Region.CHEST, Region.ABDOMEN,
                        Region.UPPER_BACK, Region.LOWER_BACK, Region.HEAD_CROWN),
                true, false, false, 10, 28,
                new float[]{0.00F, 0.10F, 0.40F, 0.80F, 1.00F});

        final EnumSet<Region> favouredRegions;
        final EnumSet<Region> forbiddenRegions;
        final boolean favoursJaw;
        final boolean favoursSternum;
        final boolean favoursPosteriorContact;
        final int minArea;
        final int maxArea;
        final float[] expression;

        PatchArchetype(EnumSet<Region> favouredRegions, EnumSet<Region> forbiddenRegions, boolean favoursJaw,
                       boolean favoursSternum, boolean favoursPosteriorContact, int minArea, int maxArea,
                       float[] expression) {
            this.favouredRegions = favouredRegions;
            this.forbiddenRegions = forbiddenRegions;
            this.favoursJaw = favoursJaw;
            this.favoursSternum = favoursSternum;
            this.favoursPosteriorContact = favoursPosteriorContact;
            this.minArea = minArea;
            this.maxArea = maxArea;
            this.expression = expression;
        }
    }

    private enum Kind {
        CORE(false), FIELD(false), RIM(true),
        TRUNK(false), BRANCH(false), HALO(true),
        RIBBON_CENTRE(false), RIBBON_ADJACENT(false),
        DISTAL_FIELD(false), PROXIMAL_RIM(true), DEEP_CAP(false),
        RUNG(false), LADDER_HALO(true),
        EDGE(false),
        FIBER(false), FIBER_SHADE(false);

        final boolean fixedRimWeight;

        Kind(boolean fixedRimWeight) {
            this.fixedRimWeight = fixedRimWeight;
        }
    }

    /** {@code rank} is this texel's position in its patch's own stable reveal order (C.4: "core first
     *  for line motifs, field then rim for solid motifs"). Assigned by {@link #assignRanks} as a
     *  post-processing pass over each generator's returned list, rather than threaded through all
     *  eight generators individually, so only CREASE_BAND's "every fifth centre texel" colour rule
     *  (B.2 #4) needs to reason about it. */
    private record RawTexel(int surfaceIndex, Kind kind, float z, int rank) {
        /** Convenience constructor for the eight shape generators, which do not yet know a texel's
         *  final rank at generation time — {@link #assignRanks} fills it in afterward. */
        RawTexel(int surfaceIndex, Kind kind, float z) {
            this(surfaceIndex, kind, z, 0);
        }
    }

    private static List<RawTexel> assignRanks(List<RawTexel> shape) {
        List<RawTexel> result = new ArrayList<>(shape.size());
        for (int i = 0; i < shape.size(); i++) {
            RawTexel t = shape.get(i);
            result.add(new RawTexel(t.surfaceIndex(), t.kind(), t.z(), i));
        }
        return result;
    }

    private static final class PatchInstance {
        final int slot;
        final PatchArchetype archetype;
        final List<RawTexel> texels;
        final Set<Integer> texelSet;
        final Map<Integer, RawTexel> bySurfaceIndex;
        final EnumSet<Part> parts;
        final long seamSeed;
        int revealCount;

        PatchInstance(int slot, PatchArchetype archetype, List<RawTexel> texels, EnumSet<Part> parts) {
            this.slot = slot;
            this.archetype = archetype;
            this.texels = texels;
            this.texelSet = new java.util.HashSet<>();
            this.bySurfaceIndex = new HashMap<>();
            for (RawTexel t : texels) {
                texelSet.add(t.surfaceIndex());
                bySurfaceIndex.put(t.surfaceIndex(), t);
            }
            this.parts = parts;
            this.seamSeed = SplitMix64.mix(((long) slot + 1L) * 0x9E3779B97F4A7C15L) ^ (long) archetype.ordinal();
        }
    }

    // ── B.2 shape generators ─────────────────────────────────────────────────────────────────────

    private static List<RawTexel> generate(PatchArchetype archetype, Geometry geo, int anchorIndex, long shapeSeed,
                                           int attempt, float sizeMultiplier, int rotationSteps, boolean reversed,
                                           EnumSet<Region> role) {
        long localSeed = CorpseAppearanceLayout.attemptSeed(shapeSeed, attempt);
        return switch (archetype) {
            case LIVID_POOL -> generateEllipseUnion(geo, anchorIndex, localSeed, sizeMultiplier, rotationSteps,
                    2.5F, 4.5F, 1.5F, 3.0F, 0.16F, archetype);
            case ROT_ISLAND -> generateRotIsland(geo, anchorIndex, localSeed, sizeMultiplier, archetype);
            case PRESSURE_PANEL -> generatePressurePanel(geo, anchorIndex, localSeed, sizeMultiplier, archetype);
            case VESSEL_FAN -> generateVesselFan(geo, anchorIndex, localSeed, sizeMultiplier, reversed, archetype);
            case STERNAL_LADDER -> generateSternalLadder(geo, anchorIndex, localSeed, sizeMultiplier, archetype);
            case TENDON_LACE -> generateTendonLace(geo, anchorIndex, localSeed, sizeMultiplier, reversed, archetype);
            case CREASE_BAND -> generateCreaseBand(geo, anchorIndex, localSeed, sizeMultiplier, archetype);
            case DISTAL_SLOUGH -> generateDistalSlough(geo, anchorIndex, localSeed, sizeMultiplier, archetype);
        };
    }

    /** LIVID_POOL (B.2 #1): union of two ellipses, continuous edge→core gradient, no discrete rim. */
    private static List<RawTexel> generateEllipseUnion(Geometry geo, int anchorIndex, long seed, float scale,
                                                        int rotation, float aMin, float aMax, float bMin,
                                                        float bMax, float noiseAmp, PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        Face face = anchor.face();
        SplitMix64 rnd = new SplitMix64(seed);
        float a = (aMin + rnd.nextInt(1000) / 1000.0F * (aMax - aMin)) * scale;
        float b = (bMin + rnd.nextInt(1000) / 1000.0F * (bMax - bMin)) * scale;
        float offsetFrac = 0.35F + rnd.nextInt(1000) / 1000.0F * 0.20F;
        int[] dir = rotationVector(rotation);
        float offX = offsetFrac * a * dir[0];
        float offY = offsetFrac * a * dir[1];

        int ax = anchor.x();
        int ay = anchor.y();
        int radius = Math.round(Math.max(a, b) * 1.4F) + 1;
        List<double[]> scored = new ArrayList<>();
        for (int y = ay - radius; y <= ay + radius; y++) {
            for (int x = ax - radius; x <= ax + radius; x++) {
                Integer index = geo.coordIndex.get(coordKey(part, face, x, y));
                if (index == null) continue;
                if (!eligible(geo, archetype, index)) continue;
                double dx1 = (x - ax) / (double) a;
                double dy1 = (y - ay) / (double) b;
                double d1 = StrictMath.sqrt(dx1 * dx1 + dy1 * dy1) - 1.0;
                double dx2 = (x - (ax + offX)) / (0.65 * a);
                double dy2 = (y - (ay + offY)) / (0.65 * b);
                double d2 = StrictMath.sqrt(dx2 * dx2 + dy2 * dy2) - 1.0;
                double d = Math.min(d1, d2) + noiseAmp * noise(seed, x, y);
                if (d <= 0.0) scored.add(new double[]{q16(d), index, -d});
            }
        }
        scored.sort(Comparator.<double[]>comparingLong(s -> (long) s[0]).thenComparingLong(s -> (long) s[1]));
        List<RawTexel> result = new ArrayList<>(scored.size());
        for (double[] s : scored) {
            float z = (float) Math.max(0.0, Math.min(1.0, s[2]));
            result.add(new RawTexel((int) s[1], Kind.CORE, z));
        }
        return result;
    }

    /** ROT_ISLAND (B.2 #3): three-ellipse island with discrete core/field/rim bands. */
    private static List<RawTexel> generateRotIsland(Geometry geo, int anchorIndex, long seed, float scale,
                                                     PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        Face face = anchor.face();
        SplitMix64 rnd = new SplitMix64(seed);
        float[][] centres = new float[3][2];
        centres[0] = new float[]{anchor.x(), anchor.y()};
        int cursor = anchorIndex;
        for (int c = 1; c < 3; c++) {
            int steps = 1 + rnd.nextInt(3);
            int walker = cursor;
            for (int s = 0; s < steps; s++) {
                int[] neighbors = geo.anatomy.neighbors(walker);
                if (neighbors.length == 0) break;
                int pick = neighbors[rnd.nextInt(neighbors.length)];
                if (geo.mask.texel(pick).part() == part && geo.mask.texel(pick).face() == face) walker = pick;
            }
            SurfaceTexel t = geo.mask.texel(walker);
            centres[c] = new float[]{t.x(), t.y()};
            cursor = walker;
        }
        float[] radii = new float[3];
        for (int c = 0; c < 3; c++) radii[c] = (1.5F + rnd.nextInt(1000) / 1000.0F * 2.0F) * scale;

        int ax = anchor.x();
        int ay = anchor.y();
        int radius = Math.round(radii[0] * 1.6F) + 3;
        List<double[]> coreField = new ArrayList<>();
        Set<Integer> insideSet = new java.util.HashSet<>();
        for (int y = ay - radius; y <= ay + radius; y++) {
            for (int x = ax - radius; x <= ax + radius; x++) {
                Integer index = geo.coordIndex.get(coordKey(part, face, x, y));
                if (index == null || !eligible(geo, archetype, index)) continue;
                double best = Double.POSITIVE_INFINITY;
                for (int c = 0; c < 3; c++) {
                    double dx = (x - centres[c][0]) / radii[c];
                    double dy = (y - centres[c][1]) / radii[c];
                    best = Math.min(best, StrictMath.sqrt(dx * dx + dy * dy) - 1.0);
                }
                double d = best + 0.28 * noise(seed, x, y);
                if (d <= 0.0) {
                    coreField.add(new double[]{q16(d), index, d});
                    insideSet.add(index);
                }
            }
        }
        coreField.sort(Comparator.<double[]>comparingLong(s -> (long) s[0]).thenComparingLong(s -> (long) s[1]));
        List<RawTexel> result = new ArrayList<>();
        for (double[] s : coreField) {
            double d = s[2];
            Kind kind = d < -0.45 ? Kind.CORE : Kind.FIELD;
            // Playtest fix (2026-08-23): z used to be a flat per-Kind constant (1.0F for every CORE
            // texel, 0.6F for every FIELD texel) — resolveColor's ROT_ISLAND case now reads this as a
            // continuous depth instead, so reuse the same signed-distance `d` that already drew the
            // CORE/FIELD threshold (deeper/more negative d = further from the boundary) rather than
            // discarding that gradient at classification time.
            float z = (float) Math.max(0.0, Math.min(1.0, -d));
            result.add(new RawTexel((int) s[1], kind, z));
        }
        List<Integer> rimCandidates = new ArrayList<>();
        for (int inside : insideSet) {
            for (int neighbor : geo.anatomy.neighbors(inside)) {
                if (!insideSet.contains(neighbor) && eligible(geo, archetype, neighbor)
                        && !containsIndex(rimCandidates, neighbor)) {
                    rimCandidates.add(neighbor);
                }
            }
        }
        rimCandidates.sort(Comparator.naturalOrder());
        for (int rim : rimCandidates) result.add(new RawTexel(rim, Kind.RIM, 0.0F));
        return result;
    }

    /** PRESSURE_PANEL (B.2 #7): one ellipse on the anchor's own face with its lower third flattened. */
    private static List<RawTexel> generatePressurePanel(Geometry geo, int anchorIndex, long seed, float scale,
                                                         PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        Face face = anchor.face();
        SplitMix64 rnd = new SplitMix64(seed);
        float a = (2.5F + rnd.nextInt(1000) / 1000.0F * 2.0F) * scale;
        float b = (2.0F + rnd.nextInt(1000) / 1000.0F * 3.0F) * scale;
        float yFlat = anchor.y() + (2.0F / 3.0F) * b;

        int ax = anchor.x();
        int ay = anchor.y();
        int radius = Math.round(Math.max(a, b) * 1.3F) + 1;
        List<double[]> field = new ArrayList<>();
        Set<Integer> insideSet = new java.util.HashSet<>();
        for (int y = ay - radius; y <= ay + radius; y++) {
            for (int x = ax - radius; x <= ax + radius; x++) {
                Integer index = geo.coordIndex.get(coordKey(part, face, x, y));
                if (index == null || !eligible(geo, archetype, index)) continue;
                double dx = (x - ax) / (double) a;
                double dy = (y - ay) / (double) b;
                double ellipse = StrictMath.sqrt(dx * dx + dy * dy) - 1.0;
                double flat = (y - yFlat) / b;
                double d = Math.max(ellipse, flat) + 0.12 * noise(seed, x, y);
                if (d <= 0.0) {
                    field.add(new double[]{q16(d), index, -d});
                    insideSet.add(index);
                }
            }
        }
        field.sort(Comparator.<double[]>comparingLong(s -> (long) s[0]).thenComparingLong(s -> (long) s[1]));
        List<RawTexel> result = new ArrayList<>();
        for (double[] s : field) {
            float z = (float) Math.max(0.0, Math.min(1.0, s[2]));
            result.add(new RawTexel((int) s[1], Kind.FIELD, z));
        }
        List<Integer> edgeCandidates = new ArrayList<>();
        for (int inside : insideSet) {
            for (int neighbor : geo.anatomy.neighbors(inside)) {
                if (!insideSet.contains(neighbor) && eligible(geo, archetype, neighbor)
                        && !containsIndex(edgeCandidates, neighbor)) {
                    edgeCandidates.add(neighbor);
                }
            }
        }
        edgeCandidates.sort(Comparator.naturalOrder());
        for (int edge : edgeCandidates) result.add(new RawTexel(edge, Kind.EDGE, 0.0F));

        // Playtest fix (2026-08-23): "PRESSURE_PANEL... very abrupt" — the panel used to stop dead at
        // this single one-hop EDGE ring, so the flat-bottom clip (and the ellipse boundary generally)
        // read as a hard, geometric, human-made line against the organic patches around it. Add a
        // second, two-hop "feather" ring just beyond it, tagged with the sentinel z=-1.0F (RIM/HALO-
        // style Kinds already use z<=0 as "no depth"; -1 additionally distinguishes this ring from the
        // ordinary z=0.0F edge ring in resolveColor's PRESSURE_PANEL/EDGE case below) so the transition
        // into untouched flesh happens over two graduated steps instead of one hard cutoff. Deliberately
        // NOT a new Kind/weightFor change (that formula floors at 0.22 for every non-fixed Kind and is
        // shared by every archetype using EDGE/FIELD/CORE) — the softening instead comes from resolveColor
        // blending this ring toward a far smaller fraction of P, the same "small-fraction lerp toward
        // current" idiom HALO/RIBBON_ADJACENT/LADDER_HALO already use for their own soft halos.
        Set<Integer> edgeSet = new java.util.HashSet<>(edgeCandidates);
        List<Integer> featherCandidates = new ArrayList<>();
        for (int edge : edgeCandidates) {
            for (int neighbor : geo.anatomy.neighbors(edge)) {
                if (!insideSet.contains(neighbor) && !edgeSet.contains(neighbor)
                        && eligible(geo, archetype, neighbor) && !containsIndex(featherCandidates, neighbor)) {
                    featherCandidates.add(neighbor);
                }
            }
        }
        featherCandidates.sort(Comparator.naturalOrder());
        for (int feather : featherCandidates) result.add(new RawTexel(feather, Kind.EDGE, -1.0F));
        return result;
    }

    /** VESSEL_FAN (B.2 #2): a trunk graph-walk along the anchor part's long axis, two branches, and a
     *  one-hop halo. Straight/left/right weights 6/2/2, no immediate reversal, terminates on leaving
     *  the archetype's eligible region set. */
    private static List<RawTexel> generateVesselFan(Geometry geo, int anchorIndex, long seed, float scale,
                                                     boolean reversed, PatchArchetype archetype) {
        SplitMix64 rnd = new SplitMix64(seed);
        int length = Math.max(2, Math.round((4 + rnd.nextInt(5)) * scale));
        int[] axis = longAxis(geo.mask.texel(anchorIndex).part(), reversed);

        List<Integer> trunk = walk(geo, archetype, anchorIndex, axis, length, rnd);
        List<Integer> branches = new ArrayList<>();
        int[] branchPoints = {trunk.size() / 3, 2 * trunk.size() / 3};
        for (int bp : branchPoints) {
            if (bp <= 0 || bp >= trunk.size()) continue;
            int start = trunk.get(bp);
            int branchLen = 2 + rnd.nextInt(3);
            int[] perp = {-axis[1], axis[0]};
            if (rnd.nextInt(2) == 0) perp = new int[]{axis[1], -axis[0]};
            List<Integer> branch = walk(geo, archetype, start, perp, branchLen, rnd);
            for (int idx : branch) if (idx != start) branches.add(idx);
        }

        Set<Integer> core = new java.util.HashSet<>(trunk);
        core.addAll(branches);
        List<Integer> halo = new ArrayList<>();
        for (int idx : core) {
            for (int neighbor : geo.anatomy.neighbors(idx)) {
                if (!core.contains(neighbor) && eligible(geo, archetype, neighbor) && !halo.contains(neighbor)) {
                    halo.add(neighbor);
                }
            }
        }
        halo.sort(Comparator.naturalOrder());

        List<RawTexel> result = new ArrayList<>();
        for (int idx : trunk) result.add(new RawTexel(idx, Kind.TRUNK, 1.0F));
        for (int idx : branches) result.add(new RawTexel(idx, Kind.BRANCH, 0.4F));
        for (int idx : halo) result.add(new RawTexel(idx, Kind.HALO, 0.0F));
        return result;
    }

    /** Straight/left/right weights 6/2/2, no immediate reversal, stops when it would leave the
     *  archetype's eligible region set or the surface entirely — shared by VESSEL_FAN's trunk/branches
     *  and used as the structural basis for the other graph-walk archetypes below. */
    private static List<Integer> walk(Geometry geo, PatchArchetype archetype, int start, int[] initialDir,
                                      int steps, SplitMix64 rnd) {
        List<Integer> path = new ArrayList<>();
        path.add(start);
        int current = start;
        int[] dir = initialDir;
        int lastTurn = 0;
        for (int i = 0; i < steps; i++) {
            SurfaceTexel t = geo.mask.texel(current);
            int roll = rnd.nextInt(10);
            int turn = roll < 6 ? 0 : (roll < 8 ? -1 : 1);
            if (turn != 0 && turn == -lastTurn) turn = 0; // disallow immediate reversal
            int[] candidateDir = turn == 0 ? dir : rotate90(dir, turn);
            Integer next = geo.coordIndex.get(coordKey(t.part(), t.face(), t.x() + candidateDir[0],
                    t.y() + candidateDir[1]));
            if (next == null || !eligible(geo, archetype, next)) {
                next = geo.coordIndex.get(coordKey(t.part(), t.face(), t.x() + dir[0], t.y() + dir[1]));
                if (next == null || !eligible(geo, archetype, next)) break;
            } else {
                dir = candidateDir;
                lastTurn = turn;
            }
            current = next;
            path.add(current);
        }
        return path;
    }

    /** STERNAL_LADDER (B.2 #6): a central trunk column on {@code BODY/NORTH} (or a shorter, capped-
     *  rung "weaker" variant on {@code BODY/SOUTH}), with 3-5 transverse rungs and a one-hop halo. */
    private static List<RawTexel> generateSternalLadder(Geometry geo, int anchorIndex, long seed, float scale,
                                                         PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        boolean weaker = anchor.face() == Face.SOUTH;
        SplitMix64 rnd = new SplitMix64(seed);
        int trunkX = rnd.nextInt(2) == 0 ? 3 : 4;
        int yStart = weaker ? 0 : 1;
        int yEnd = weaker ? 5 : 10;
        List<Integer> trunk = new ArrayList<>();
        for (int y = yStart; y <= yEnd; y++) {
            Integer idx = geo.coordIndex.get(coordKey(Part.BODY, anchor.face(), trunkX, y));
            if (idx != null && eligible(geo, archetype, idx)) trunk.add(idx);
        }
        int centreY = (yStart + yEnd) / 2;
        trunk.sort(Comparator.comparingInt(idx -> Math.abs(geo.mask.texel(idx).y() - centreY)));

        int rungCount = weaker ? Math.min(3, 3 + rnd.nextInt(1)) : 3 + rnd.nextInt(3);
        List<Integer> rungs = new ArrayList<>();
        Set<Integer> core = new java.util.HashSet<>(trunk);
        for (int k = 0; k < rungCount; k++) {
            int j = rnd.nextInt(3) - 1;
            int y = yStart + 2 + 2 * k + j;
            if (y < yStart || y > yEnd) continue;
            int extent = 2 + rnd.nextInt(2);
            boolean omitLeft = rnd.nextInt(4) == 0;
            boolean omitRight = !omitLeft && rnd.nextInt(4) == 0;
            for (int side = -1; side <= 1; side += 2) {
                if (side < 0 && omitLeft) continue;
                if (side > 0 && omitRight) continue;
                for (int step = 1; step <= extent; step++) {
                    Integer idx = geo.coordIndex.get(coordKey(Part.BODY, anchor.face(), trunkX + side * step, y));
                    if (idx != null && eligible(geo, archetype, idx) && !core.contains(idx)) {
                        rungs.add(idx);
                        core.add(idx);
                    }
                }
            }
        }

        List<Integer> halo = new ArrayList<>();
        for (int idx : core) {
            for (int neighbor : geo.anatomy.neighbors(idx)) {
                if (!core.contains(neighbor) && eligible(geo, archetype, neighbor) && !halo.contains(neighbor)) {
                    halo.add(neighbor);
                }
            }
        }
        halo.sort(Comparator.naturalOrder());

        List<RawTexel> result = new ArrayList<>();
        for (int idx : trunk) result.add(new RawTexel(idx, Kind.TRUNK, 1.0F));
        for (int idx : rungs) result.add(new RawTexel(idx, Kind.RUNG, 0.6F));
        for (int idx : halo) result.add(new RawTexel(idx, Kind.LADDER_HALO, 0.0F));
        return result;
    }

    /** CREASE_BAND (B.2 #4): a band wrapped across 2-4 cyclic side faces at a flexion-crease row, with
     *  C.2's rare independently-warped, ≤70%-area bilateral mirror on the anatomically opposite limb. */
    private static List<RawTexel> generateCreaseBand(Geometry geo, int anchorIndex, long seed, float scale,
                                                      PatchArchetype archetype) {
        List<RawTexel> first = creaseBandHalf(geo, anchorIndex, seed, scale, archetype);
        if (Long.remainderUnsigned(SplitMix64.mix(seed), 8) != 0) return first;

        Part anchorPart = geo.mask.texel(anchorIndex).part();
        Part mirrorPart = mirrorPart(anchorPart);
        if (mirrorPart == null) return first;
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Integer mirrorAnchor = geo.coordIndex.get(coordKey(mirrorPart, anchor.face(), anchor.x(), anchor.y()));
        if (mirrorAnchor == null) return first;
        List<RawTexel> second = creaseBandHalf(geo, mirrorAnchor, seed ^ SALT_MIRROR, scale, archetype);
        int cap = (int) Math.floor(0.70 * first.size());
        List<RawTexel> combined = new ArrayList<>(first);
        for (int i = 0; i < Math.min(cap, second.size()); i++) combined.add(second.get(i));
        return combined;
    }

    private static List<RawTexel> creaseBandHalf(Geometry geo, int anchorIndex, long seed, float scale,
                                                 PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        SplitMix64 rnd = new SplitMix64(seed);
        int faceCount = 2 + rnd.nextInt(3);
        Face[] cycle = {Face.WEST, Face.NORTH, Face.EAST, Face.SOUTH};
        int startCycleIndex = indexOfFace(cycle, anchor.face());
        int extraRows = rnd.nextInt(2);

        List<RawTexel> centre = new ArrayList<>();
        List<RawTexel> adjacent = new ArrayList<>();
        int rank = 0;
        for (int f = 0; f < faceCount; f++) {
            Face face = cycle[(startCycleIndex + f) % cycle.length];
            for (int x = 0; x < 8; x++) {
                Integer probe = geo.coordIndex.get(coordKey(part, face, x, anchor.y()));
                if (probe == null) continue;
                int warp = Math.round(0.8F * noise(seed, f * 8 + x, 0));
                int y = anchor.y() + warp;
                Integer centreIdx = geo.coordIndex.get(coordKey(part, face, x, y));
                if (centreIdx == null || !eligible(geo, archetype, centreIdx)) continue;
                centre.add(new RawTexel(centreIdx, Kind.RIBBON_CENTRE, 1.0F));
                if (extraRows > 0) {
                    Integer adjIdx = geo.coordIndex.get(coordKey(part, face, x, y + 1));
                    if (adjIdx != null && eligible(geo, archetype, adjIdx)) {
                        adjacent.add(new RawTexel(adjIdx, Kind.RIBBON_ADJACENT, 0.4F));
                    }
                }
                rank++;
            }
        }
        List<RawTexel> result = new ArrayList<>(centre);
        result.addAll(adjacent);
        return result;
    }

    private static Part mirrorPart(Part part) {
        return switch (part) {
            case RIGHT_ARM -> Part.LEFT_ARM;
            case LEFT_ARM -> Part.RIGHT_ARM;
            case RIGHT_LEG -> Part.LEFT_LEG;
            case LEFT_LEG -> Part.RIGHT_LEG;
            default -> null;
        };
    }

    private static int indexOfFace(Face[] cycle, Face face) {
        for (int i = 0; i < cycle.length; i++) if (cycle[i] == face) return i;
        return 0;
    }

    /** DISTAL_SLOUGH (B.2 #5): the {@code DOWN} cap plus the final 2-4 rows of the four side faces of
     *  one limb end. Never bilateral — one instance targets exactly the limb end it anchored on. */
    private static List<RawTexel> generateDistalSlough(Geometry geo, int anchorIndex, long seed, float scale,
                                                        PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        SplitMix64 rnd = new SplitMix64(seed);
        int k = rnd.nextInt(3);
        int rowSpan = 2 + k;

        List<Integer> downFace = new ArrayList<>();
        for (int i = 0; i < geo.surfaceSize; i++) {
            SurfaceTexel t = geo.mask.texel(i);
            if (t.part() == part && t.face() == Face.DOWN && eligible(geo, archetype, i)) downFace.add(i);
        }
        downFace.sort(Comparator.comparingInt((Integer idx) -> {
            SurfaceTexel t = geo.mask.texel(idx);
            return Math.abs(t.x() * 2 - 3) + Math.abs(t.y() * 2 - 3);
        }).thenComparingInt(idx -> idx));

        List<Integer> distalRows = new ArrayList<>();
        List<Integer> proximalRow = new ArrayList<>();
        Face[] sides = {Face.WEST, Face.NORTH, Face.EAST, Face.SOUTH};
        for (Face face : sides) {
            // Determine this part/face's own height by locating its maximum y present.
            int height = 0;
            for (int y = 0; y < 16; y++) {
                if (geo.coordIndex.containsKey(coordKey(part, face, 0, y))) height = y + 1;
            }
            for (int x = 0; x < 8; x++) {
                int warp = Math.round(noise(seed, x, 0));
                int boundary = height - rowSpan + warp;
                for (int y = Math.max(0, boundary); y < height; y++) {
                    Integer idx = geo.coordIndex.get(coordKey(part, face, x, y));
                    if (idx == null || !eligible(geo, archetype, idx)) continue;
                    if (y == height - 1) proximalRow.add(idx);
                    else distalRows.add(idx);
                }
            }
        }
        distalRows.sort(Comparator.comparingInt((Integer idx) -> -geo.mask.texel(idx).y()).thenComparingInt(i -> i));
        proximalRow.sort(Comparator.naturalOrder());

        List<RawTexel> result = new ArrayList<>();
        int deepCapCount = Math.min(2, downFace.size());
        for (int i = 0; i < deepCapCount; i++) result.add(new RawTexel(downFace.get(i), Kind.DEEP_CAP, 1.0F));
        for (int i = deepCapCount; i < downFace.size(); i++) result.add(new RawTexel(downFace.get(i), Kind.DISTAL_FIELD, 0.6F));
        for (int idx : distalRows) result.add(new RawTexel(idx, Kind.DISTAL_FIELD, 0.5F));
        for (int idx : proximalRow) result.add(new RawTexel(idx, Kind.PROXIMAL_RIM, 0.0F));
        return result;
    }

    /** TENDON_LACE (B.2 #8): 2-4 parallel fibres along the limb's long axis with sparse cross-links. */
    private static List<RawTexel> generateTendonLace(Geometry geo, int anchorIndex, long seed, float scale,
                                                      boolean reversed, PatchArchetype archetype) {
        SurfaceTexel anchor = geo.mask.texel(anchorIndex);
        Part part = anchor.part();
        Face face = anchor.face();
        SplitMix64 rnd = new SplitMix64(seed);
        int lineCount = 2 + rnd.nextInt(3);
        int rows = Math.max(3, Math.round((5 + rnd.nextInt(6)) * scale));
        int spacing = Math.max(1, 4 / Math.max(1, lineCount));
        int x0 = anchor.x() - (lineCount - 1) * spacing / 2;
        int yDir = reversed ? -1 : 1;

        List<RawTexel> fibers = new ArrayList<>();
        List<RawTexel> shades = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            int y = anchor.y() + row * yDir;
            for (int j = 0; j < lineCount; j++) {
                int warp = Math.round(0.5F * noise(seed, 0, y));
                int x = x0 + j * spacing + warp;
                Integer idx = geo.coordIndex.get(coordKey(part, face, x, y));
                if (idx == null || !eligible(geo, archetype, idx)) continue;
                fibers.add(new RawTexel(idx, Kind.FIBER, 0.6F));
                boolean crossLinkRow = row % 3 == 0;
                boolean hashBit = (SplitMix64.mix(seed ^ ((long) row << 8) ^ j) & 1L) == 0L;
                if (crossLinkRow && hashBit && j + 1 < lineCount) {
                    Integer shadeIdx = geo.coordIndex.get(coordKey(part, face, x + spacing, y));
                    if (shadeIdx != null && eligible(geo, archetype, shadeIdx)) {
                        shades.add(new RawTexel(shadeIdx, Kind.FIBER_SHADE, 0.35F));
                    }
                }
            }
        }
        List<RawTexel> result = new ArrayList<>(fibers);
        result.addAll(shades);
        return result;
    }

    // ── Geometry / shared helpers ─────────────────────────────────────────────────────────────────

    private static boolean eligible(Geometry geo, PatchArchetype archetype, int index) {
        return texelWeight(geo, archetype, geo.mask.texel(index)) > 0;
    }

    private static boolean containsIndex(List<Integer> list, int value) {
        for (int v : list) if (v == value) return true;
        return false;
    }

    private static int[] longAxis(Part part, boolean reversed) {
        boolean vertical = part == Part.RIGHT_ARM || part == Part.LEFT_ARM
                || part == Part.RIGHT_LEG || part == Part.LEFT_LEG;
        int[] axis = vertical ? new int[]{0, 1} : new int[]{1, 0};
        if (reversed) return new int[]{-axis[0], -axis[1]};
        return axis;
    }

    private static int[] rotate90(int[] dir, int turn) {
        // turn == 1 -> +90 degrees, turn == -1 -> -90 degrees, in (dx,dy) grid space.
        return turn > 0 ? new int[]{-dir[1], dir[0]} : new int[]{dir[1], -dir[0]};
    }

    private static int[] rotationVector(int rotationSteps) {
        return switch (((rotationSteps % 4) + 4) % 4) {
            case 0 -> new int[]{1, 0};
            case 1 -> new int[]{0, 1};
            case 2 -> new int[]{-1, 0};
            default -> new int[]{0, -1};
        };
    }

    private static long coordKey(Part part, Face face, int x, int y) {
        return ((long) part.ordinal() << 40) | ((long) face.ordinal() << 32) | ((long) x << 16) | (y & 0xFFFFL);
    }

    /** B.1's bounded value-noise field, lattice cell size g=2 texels: {@code h(ix,iy) =
     *  top24(mix(shapeSeed xor ix*C1 xor iy*C2))/16777215}, bilinear-interpolated with smoothstep,
     *  {@code n = 2V-1}. Used only to roughen an authored boundary by at most ~1 texel. */
    private static float noise(long shapeSeed, int x, int y) {
        int g = 2;
        int ix0 = Math.floorDiv(x, g);
        int iy0 = Math.floorDiv(y, g);
        float fx = smoothstep(frac(x / (float) g));
        float fy = smoothstep(frac(y / (float) g));
        float h00 = latticeHash(shapeSeed, ix0, iy0);
        float h10 = latticeHash(shapeSeed, ix0 + 1, iy0);
        float h01 = latticeHash(shapeSeed, ix0, iy0 + 1);
        float h11 = latticeHash(shapeSeed, ix0 + 1, iy0 + 1);
        float v = bilerp(h00, h10, h01, h11, fx, fy);
        return 2.0F * v - 1.0F;
    }

    private static float latticeHash(long shapeSeed, int ix, int iy) {
        long mixed = SplitMix64.mix(shapeSeed ^ ((long) ix * 0x9E3779B97F4A7C15L) ^ ((long) iy * 0xD1B54A32D192ED03L));
        long top24 = (mixed >>> 40) & 0xFFFFFFL;
        return top24 / 16777215.0F;
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    private static float smoothstep(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private static float bilerp(float h00, float h10, float h01, float h11, float fx, float fy) {
        float top = h00 + (h10 - h00) * fx;
        float bottom = h01 + (h11 - h01) * fx;
        return top + (bottom - top) * fy;
    }

    private static long q16(double v) {
        return Math.round(v * 65536.0);
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    private static int dark(int rgb, float k) {
        int r = clamp255(Math.round(((rgb >> 16) & 0xFF) * k));
        int g = clamp255(Math.round(((rgb >> 8) & 0xFF) * k));
        int b = clamp255(Math.round((rgb & 0xFF) * k));
        return (r << 16) | (g << 8) | b;
    }

    private static int desatLift(int rgb, float satMult, float valueLift) {
        float[] hsb = new float[3];
        Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
        float s = clamp01(hsb[1] * satMult);
        float v = clamp01(hsb[2] + (1.0F - hsb[2]) * valueLift);
        return Color.HSBtoRGB(hsb[0], s, v) & 0xFFFFFF;
    }

    private static int adjustValue(int rgb, float valueMult) {
        float[] hsb = new float[3];
        Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, hsb);
        float v = clamp01(hsb[2] * valueMult);
        return Color.HSBtoRGB(hsb[0], hsb[1], v) & 0xFFFFFF;
    }

    private static int lerpRgb(int from, int to, float t) {
        float clamped = clamp01(t);
        int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, clamped));
        int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, clamped));
        int b = Math.round(lerp(from & 0xFF, to & 0xFF, clamped));
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int packRgb(int abgrPixel) {
        return (FastColor.ABGR32.red(abgrPixel) << 16) | (FastColor.ABGR32.green(abgrPixel) << 8)
                | FastColor.ABGR32.blue(abgrPixel);
    }

    private static void blendInto(int[] output, int index, int targetRgb, float weight) {
        if (weight <= 0.0F) return;
        int pixel = output[index];
        int alpha = FastColor.ABGR32.alpha(pixel);
        if (alpha == 0) return;
        int currentRgb = packRgb(pixel);
        int blended = lerpRgb(currentRgb, targetRgb, weight);
        output[index] = FastColor.ABGR32.color(alpha, blended & 0xFF, (blended >> 8) & 0xFF, (blended >> 16) & 0xFF);
    }

    // ── Structural geometry cache (mirrors CorpseFleshMask/CorpseAnatomy's own two-entry pattern) ────

    private static final class Geometry {
        private static final Map<Boolean, Geometry> CACHE = new HashMap<>(4);

        final CorpseFleshMask mask;
        final CorpseAnatomy anatomy;
        final int surfaceSize;
        final Map<Long, Integer> coordIndex;
        final Map<Region, int[]> regionIndices;

        private Geometry(CorpseFleshMask mask, CorpseAnatomy anatomy) {
            this.mask = mask;
            this.anatomy = anatomy;
            this.surfaceSize = mask.surfaceSize();
            this.coordIndex = new HashMap<>(surfaceSize * 2);
            Map<Region, List<Integer>> byRegion = new EnumMap<>(Region.class);
            for (int i = 0; i < surfaceSize; i++) {
                SurfaceTexel texel = mask.texel(i);
                coordIndex.put(coordKey(texel.part(), texel.face(), texel.x(), texel.y()), i);
                byRegion.computeIfAbsent(anatomy.region(texel), r -> new ArrayList<>()).add(i);
            }
            this.regionIndices = new EnumMap<>(Region.class);
            for (Map.Entry<Region, List<Integer>> entry : byRegion.entrySet()) {
                int[] indices = entry.getValue().stream().mapToInt(Integer::intValue).sorted().toArray();
                regionIndices.put(entry.getKey(), indices);
            }
        }

        static synchronized Geometry get(CorpseFleshMask mask, CorpseAnatomy anatomy) {
            boolean slim = mask.surfaceSize() == 1568;
            Geometry geo = CACHE.get(slim);
            if (geo == null) {
                geo = new Geometry(mask, anatomy);
                CACHE.put(slim, geo);
            }
            return geo;
        }
    }
}
