package com.temog.notminelegacy.whatremains.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.temog.notminelegacy.whatremains.client.CorpseFleshMask.SplitMix64;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.DeathCause;
import com.temog.notminelegacy.whatremains.entity.StuckProjectile;

import javax.annotation.Nullable;

/**
 * Immutable, background-safe snapshot of everything a death-cause injury needs about one corpse
 * (SOL-DEATHCAUSE-INJURY-DESIGN.md F1 step 3). Captured once on the render thread by {@link
 * #from(CorpseEntity)}, alongside {@link EnvironmentalRenderInputs#from(CorpseEntity)} and the
 * existing {@code appearanceSeed} read, then threaded unchanged through {@code CorpseFleshBaker}'s
 * whole {@code resolve -> requestBake -> BakeTask -> bake} pipeline exactly the way {@code
 * EnvironmentalRenderInputs} already is.
 * <p>
 * {@link #from} copies only primitives, the immutable {@link UUID}, enums, and (via {@link Anchor}) a
 * plain record of primitive projectile-placement fields — it never touches a live {@link CorpseEntity},
 * an {@code ItemStack}, mutable NBT, or {@code getStuckProjectiles()}'s own mutable-looking backing
 * list beyond copying its primitive contents out. Safe to hand to {@code CorpseFleshBaker}'s
 * background worker thread the same way {@code CorpseRenderState}/{@code SourceSnapshot} already are.
 */
record InjuryRenderInputs(UUID corpseUuid, long appearanceSeed, DeathCause deathCause,
                          @Nullable Anchor killingAnchor, List<Anchor> nonKillingAnchors,
                          long anchorFingerprint) {

    /** All-neutral placeholder — mirrors {@code EnvironmentalRenderInputs#NONE}'s role as a safe
     *  non-null default for {@code CorpseFleshBaker.OwnerSource} fields before a corpse's first real
     *  render-thread sample. */
    static final InjuryRenderInputs NONE =
            new InjuryRenderInputs(new UUID(0L, 0L), 0L, DeathCause.NATURAL, null, List.of(), 0L);

    /** At most this many non-killing anchors are kept (E1: "limited to the first three non-killing
     *  arrows in list order" — a heavily-pincushioned corpse should not turn its flesh into noisy
     *  polka dots, nor drown out the one projectile that actually killed the player). */
    static final int MAX_NON_KILLING_ANCHORS = 3;

    /** Trimmed-down mirror of {@link StuckProjectile}'s placement fields — {@code color}/{@code
     *  killingBlow} are deliberately dropped: colour never affects flesh injury, and "is this the
     *  killing entry" is already expressed structurally by which of {@link #killingAnchor}/{@link
     *  #nonKillingAnchors} an anchor lives in. {@code part} is a raw {@code StuckProjectile.PART_*}
     *  index (0..5), matching {@code CorpseFleshMask.Part}'s own declaration order exactly, so callers
     *  may resolve it via {@code Part.values()[anchor.part()]} without a translation table. */
    record Anchor(int part, float px, float py, float pz, StuckProjectile.Kind kind) {}

    static InjuryRenderInputs from(CorpseEntity entity) {
        List<StuckProjectile> stuck = entity.getStuckProjectiles();
        Anchor killing = null;
        List<Anchor> nonKilling = new ArrayList<>();
        for (StuckProjectile projectile : stuck) {
            Anchor anchor = new Anchor(projectile.partIndex(), projectile.px(), projectile.py(),
                    projectile.pz(), projectile.kind());
            if (projectile.killingBlow() && killing == null) {
                killing = anchor;
            } else if (nonKilling.size() < MAX_NON_KILLING_ANCHORS) {
                nonKilling.add(anchor);
            }
        }
        List<Anchor> nonKillingCopy = List.copyOf(nonKilling);
        return new InjuryRenderInputs(entity.getUUID(), entity.getAppearanceSeed(), entity.getDeathCause(),
                killing, nonKillingCopy, fingerprint(killing, nonKillingCopy));
    }

    /** One stable fingerprint over EVERY copied anchor (killing plus every kept non-killing one), not
     *  just the killing anchor — required so a corpse whose non-killing arrow layout changes (e.g. a
     *  new arrow lands post-mortem) still gets a fresh {@code CorpseInjuryMask} cache entry rather than
     *  silently reusing stale entry-shadow placement. */
    private static long fingerprint(@Nullable Anchor killing, List<Anchor> nonKilling) {
        long h = SplitMix64.mix(killing == null ? 0x494E4A5F4E4F4B4CL : anchorBits(killing) ^ 0x494E4A5F4B494C4CL);
        for (Anchor anchor : nonKilling) {
            h = SplitMix64.mix(h ^ anchorBits(anchor));
        }
        return h;
    }

    private static long anchorBits(Anchor anchor) {
        long bits = ((long) anchor.part() << 56) ^ ((long) anchor.kind().ordinal() << 48);
        bits ^= (long) Float.floatToIntBits(anchor.px()) << 32;
        bits ^= (long) Float.floatToIntBits(anchor.py()) << 16;
        bits ^= Integer.toUnsignedLong(Float.floatToIntBits(anchor.pz()));
        return SplitMix64.mix(bits);
    }
}
