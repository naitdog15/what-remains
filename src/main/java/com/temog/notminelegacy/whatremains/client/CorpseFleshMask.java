package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.temog.notminelegacy.whatremains.entity.DecayStage;

/**
 * Maps logical cube faces into player UV — the vanilla-skin-shaped surface every corpse render
 * shares. Purely structural: two immutable instances exist for the lifetime of the client (wide and
 * slim), built once from the exact vanilla {@code addPart}/{@code addFace} rectangles and never
 * touched again.
 * <p>
 * Everything <em>seeded</em> that used to live here — the owner-UUID-keyed hole rank, blotch seed,
 * and full-part reveal archetype/activation — moved to {@link CorpseAppearanceLayout}, keyed by
 * {@code appearanceSeed} instead (SOL-PATCH-STITCH-DESIGN.md A.3 / F.1 step 4). This split is why
 * this class's own cache now holds exactly two entries (wide/slim) rather than one per corpse owner.
 * <p>
 * {@link Part}, {@link Face}, {@link Category}, and {@link RevealArchetype} are package-private (not
 * {@code private}) so {@link CorpseAnatomy}, {@link CorpseAppearanceLayout}, and the companion
 * death-cause injury design can share the exact same vocabulary instead of duplicating it
 * (SOL-PATCH-STITCH-DESIGN.md E.4 / RECONCILIATION.md R1). {@link SurfaceTexel} stays package-private
 * too — its accessor methods (not direct field access) are how other classes in this package must
 * read it, since Java's "private members are visible to the enclosing top-level class" rule only
 * covers code physically inside this file.
 */
final class CorpseFleshMask {

    private static final Map<Boolean, CorpseFleshMask> CACHE = new HashMap<>(4);

    private final List<SurfaceTexel> texels;

    private CorpseFleshMask(boolean slim) {
        this.texels = buildSurface(slim);
        validate(slim);
    }

    static synchronized CorpseFleshMask get(boolean slim) {
        CorpseFleshMask mask = CACHE.get(slim);
        if (mask == null) {
            mask = new CorpseFleshMask(slim);
            CACHE.put(slim, mask);
        }
        return mask;
    }

    static synchronized void clearCache() {
        CACHE.clear();
    }

    int surfaceSize() {
        return texels.size();
    }

    SurfaceTexel texel(int index) {
        return texels.get(index);
    }

    private void validate(boolean slim) {
        int expected = slim ? 1568 : 1632;
        if (texels.size() != expected) {
            throw new IllegalStateException("Invalid corpse flesh UV layout");
        }
        boolean[] base = new boolean[4096];
        boolean[] outer = new boolean[4096];
        for (SurfaceTexel texel : texels) {
            if (texel.baseIndex() < 0 || texel.baseIndex() >= 4096
                    || texel.outerIndex() < 0 || texel.outerIndex() >= 4096
                    || base[texel.baseIndex()] || outer[texel.outerIndex()]) {
                throw new IllegalStateException("Overlapping corpse flesh UV descriptor");
            }
            base[texel.baseIndex()] = true;
            outer[texel.outerIndex()] = true;
        }
    }

    private static List<SurfaceTexel> buildSurface(boolean slim) {
        List<SurfaceTexel> result = new ArrayList<>();
        addPart(result, Part.HEAD, 8, 8, 8, 0, 0, 32, 0);
        addPart(result, Part.BODY, 8, 12, 4, 16, 16, 16, 32);
        int armWidth = slim ? 3 : 4;
        addPart(result, Part.RIGHT_ARM, armWidth, 12, 4, 40, 16, 40, 32);
        addPart(result, Part.LEFT_ARM, armWidth, 12, 4, 32, 48, 48, 48);
        addPart(result, Part.RIGHT_LEG, 4, 12, 4, 0, 16, 0, 32);
        addPart(result, Part.LEFT_LEG, 4, 12, 4, 16, 48, 0, 48);
        return List.copyOf(result);
    }

    private static void addPart(List<SurfaceTexel> output, Part part, int width, int height,
                                int depth, int baseU, int baseV, int outerU, int outerV) {
        addFace(output, part, Face.DOWN, width, depth, baseU + depth, baseV,
                outerU + depth, outerV);
        addFace(output, part, Face.UP, width, depth, baseU + depth + width, baseV,
                outerU + depth + width, outerV);
        addFace(output, part, Face.WEST, depth, height, baseU, baseV + depth,
                outerU, outerV + depth);
        addFace(output, part, Face.NORTH, width, height, baseU + depth, baseV + depth,
                outerU + depth, outerV + depth);
        addFace(output, part, Face.EAST, depth, height, baseU + depth + width, baseV + depth,
                outerU + depth + width, outerV + depth);
        addFace(output, part, Face.SOUTH, width, height, baseU + depth + width + depth,
                baseV + depth, outerU + depth + width + depth, outerV + depth);
    }

    private static void addFace(List<SurfaceTexel> output, Part part, Face face, int width,
                                int height, int baseU, int baseV, int outerU, int outerV) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                output.add(new SurfaceTexel(part, face, x, y,
                        baseU + x + (baseV + y) * 64,
                        outerU + x + (outerV + y) * 64));
            }
        }
    }

    /** Structural (seed-independent) classification of one texel against the legacy heuristic
     *  {@link Category} buckets — package-private so {@link CorpseAppearanceLayout}'s seeded hole/
     *  blotch construction can reuse it without duplicating the geometry. */
    static boolean matches(Category category, SurfaceTexel texel) {
        boolean side = EnumSet.of(Face.WEST, Face.NORTH, Face.EAST, Face.SOUTH).contains(texel.face());
        boolean arm = texel.part() == Part.RIGHT_ARM || texel.part() == Part.LEFT_ARM;
        boolean leg = texel.part() == Part.RIGHT_LEG || texel.part() == Part.LEFT_LEG;
        return switch (category) {
            case LIMB_END -> (arm || leg) && ((side && texel.y() >= 9) || texel.face() == Face.DOWN);
            case JOINT -> (arm && side && texel.y() >= 5 && texel.y() <= 8)
                    || (leg && side && texel.y() >= 4 && texel.y() <= 7);
            case STERNUM -> texel.part() == Part.BODY
                    && ((texel.face() == Face.NORTH && texel.x() >= 2 && texel.x() <= 5
                    && texel.y() >= 1 && texel.y() <= 10)
                    || ((texel.face() == Face.WEST || texel.face() == Face.EAST)
                    && texel.y() >= 2 && texel.y() <= 9));
            case JAW -> texel.part() == Part.HEAD
                    && ((texel.face() == Face.NORTH && texel.x() >= 1 && texel.x() <= 6 && texel.y() >= 4)
                    || ((texel.face() == Face.WEST || texel.face() == Face.EAST) && texel.y() >= 4));
            case UNIFORM -> true;
        };
    }

    /** Deterministic per-texel hash used by every seeded rank builder in {@link
     *  CorpseAppearanceLayout} — package-private so that class can jitter/tie-break candidates
     *  without each caller re-deriving the same bit-mixing scheme. Purely a function of an
     *  externally supplied {@code seed} and the texel's own structural identity; holds no state of
     *  its own. */
    static long texelHash(long seed, SurfaceTexel texel) {
        long value = seed ^ ((long) texel.part().ordinal() << 48)
                ^ ((long) texel.face().ordinal() << 40) ^ ((long) texel.x() << 20) ^ texel.y();
        return SplitMix64.mix(value);
    }

    record SurfaceTexel(Part part, Face face, int x, int y, int baseIndex, int outerIndex) {}

    enum Part { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    enum RevealArchetype {
        RIGHT_ARM(Part.RIGHT_ARM),
        LEFT_ARM(Part.LEFT_ARM),
        RIGHT_LEG(Part.RIGHT_LEG),
        LEFT_LEG(Part.LEFT_LEG),
        TORSO(Part.BODY),
        BOTH_ARMS(Part.RIGHT_ARM, Part.LEFT_ARM),
        BOTH_LEGS(Part.RIGHT_LEG, Part.LEFT_LEG),
        HEAD(Part.HEAD);

        private final EnumSet<Part> parts;

        RevealArchetype(Part first, Part... remaining) {
            this.parts = EnumSet.of(first, remaining);
        }

        boolean contains(Part part) {
            return parts.contains(part);
        }

        boolean isEligible(DecayStage stage, float stageProgress) {
            // ADVANCED_DECAY is the OLD SKELETAL stage (same fraction/position, renamed — see
            // DecayStage's header comment): this branch used to read "stage == SKELETAL" and relied
            // on that name meaning what ADVANCED_DECAY means now. Left unfixed, that old name now
            // resolves to the NEW terminal bare-exoskeleton stage instead, and ADVANCED_DECAY would
            // silently fall through every other branch to "not eligible" — a real full-part-reveal
            // regression at exactly the stage a player has already seen reveals fire at. NEW SKELETAL
            // is kept eligible too (true is its own terminal/topmost value already, and it's a strict
            // superset of decay), defensively, even though CorpseFleshBaker#resolve now never bakes
            // flesh at that stage at all, so this branch is never actually reached for it in practice.
            if (stage == DecayStage.COLLAPSE || stage == DecayStage.ADVANCED_DECAY
                    || stage == DecayStage.SKELETAL) return true;
            if (stage == DecayStage.LATE_DECAY) return this != HEAD;
            if (stage != DecayStage.MID_DECAY || stageProgress < 0.67F) return false;
            return this == RIGHT_ARM || this == LEFT_ARM
                    || this == RIGHT_LEG || this == LEFT_LEG;
        }
    }

    enum Face { DOWN, UP, WEST, NORTH, EAST, SOUTH }
    enum Category { LIMB_END, JOINT, STERNUM, JAW, UNIFORM }

    /** Local stream so unrelated vanilla RNG use and future blotch changes cannot move holes. */
    static final class SplitMix64 {
        private long state;

        SplitMix64(long seed) {
            this.state = seed;
        }

        long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            return mix(state);
        }

        int nextInt(int bound) {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }

        int nextInt(int origin, int bound) {
            return origin + nextInt(bound - origin);
        }

        static long mix(long value) {
            value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
            value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
            return value ^ value >>> 31;
        }
    }
}
