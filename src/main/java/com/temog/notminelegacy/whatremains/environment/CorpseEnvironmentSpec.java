package com.temog.notminelegacy.whatremains.environment;

import javax.annotation.Nullable;

import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/**
 * One parsed {@code data/whatremains/corpse_environment/*.json} entry — an environment category
 * feeding exactly one of the five closed tracker ids ({@link #TRACKER_CHAR}, {@link #TRACKER_FROST},
 * {@link #TRACKER_WATERLOGGED}, {@link #TRACKER_SCORCH}, {@link #TRACKER_CALCIFY}). Multiple specs
 * may legitimately target the same tracker (e.g. {@code lava.json} alongside a future
 * char-feeding file, both feeding {@code char}; or {@code desert_scorch.json}/{@code nether_ash.json}
 * both feeding {@code scorch} with different {@link #styleKey()}s; or {@code submerged.json}/
 * {@code ocean.json} both feeding {@code waterlogged}), which is why {@code CorpseEnvironmentLoader}
 * stores these in a flat list rather than a map.
 * <p>
 * The tracker set is closed by design — each id needs a matching hardcoded synced field in
 * {@code CorpseEntity} ({@code DATA_CHAR_LEVEL}/{@code DATA_FROST_LEVEL}/
 * {@code DATA_WATERLOGGED_LEVEL}/{@code DATA_SCORCH_LEVEL}/{@code DATA_CALCIFY_LEVEL}) — so a
 * datapack can add new environment categories that feed an existing tracker, but not invent a
 * brand-new tracker.
 * <p>
 * {@link #priority()} exists only to break ties when two or more specs on the SAME tracker match
 * the same sampled environment at once but carry different {@link #styleKey()}s — see
 * {@link CorpseEnvironmentLoader#resolveStyleKey}. It has no effect on accumulation (that already
 * takes the max {@code accumulate_rate}/{@code decay_rate} across every matching spec regardless of
 * priority — see {@code CorpseEntity#nextTrackerLevel}), only on which style_key wins.
 */
public final class CorpseEnvironmentSpec {

    public static final String TRACKER_CHAR = "char";
    public static final String TRACKER_FROST = "frost";
    public static final String TRACKER_WATERLOGGED = "waterlogged";
    /** Shared by {@code desert_desiccation} and {@code nether_ash_scorch} — see {@link #priority()}
     *  and {@link CorpseEnvironmentLoader#resolveStyleKey} for how the two are told apart. One-way,
     *  same as {@link #TRACKER_CHAR} (enforced in {@code CorpseEnvironmentLoader}/{@code CorpseEntity}). */
    public static final String TRACKER_SCORCH = "scorch";
    /** {@code cave_calcification}. One-way, same as {@link #TRACKER_CHAR}. */
    public static final String TRACKER_CALCIFY = "calcify";

    private final String tracker;
    private final Condition condition;
    private final double accumulateRate;
    private final double decayRate;
    private final String styleKey;
    private final int priority;

    public CorpseEnvironmentSpec(String tracker, Condition condition, double accumulateRate,
                                  double decayRate, String styleKey, int priority) {
        this.tracker = tracker;
        this.condition = condition;
        this.accumulateRate = accumulateRate;
        this.decayRate = decayRate;
        this.styleKey = styleKey;
        this.priority = priority;
    }

    public String tracker() {
        return tracker;
    }

    public Condition condition() {
        return condition;
    }

    /** Intensity gained per real tick while the condition holds. */
    public double accumulateRate() {
        return accumulateRate;
    }

    /** Intensity lost per real tick while the condition does NOT hold. Always {@code 0.0} for
     *  specs targeting a one-way tracker ({@link #TRACKER_CHAR}/{@link #TRACKER_SCORCH}/
     *  {@link #TRACKER_CALCIFY}) — see {@code CorpseEnvironmentLoader}'s enforcement. */
    public double decayRate() {
        return decayRate;
    }

    /** Which visual style this spec represents, consumed by {@link CorpseEnvironmentLoader#resolveStyleKey}
     *  and (client-side, out of scope here) a future renderer. */
    public String styleKey() {
        return styleKey;
    }

    /** Tie-breaker among simultaneously-matching same-tracker specs — see the class javadoc. Defaults
     *  to {@code 0} when a JSON file omits {@code style_priority}. */
    public int priority() {
        return priority;
    }

    /** True when every AND-combined condition field currently holds against the given sample. */
    public boolean matches(CorpseEnvironment env) {
        return condition.matches(env);
    }

    /**
     * AND-combined match conditions. {@code fluid} is {@code "lava"}/{@code "water"} only for now
     * (not an arbitrary tag string — fine for v1, easy to widen later if a third fluid-driven
     * tracker shows up).
     * <p>
     * {@code maxTemperature}/{@code minTemperature}, {@code requireSkyVisible}/{@code requireNoSky},
     * {@code biomeTag}, and {@code minAgeTicks} are all optional — a null field means "no gate on
     * this axis." {@code requireSkyVisible} is a plain {@code boolean} (default {@code false} means
     * "doesn't matter"). {@code requireNoSky} is a genuine tri-state {@code Boolean} with three
     * DISTINCT behaviours: {@code null} (the field absent from the JSON) means "doesn't matter";
     * explicit {@code true} means the corpse must NOT be able to see sky; explicit {@code false}
     * means the corpse MUST be able to see sky — the natural negation a datapack author would expect
     * from writing {@code "require_no_sky": false}, and deliberately not a second spelling of
     * "doesn't matter" (which is what an absent field, {@code null}, already means).
     *
     * {@code requireOnFire} follows {@code requireSkyVisible}'s plain-{@code boolean} shape, not
     * {@code requireNoSky}'s tri-state: {@code false}/absent means "doesn't matter", {@code true}
     * means the corpse must currently be burning ({@code Entity#isOnFire()}). There is deliberately
     * no "must NOT be on fire" spelling — no environment category wants to gate on the ABSENCE of
     * fire (a corpse that isn't burning is just the ordinary case every other condition already
     * describes), and inventing the negation would add a third parse shape to this class for a use
     * case nothing has asked for. If one ever does, widen this to {@code Boolean} then, with
     * {@code requireNoSky}'s exact semantics as the precedent.
     */
    public static final class Condition {
        @Nullable
        private final String fluid;
        @Nullable
        private final Float maxTemperature;
        private final boolean requireSkyVisible;
        @Nullable
        private final Float minTemperature;
        @Nullable
        private final TagKey<Biome> biomeTag;
        @Nullable
        private final Boolean requireNoSky;
        @Nullable
        private final Integer minAgeTicks;
        private final boolean requireOnFire;

        public Condition(@Nullable String fluid, @Nullable Float maxTemperature, boolean requireSkyVisible,
                          @Nullable Float minTemperature, @Nullable TagKey<Biome> biomeTag,
                          @Nullable Boolean requireNoSky, @Nullable Integer minAgeTicks,
                          boolean requireOnFire) {
            this.fluid = fluid;
            this.maxTemperature = maxTemperature;
            this.requireSkyVisible = requireSkyVisible;
            this.minTemperature = minTemperature;
            this.biomeTag = biomeTag;
            this.requireNoSky = requireNoSky;
            this.minAgeTicks = minAgeTicks;
            this.requireOnFire = requireOnFire;
        }

        public boolean matches(CorpseEnvironment env) {
            if (fluid != null) {
                boolean fluidPresent = switch (fluid) {
                    case "lava" -> env.inLava();
                    case "water" -> env.inWater();
                    default -> false;
                };
                if (!fluidPresent) return false;
            }
            if (requireOnFire && !env.onFire()) {
                return false;
            }
            if (maxTemperature != null && env.biomeTemperature() > maxTemperature) {
                return false;
            }
            if (minTemperature != null && env.biomeTemperature() < minTemperature) {
                return false;
            }
            if (requireSkyVisible && !env.canSeeSky()) {
                return false;
            }
            if (requireNoSky != null) {
                // true = corpse must NOT see sky; false = corpse MUST see sky; null (handled above)
                // means "doesn't matter" — see the class javadoc for the full tri-state rationale.
                if (requireNoSky && env.canSeeSky()) {
                    return false;
                }
                if (!requireNoSky && !env.canSeeSky()) {
                    return false;
                }
            }
            if (biomeTag != null && !env.biome().is(biomeTag)) {
                return false;
            }
            if (minAgeTicks != null && env.ageTicks() < minAgeTicks) {
                return false;
            }
            return true;
        }
    }
}
