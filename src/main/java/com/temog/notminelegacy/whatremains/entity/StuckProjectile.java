package com.temog.notminelegacy.whatremains.entity;

import net.minecraft.nbt.CompoundTag;

/**
 * One arrow/trident visually stuck in a corpse's model — carried over from the dead player's own
 * vanilla arrow pincushion count, plus (at most one) killing-blow entry captured by {@code
 * PlayerDeathHandler} at the moment of death. Purely a render-input record: nothing server-side
 * reacts to these beyond carrying them through {@link CorpseEntity#getStuckProjectiles()}'s
 * sync/save round-trip — the actual rendering is owned separately.
 *
 * @param partIndex   which of the corpse model's 6 cubes the projectile is anchored to — see the
 *                    {@code PART_*} constants below for the exact addressing order. The render
 *                    layer depends on this order matching exactly; do not renumber it.
 * @param px          normalized {@code [0,1)} position within the chosen cube's local AABB, X axis
 *                    — the renderer lerps this against the cube's own min/max, the same way
 *                    vanilla's {@code StuckInBodyLayer} places arrows stuck in a living entity.
 * @param py          normalized {@code [0,1)} position within the chosen cube's local AABB, Y axis.
 * @param pz          normalized {@code [0,1)} position within the chosen cube's local AABB, Z axis.
 * @param kind        which projectile model/texture to render.
 * @param color       tipped-arrow potion colour (packed RGB), or {@code -1} for none/not applicable.
 * @param killingBlow true for the (at most one) entry that was the actual fatal projectile.
 */
public record StuckProjectile(int partIndex, float px, float py, float pz,
                              Kind kind, int color, boolean killingBlow) {

    // ── partIndex addressing — exact order the render layer depends on ──────────────────────────
    public static final int PART_HEAD = 0;
    public static final int PART_BODY = 1;
    public static final int PART_RIGHT_ARM = 2;
    public static final int PART_LEFT_ARM = 3;
    public static final int PART_RIGHT_LEG = 4;
    public static final int PART_LEFT_LEG = 5;

    public enum Kind { ARROW, TIPPED_ARROW, SPECTRAL_ARROW, TRIDENT }

    private static final String KEY_PART = "Part";
    private static final String KEY_PX = "Px";
    private static final String KEY_PY = "Py";
    private static final String KEY_PZ = "Pz";
    private static final String KEY_KIND = "Kind";
    private static final String KEY_COLOR = "Color";
    private static final String KEY_KILLING = "Killing";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(KEY_PART, partIndex);
        tag.putFloat(KEY_PX, px);
        tag.putFloat(KEY_PY, py);
        tag.putFloat(KEY_PZ, pz);
        tag.putString(KEY_KIND, kind.name());
        tag.putInt(KEY_COLOR, color);
        tag.putBoolean(KEY_KILLING, killingBlow);
        return tag;
    }

    /**
     * Safe-defaults loader: clamps {@code partIndex} into {@code 0..5}, clamps every position into
     * {@code [0,1)}, and falls back to {@link Kind#ARROW} for an unrecognized (or missing) kind
     * name — so a hand-edited tag, or a future format drift, can never hand the render layer an
     * out-of-range cube index or an unknown enum constant.
     */
    public static StuckProjectile load(CompoundTag tag) {
        int part = Math.max(PART_HEAD, Math.min(PART_LEFT_LEG, tag.getInt(KEY_PART)));
        float px = clamp01(tag.getFloat(KEY_PX));
        float py = clamp01(tag.getFloat(KEY_PY));
        float pz = clamp01(tag.getFloat(KEY_PZ));
        Kind kind;
        try {
            kind = Kind.valueOf(tag.getString(KEY_KIND));
        } catch (IllegalArgumentException unknownKind) {
            kind = Kind.ARROW;
        }
        int color = tag.contains(KEY_COLOR) ? tag.getInt(KEY_COLOR) : -1;
        boolean killingBlow = tag.getBoolean(KEY_KILLING);
        return new StuckProjectile(part, px, py, pz, kind, color, killingBlow);
    }

    private static float clamp01(float value) {
        if (!(value >= 0.0F)) return 0.0F; // also catches NaN, which fails every comparison
        return Math.min(value, Math.nextDown(1.0F));
    }

    /**
     * Minimal SplitMix64 stream, used to seed fully deterministic (never {@code Math.random()} or
     * {@code level.random}) placement for stuck projectiles — see {@code PlayerDeathHandler}'s
     * capture logic, which steps one of these from the corpse's own {@link
     * CorpseEntity#getAppearanceSeed()} so every client renders the identical arrow layout, and the
     * same layout survives a relog. {@link #mix} alone (no stepping) is also reused by {@link
     * CorpseEntity}'s own UUID-derived appearance-seed default as its finalizer. Same
     * multiply-xorshift constants as {@code whatremains.client.CorpseFleshMask}'s own
     * package-private copy — duplicated rather than imported so this server-side entity class
     * carries no dependency on client-only code.
     */
    public static final class SeedRandom {
        private long state;

        public SeedRandom(long seed) {
            this.state = seed;
        }

        /** Advances the stream and returns the next 64-bit value. */
        public long nextLong() {
            state += 0x9E3779B97F4A7C15L;
            return mix(state);
        }

        public int nextInt(int bound) {
            return (int) Long.remainderUnsigned(nextLong(), bound);
        }

        /** Uniform-density value in {@code [0,1)} — top 24 bits of a fresh draw. */
        public float nextFloat01() {
            return (nextLong() >>> 40) * 0x1.0p-24F;
        }

        public static long mix(long value) {
            value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
            value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
            return value ^ value >>> 31;
        }
    }
}
