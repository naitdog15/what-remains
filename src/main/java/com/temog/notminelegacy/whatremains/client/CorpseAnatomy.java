package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Category;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Face;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.Part;
import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SurfaceTexel;

/**
 * The single, authoritative anatomical vocabulary for a corpse's flesh surface — SOL-PATCH-STITCH-
 * DESIGN.md section E as the base definition, plus every region name from SOL-DEATHCAUSE-INJURY-
 * DESIGN.md section A1 exposed as {@link Selector}s on this same type (RECONCILIATION.md R1). There
 * is exactly one region vocabulary in this codebase; nothing here is duplicated into a second
 * {@code CorpseInjuryRegion}-style enum.
 * <p>
 * "This is a visual targeting vocabulary, not a medical damage model. It describes only geometry
 * that actually exists" (E.1) — {@link Region#HIDDEN} is the one deliberate addition beyond E.3's
 * literal table: three real {@link SurfaceTexel}s (HEAD/DOWN, BODY/UP, BODY/DOWN) exist in the
 * surface but sit permanently inside neighbouring geometry (the neck cavity, the head socket, and
 * between the legs respectively) and are simply never named by E.3's own region table. Returning an
 * honest "no region" bucket for them is truer to E.1's own stated principle than either inventing a
 * region E.3 never described or throwing on a texel that is legitimately part of the surface.
 * <p>
 * Two singleton instances exist (wide/slim), mirroring {@link CorpseFleshMask}'s own two-entry
 * cache — geometry never changes at runtime, so unlike {@link CorpseAppearanceLayout} this cache is
 * never invalidated by a resource reload or logout.
 * <p>
 * <b>Honesty note on cap adjacency</b> — {@link #neighbors(int)} wires each part's {@code UP}/
 * {@code DOWN} cap face to the top/bottom row of its four side faces using the cuboid dimensions in
 * E.2, per C.1's explicit instruction to derive this from geometry rather than from where the flat
 * PNG atlas happens to place pixels (the atlas layout is, in fact, the OPPOSITE of true 3D adjacency
 * here — see {@link #wireCap} — which is exactly the trap C.1 warns against). The edge-to-edge index
 * correspondence chosen (an unmirrored, direct mapping) is internally consistent and reciprocal by
 * construction, and {@link #validate} checks that reciprocity plus the exact expected count of wired
 * border texels per cap. What {@code validate()} cannot check is whether that correspondence points
 * at the visually correct pixels — SOL-PATCH-STITCH-DESIGN.md's own failure-mode table (G, "UV seam
 * discontinuity") calls this out as needing a screenshot pass, not just range checks, and none has
 * happened yet. Nothing in this pass draws through a seam, so no visible corpse is at risk from this
 * uncertainty today; it matters only once a later patch/injury pass starts stitching across it.
 */
final class CorpseAnatomy {

    private static final EnumSet<Part> UP_CAP_PARTS = EnumSet.of(Part.HEAD, Part.RIGHT_ARM,
            Part.LEFT_ARM, Part.RIGHT_LEG, Part.LEFT_LEG);
    private static final EnumSet<Part> DOWN_CAP_PARTS = EnumSet.of(Part.RIGHT_ARM, Part.LEFT_ARM,
            Part.RIGHT_LEG, Part.LEFT_LEG);

    private static final Map<Boolean, CorpseAnatomy> CACHE = new HashMap<>(4);

    private final Dimensions[] dimensionsByPart;
    private final Map<Long, Integer> indexByCoordinate;
    private final int[][] neighborTable;

    private CorpseAnatomy(CorpseFleshMask mask) {
        this.dimensionsByPart = computeDimensions(mask);
        this.indexByCoordinate = buildCoordinateIndex(mask);
        this.neighborTable = buildNeighborTable(mask);
        validate(mask);
    }

    static synchronized CorpseAnatomy get(boolean slim) {
        CorpseAnatomy anatomy = CACHE.get(slim);
        if (anatomy == null) {
            anatomy = new CorpseAnatomy(CorpseFleshMask.get(slim));
            CACHE.put(slim, anatomy);
        }
        return anatomy;
    }

    /** E.1's primary anatomical region for one texel — see this class's own javadoc for the one
     *  addition ({@link Region#HIDDEN}) beyond E.3's literal table. */
    Region region(SurfaceTexel texel) {
        return switch (texel.part()) {
            case HEAD -> switch (texel.face()) {
                case UP -> Region.HEAD_CROWN;
                case NORTH -> texel.y() <= 3 ? Region.FACE_UPPER : Region.FACE_LOWER;
                case WEST, EAST -> Region.FACE_SIDE;
                case SOUTH -> Region.HEAD_BACK;
                case DOWN -> Region.HIDDEN;
            };
            case BODY -> switch (texel.face()) {
                case NORTH -> texel.y() <= 5 ? Region.CHEST : Region.ABDOMEN;
                case SOUTH -> texel.y() <= 5 ? Region.UPPER_BACK : Region.LOWER_BACK;
                case WEST, EAST -> Region.TORSO_FLANK;
                case UP, DOWN -> Region.HIDDEN;
            };
            case RIGHT_ARM, LEFT_ARM -> switch (texel.face()) {
                case UP -> Region.SHOULDER;
                case DOWN -> Region.WRIST_HAND;
                case WEST, NORTH, EAST, SOUTH -> armRegion(texel.y());
            };
            case RIGHT_LEG, LEFT_LEG -> switch (texel.face()) {
                case UP -> Region.HIP;
                case DOWN -> Region.ANKLE_FOOT;
                case WEST, NORTH, EAST, SOUTH -> legRegion(texel.y());
            };
        };
    }

    private static Region armRegion(int y) {
        if (y <= 2) return Region.SHOULDER;
        if (y <= 4) return Region.UPPER_ARM;
        if (y <= 7) return Region.ELBOW;
        if (y <= 9) return Region.FOREARM;
        return Region.WRIST_HAND;
    }

    private static Region legRegion(int y) {
        if (y <= 1) return Region.HIP;
        if (y <= 3) return Region.THIGH;
        if (y <= 6) return Region.KNEE;
        if (y <= 9) return Region.SHIN;
        return Region.ANKLE_FOOT;
    }

    /**
     * E.1's landmark tags. {@code STERNUM}/{@code JAW} delegate to {@code CorpseFleshMask}'s existing
     * {@link Category} predicates — E.3 confirms their ranges are numerically identical to the new
     * regions once HEAD/BODY's own dimensions bound them, so reusing them (rather than restating the
     * same rectangles a second time) is exactly RECONCILIATION.md R1's ask. {@code JOINT}/{@code
     * LIMB_END} deliberately do NOT delegate to {@link Category#JOINT}/{@link Category#LIMB_END} —
     * E.4 documents those legacy thresholds as one row wider than the new regions on purpose ("the
     * old side-face threshold starts at y>=9, one row earlier than the new primary regions"), so
     * delegating would silently import that legacy drift into the new vocabulary.
     */
    boolean hasLandmark(SurfaceTexel texel, Landmark landmark) {
        return switch (landmark) {
            case STERNUM -> CorpseFleshMask.matches(Category.STERNUM, texel);
            case JAW -> CorpseFleshMask.matches(Category.JAW, texel);
            case JOINT -> region(texel) == Region.ELBOW || region(texel) == Region.KNEE;
            case LIMB_END -> region(texel) == Region.WRIST_HAND || region(texel) == Region.ANKLE_FOOT;
            // E.3: "POSTERIOR_LIMB is not a primary region; it is the POSTERIOR_CONTACT landmark on
            // arm/leg SOUTH texels" — and HEAD_BACK/UPPER_BACK/LOWER_BACK are tagged POSTERIOR_CONTACT
            // in full. Every one of those is exactly "this part's SOUTH face", for every part.
            case POSTERIOR_CONTACT -> texel.face() == Face.SOUTH;
        };
    }

    /** E.1: "Side — CENTRE, LEFT, or RIGHT, derived from Part." Matches {@code StuckProjectile}'s own
     *  {@code PART_*} handedness, not screen left/right after the face-down render rotation. */
    Side side(Part part) {
        return switch (part) {
            case RIGHT_ARM, RIGHT_LEG -> Side.RIGHT;
            case LEFT_ARM, LEFT_LEG -> Side.LEFT;
            case HEAD, BODY -> Side.CENTRE;
        };
    }

    /**
     * RECONCILIATION.md R1's death-cause-vocabulary selectors, expressed as unions/intersections of
     * {@link Region}/{@link Landmark} wherever the two design docs' boundaries actually coincide.
     * {@code ARM_ROOTS}/{@code LEG_ROOTS}/{@code ALL_LIMB_ROOTS} and {@code UPPER_TORSO}/{@code
     * LOWER_TORSO} do not coincide with a whole-{@link Region} union — see {@link #isLimbRoot} and
     * {@link #isUpperTorsoRow}'s own javadoc for exactly why — so those five are direct geometric
     * predicates instead, sharing the same y-range style rather than restating unrelated logic.
     */
    boolean matches(Selector selector, SurfaceTexel texel) {
        Part part = texel.part();
        return switch (selector) {
            case HEAD_ALL -> part == Part.HEAD;
            case CRANIUM -> part == Part.HEAD && !hasLandmark(texel, Landmark.JAW);
            case JAW -> hasLandmark(texel, Landmark.JAW);
            case TORSO_ALL -> part == Part.BODY;
            case STERNUM -> hasLandmark(texel, Landmark.STERNUM);
            case UPPER_TORSO -> part == Part.BODY && isUpperTorsoRow(texel);
            case LOWER_TORSO -> part == Part.BODY && !isUpperTorsoRow(texel);
            case TORSO_FLANKS -> region(texel) == Region.TORSO_FLANK;
            case RIGHT_ARM_ALL -> part == Part.RIGHT_ARM;
            case LEFT_ARM_ALL -> part == Part.LEFT_ARM;
            case RIGHT_LEG_ALL -> part == Part.RIGHT_LEG;
            case LEFT_LEG_ALL -> part == Part.LEFT_LEG;
            case ARM_ROOTS -> isArm(part) && isLimbRoot(texel);
            case LEG_ROOTS -> isLeg(part) && isLimbRoot(texel);
            case ARM_JOINTS -> isArm(part) && hasLandmark(texel, Landmark.JOINT);
            case LEG_JOINTS -> isLeg(part) && hasLandmark(texel, Landmark.JOINT);
            case ARM_ENDS -> isArm(part) && hasLandmark(texel, Landmark.LIMB_END);
            case LEG_ENDS -> isLeg(part) && hasLandmark(texel, Landmark.LIMB_END);
            case ALL_JOINTS -> hasLandmark(texel, Landmark.JOINT);
            case ALL_LIMB_ENDS -> hasLandmark(texel, Landmark.LIMB_END);
            case ALL_LIMB_ROOTS -> (isArm(part) || isLeg(part)) && isLimbRoot(texel);
        };
    }

    private static boolean isArm(Part part) {
        return part == Part.RIGHT_ARM || part == Part.LEFT_ARM;
    }

    private static boolean isLeg(Part part) {
        return part == Part.RIGHT_LEG || part == Part.LEFT_LEG;
    }

    /** SOL-DEATHCAUSE-INJURY-DESIGN.md's shared vocabulary: {@code LIMB_ROOT(part)} = "Arm or leg
     *  side faces at local y=0..3, plus that part's UP face." Deliberately one row deeper than
     *  patch-stitch's own {@code SHOULDER}/{@code HIP} regions (y=0..2 / y=0..1 respectively) — the
     *  two design docs picked different cutoffs independently, since patch-stitch's E section
     *  predates the injury design. A union of whole {@link Region} values cannot express "SHOULDER
     *  plus one extra row of UPPER_ARM", so this is implemented directly rather than forced into a
     *  composition that would either be wrong by one row or require a non-region-shaped union. */
    private boolean isLimbRoot(SurfaceTexel texel) {
        if (texel.face() == Face.UP) return true;
        if (!isSideFace(texel.face())) return false;
        return texel.y() <= 3;
    }

    /** Neither design doc's own vocabulary names a whole-BODY top/bottom half directly by this name
     *  — RECONCILIATION.md R1 introduces {@code UPPER_TORSO}/{@code LOWER_TORSO} as bridging
     *  selectors. For {@code NORTH}/{@code SOUTH} this exactly matches {@link Region#CHEST}/{@link
     *  Region#UPPER_BACK} vs {@link Region#ABDOMEN}/{@link Region#LOWER_BACK}'s own y=0..5/6..11
     *  split; {@link Region#TORSO_FLANK} deliberately does NOT split into an upper/lower {@link
     *  Region} pair (E.3 notes the split only informally), so {@code WEST}/{@code EAST} texels are
     *  resolved with that same boundary directly rather than needing a second Region that doesn't
     *  exist. {@code UP} counts as upper (physically the torso's top), {@code DOWN} as lower. */
    private static boolean isUpperTorsoRow(SurfaceTexel texel) {
        if (texel.face() == Face.UP) return true;
        if (texel.face() == Face.DOWN) return false;
        return texel.y() <= 5;
    }

    /** C.1's reciprocal surface adjacency graph — four-neighbours within a face, the WEST→NORTH→
     *  EAST→SOUTH→WEST vertical seam cycle, and the cap wiring documented on {@link #wireCap}. Always
     *  returns a fresh copy so a caller can never mutate the cached table. */
    int[] neighbors(int surfaceIndex) {
        return neighborTable[surfaceIndex].clone();
    }

    /**
     * SOL-DEATHCAUSE-INJURY-DESIGN.md's shared-vocabulary {@code IMPACT(part,px,py,pz,r)} selector,
     * renamed ANCHOR_DISC here (RECONCILIATION.md R1) so this projection math lives in the injury-
     * agnostic anatomy layer both patch-stitch and the later injury design can share. Picks the
     * surface face whose normalized local coordinate is farthest from {@code 0.5} (ties resolve X,
     * then Z, then Y); the winning axis's sign selects WEST/EAST, NORTH/SOUTH, or UP/DOWN — {@code
     * px}/{@code py}/{@code pz} < 0.5 map to WEST/DOWN/NORTH respectively, matching vanilla's own
     * -X/-Y/-Z direction convention. Projects the anchor onto that face's local UV, then returns every
     * texel at face-local Manhattan distance {@code <= radius}, in stable {@code (y, x)} order (C.1 —
     * all candidates share one face, so the full {@code (Part.ordinal, Face.ordinal, y, x)} order
     * reduces to just {@code (y, x)}).
     */
    int[] anchorDisc(Part part, float px, float py, float pz, int radius) {
        Dimensions dims = dimensionsByPart[part.ordinal()];
        float dx = Math.abs(px - 0.5F);
        float dy = Math.abs(py - 0.5F);
        float dz = Math.abs(pz - 0.5F);

        Face face;
        int localWidth;
        int localHeight;
        int localX;
        int localY;
        if (dx >= dz && dx >= dy) {
            face = px < 0.5F ? Face.WEST : Face.EAST;
            localWidth = dims.depth();
            localHeight = dims.height();
            localX = clampIndex(Math.round(pz * dims.depth()), dims.depth());
            localY = clampIndex(Math.round(py * dims.height()), dims.height());
        } else if (dz >= dy) {
            face = pz < 0.5F ? Face.NORTH : Face.SOUTH;
            localWidth = dims.width();
            localHeight = dims.height();
            localX = clampIndex(Math.round(px * dims.width()), dims.width());
            localY = clampIndex(Math.round(py * dims.height()), dims.height());
        } else {
            face = py < 0.5F ? Face.DOWN : Face.UP;
            localWidth = dims.width();
            localHeight = dims.depth();
            localX = clampIndex(Math.round(px * dims.width()), dims.width());
            localY = clampIndex(Math.round(pz * dims.depth()), dims.depth());
        }

        List<int[]> coordinates = new ArrayList<>();
        for (int y = 0; y < localHeight; y++) {
            for (int x = 0; x < localWidth; x++) {
                if (Math.abs(x - localX) + Math.abs(y - localY) <= radius) {
                    coordinates.add(new int[]{y, x});
                }
            }
        }
        coordinates.sort(Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[1]));

        int[] result = new int[coordinates.size()];
        int cursor = 0;
        for (int[] coordinate : coordinates) {
            Integer index = indexByCoordinate.get(coordinateKey(part, face, coordinate[1], coordinate[0]));
            if (index != null) result[cursor++] = index;
        }
        return cursor == result.length ? result : java.util.Arrays.copyOf(result, cursor);
    }

    private static int clampIndex(int value, int boundExclusive) {
        return Math.max(0, Math.min(boundExclusive - 1, value));
    }

    // ── Construction ──────────────────────────────────────────────────────────────────────────────

    private static Dimensions[] computeDimensions(CorpseFleshMask mask) {
        Dimensions[] result = new Dimensions[Part.values().length];
        for (Part part : Part.values()) {
            int maxDownX = -1;
            int maxDownY = -1;
            int maxWestY = -1;
            for (int i = 0; i < mask.surfaceSize(); i++) {
                SurfaceTexel texel = mask.texel(i);
                if (texel.part() != part) continue;
                if (texel.face() == Face.DOWN) {
                    maxDownX = Math.max(maxDownX, texel.x());
                    maxDownY = Math.max(maxDownY, texel.y());
                } else if (texel.face() == Face.WEST) {
                    maxWestY = Math.max(maxWestY, texel.y());
                }
            }
            // DOWN/UP use (width, depth) as their own (localWidth, localHeight) per addFace, and
            // WEST/EAST use (depth, height) — so DOWN's own extent yields (width, depth) directly,
            // and WEST's y-extent yields height, without needing a stored slim/wide flag anywhere.
            result[part.ordinal()] = new Dimensions(maxDownX + 1, maxWestY + 1, maxDownY + 1);
        }
        return result;
    }

    private static Map<Long, Integer> buildCoordinateIndex(CorpseFleshMask mask) {
        Map<Long, Integer> byCoordinate = new HashMap<>(mask.surfaceSize() * 2);
        for (int i = 0; i < mask.surfaceSize(); i++) {
            SurfaceTexel texel = mask.texel(i);
            byCoordinate.put(coordinateKey(texel.part(), texel.face(), texel.x(), texel.y()), i);
        }
        return byCoordinate;
    }

    private static long coordinateKey(Part part, Face face, int x, int y) {
        return ((long) part.ordinal() << 40) | ((long) face.ordinal() << 32)
                | ((long) x << 16) | (y & 0xFFFFL);
    }

    private int[][] buildNeighborTable(CorpseFleshMask mask) {
        int size = mask.surfaceSize();
        List<TreeSet<Integer>> adjacency = new ArrayList<>(size);
        for (int i = 0; i < size; i++) adjacency.add(new TreeSet<>());

        for (int i = 0; i < size; i++) {
            SurfaceTexel texel = mask.texel(i);
            Part part = texel.part();
            Face face = texel.face();
            int x = texel.x();
            int y = texel.y();

            connect(adjacency, part, face, x, y, part, face, x - 1, y);
            connect(adjacency, part, face, x, y, part, face, x + 1, y);
            connect(adjacency, part, face, x, y, part, face, x, y - 1);
            connect(adjacency, part, face, x, y, part, face, x, y + 1);

            // C.1's vertical cube-seam cycle: WEST.right<->NORTH.left, NORTH.right<->EAST.left,
            // EAST.right<->SOUTH.left, SOUTH.right<->WEST.left. Only the "next" direction is wired
            // explicitly here; the reciprocal "previous" edge is the SAME call seen from the other
            // face's own x==0 texel, added automatically since connect() is bidirectional.
            if (isSideFace(face) && x == faceLocalWidth(dimensionsByPart[part.ordinal()], face) - 1) {
                connect(adjacency, part, face, x, y, part, nextSideFace(face), 0, y);
            }
        }

        for (Part part : UP_CAP_PARTS) wireCap(adjacency, part, Face.UP);
        for (Part part : DOWN_CAP_PARTS) wireCap(adjacency, part, Face.DOWN);

        int[][] table = new int[size][];
        for (int i = 0; i < size; i++) {
            int[] neighbors = new int[adjacency.get(i).size()];
            int cursor = 0;
            for (int neighbor : adjacency.get(i)) neighbors[cursor++] = neighbor;
            table[i] = neighbors;
        }
        return table;
    }

    /** See this class's javadoc "Honesty note on cap adjacency". {@code UP} wires only for HEAD/arm/
     *  leg (never BODY — its UP cap sits inside the head model, never a real seam); {@code DOWN}
     *  wires only for arm/leg (never HEAD or BODY, same "hidden, not a real seam" reason) — C.1's
     *  exact restriction list ("HEAD_CROWN, SHOULDER, and HIP" for UP; "WRIST_HAND and ANKLE_FOOT"
     *  for DOWN). Each of the cap's four border edges is wired to the matching side face's own top
     *  (UP) or bottom (DOWN) row with a direct, unmirrored index correspondence. */
    private void wireCap(List<TreeSet<Integer>> adjacency, Part part, Face cap) {
        Dimensions dims = dimensionsByPart[part.ordinal()];
        int width = dims.width();
        int depth = dims.depth();
        int edgeY = cap == Face.UP ? 0 : dims.height() - 1;
        for (int x = 0; x < width; x++) {
            connect(adjacency, part, cap, x, 0, part, Face.NORTH, x, edgeY);
            connect(adjacency, part, cap, x, depth - 1, part, Face.SOUTH, x, edgeY);
        }
        for (int z = 0; z < depth; z++) {
            connect(adjacency, part, cap, 0, z, part, Face.WEST, z, edgeY);
            connect(adjacency, part, cap, width - 1, z, part, Face.EAST, z, edgeY);
        }
    }

    private void connect(List<TreeSet<Integer>> adjacency, Part partA, Face faceA, int xA, int yA,
                         Part partB, Face faceB, int xB, int yB) {
        Integer a = indexByCoordinate.get(coordinateKey(partA, faceA, xA, yA));
        Integer b = indexByCoordinate.get(coordinateKey(partB, faceB, xB, yB));
        if (a == null || b == null || a.intValue() == b.intValue()) return;
        adjacency.get(a).add(b);
        adjacency.get(b).add(a);
    }

    private static boolean isSideFace(Face face) {
        return face == Face.WEST || face == Face.NORTH || face == Face.EAST || face == Face.SOUTH;
    }

    private static int faceLocalWidth(Dimensions dims, Face face) {
        return switch (face) {
            case WEST, EAST -> dims.depth();
            case NORTH, SOUTH, UP, DOWN -> dims.width();
        };
    }

    private static Face nextSideFace(Face face) {
        return switch (face) {
            case WEST -> Face.NORTH;
            case NORTH -> Face.EAST;
            case EAST -> Face.SOUTH;
            case SOUTH -> Face.WEST;
            default -> throw new IllegalArgumentException("Not a side face: " + face);
        };
    }

    // ── Verification (F.1 step 3: "Unit-test or assertion-check every neighbor index, cap mapping,
    // and wide/slim boundary before any patch logic uses it.") ──────────────────────────────────────

    private void validate(CorpseFleshMask mask) {
        int size = mask.surfaceSize();
        for (int i = 0; i < size; i++) {
            for (int neighbor : neighborTable[i]) {
                if (neighbor < 0 || neighbor >= size) {
                    throw new IllegalStateException("Corpse anatomy neighbor index out of range");
                }
                if (!contains(neighborTable[neighbor], i)) {
                    throw new IllegalStateException("Corpse anatomy neighbor graph is not reciprocal");
                }
            }
        }

        int rightArmWidth = dimensionsByPart[Part.RIGHT_ARM.ordinal()].width();
        int leftArmWidth = dimensionsByPart[Part.LEFT_ARM.ordinal()].width();
        if (rightArmWidth != leftArmWidth || (rightArmWidth != 3 && rightArmWidth != 4)) {
            throw new IllegalStateException("Corpse anatomy arm width is inconsistent with the slim/wide surface");
        }

        validateCapWiring(mask, Part.BODY, Face.UP, false);
        validateCapWiring(mask, Part.BODY, Face.DOWN, false);
        validateCapWiring(mask, Part.HEAD, Face.DOWN, false);
        validateCapWiring(mask, Part.HEAD, Face.UP, true);
        for (Part part : UP_CAP_PARTS) {
            if (part != Part.HEAD) validateCapWiring(mask, part, Face.UP, true);
        }
        for (Part part : DOWN_CAP_PARTS) {
            validateCapWiring(mask, part, Face.DOWN, true);
        }
    }

    /** For an isolated cap ({@code expectWired == false}) asserts NO texel of that cap has a cross-
     *  face neighbor. For a wired cap asserts EXACTLY its border texels (and no interior texel) have
     *  one, and that the wired count matches the expected perimeter length — a stronger check than
     *  "at least one edge exists," catching a partially-wired cap the reciprocity check alone would
     *  miss. */
    private void validateCapWiring(CorpseFleshMask mask, Part part, Face cap, boolean expectWired) {
        Dimensions dims = dimensionsByPart[part.ordinal()];
        int width = dims.width();
        int depth = dims.depth();
        int expectedBorder = borderTexelCount(width, depth);
        int wiredCount = 0;
        for (int i = 0; i < mask.surfaceSize(); i++) {
            SurfaceTexel texel = mask.texel(i);
            if (texel.part() != part || texel.face() != cap) continue;
            boolean isBorder = texel.x() == 0 || texel.x() == width - 1
                    || texel.y() == 0 || texel.y() == depth - 1;
            boolean hasCrossFaceNeighbor = false;
            for (int neighbor : neighborTable[i]) {
                if (mask.texel(neighbor).face() != cap) {
                    hasCrossFaceNeighbor = true;
                    break;
                }
            }
            if (!expectWired) {
                if (hasCrossFaceNeighbor) {
                    throw new IllegalStateException(
                            "Corpse anatomy wired an isolated cap face " + part + "/" + cap);
                }
                continue;
            }
            if (isBorder != hasCrossFaceNeighbor) {
                throw new IllegalStateException(
                        "Corpse anatomy cap wiring mismatch on " + part + "/" + cap
                                + " at (" + texel.x() + "," + texel.y() + ")");
            }
            if (hasCrossFaceNeighbor) wiredCount++;
        }
        if (expectWired && wiredCount != expectedBorder) {
            throw new IllegalStateException("Corpse anatomy cap wiring count mismatch on "
                    + part + "/" + cap + ": expected " + expectedBorder + ", found " + wiredCount);
        }
    }

    private static int borderTexelCount(int width, int depth) {
        if (width <= 2 || depth <= 2) return width * depth;
        return width * depth - (width - 2) * (depth - 2);
    }

    private static boolean contains(int[] array, int value) {
        for (int candidate : array) {
            if (candidate == value) return true;
        }
        return false;
    }

    private record Dimensions(int width, int height, int depth) {}

    /** E.1's primary anatomical region for a texel. {@link #HIDDEN} is not part of E.3's table — see
     *  this class's own javadoc. */
    enum Region {
        HEAD_CROWN, FACE_UPPER, FACE_LOWER, FACE_SIDE, HEAD_BACK,
        CHEST, ABDOMEN, UPPER_BACK, LOWER_BACK, TORSO_FLANK,
        SHOULDER, UPPER_ARM, ELBOW, FOREARM, WRIST_HAND,
        HIP, THIGH, KNEE, SHIN, ANKLE_FOOT,
        HIDDEN
    }

    /** E.1's landmark tags. */
    enum Landmark { STERNUM, JAW, JOINT, LIMB_END, POSTERIOR_CONTACT }

    /** E.1: "Side — CENTRE, LEFT, or RIGHT, derived from Part." */
    enum Side { CENTRE, LEFT, RIGHT }

    /** RECONCILIATION.md R1's death-cause-vocabulary selectors — see {@link #matches}. */
    enum Selector {
        HEAD_ALL, CRANIUM, JAW, TORSO_ALL, STERNUM, UPPER_TORSO, LOWER_TORSO, TORSO_FLANKS,
        RIGHT_ARM_ALL, LEFT_ARM_ALL, RIGHT_LEG_ALL, LEFT_LEG_ALL,
        ARM_ROOTS, LEG_ROOTS, ARM_JOINTS, LEG_JOINTS, ARM_ENDS, LEG_ENDS,
        ALL_JOINTS, ALL_LIMB_ENDS, ALL_LIMB_ROOTS
    }
}
