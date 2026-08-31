package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.entity.DeathCause;

/**
 * Snapshot of a corpse's environmental exposure trackers (char/scorch/calcify/frost/waterlogged +
 * the two resolved style latches + the wither-scarred death-cause fact), captured once on the
 * render thread by {@link #from(CorpseEntity)} and threaded through the whole bake pipeline
 * ({@link CorpseFleshLayer} → {@link CorpseRenderState#withEnvironment} →
 * {@link CorpseFleshBaker#resolve} → the off-thread bake task) — a plain immutable record of
 * primitives/strings is safe to hand to a background thread the same way
 * {@code CorpseRenderState}/{@code SourceSnapshot} already are; a live {@code CorpseEntity} is not.
 *
 * <p>See {@code reference/session_context/2026-08-22/env-corpse-designs/} for the seven case
 * designs this feeds: {@code lava_progressive_incineration} (char), {@code desert_desiccation} /
 * {@code nether_ash_scorch} (scorch, disambiguated by {@link #scorchStyle()}),
 * {@code cave_calcification} (calcify), {@code frozen_rime} (frost), {@code ocean_bloat}
 * (waterlogged, disambiguated by {@link #waterloggedStyle()}), {@code wither_scarred}
 * ({@link #witherScarred()}).
 */
public record EnvironmentalRenderInputs(float charLevel, float scorchLevel, String scorchStyle,
                                        float calcifyLevel, float frostLevel,
                                        float waterloggedLevel, String waterloggedStyle,
                                        boolean witherScarred) {

    /** {@code CorpseEnvironmentSpec.styleKey()} value used by {@code desert_scorch.json}. */
    public static final String SCORCH_STYLE_DESERT = "desert_sun";
    /** {@code CorpseEnvironmentSpec.styleKey()} value used by {@code nether_ash.json}. */
    public static final String SCORCH_STYLE_NETHER = "nether_ash";
    /** {@code CorpseEnvironmentSpec.styleKey()} value used by {@code ocean.json}. */
    public static final String WATERLOGGED_STYLE_OCEAN = "ocean_bloat";

    /** All-zero/neutral instance — used as a safe non-null default before a corpse's first real
     *  render-thread sample (mirrors how {@code CorpseFleshBaker.OwnerSource} needs a placeholder
     *  before {@code resolve()} has ever run for it). */
    public static final EnvironmentalRenderInputs NONE =
            new EnvironmentalRenderInputs(0.0F, 0.0F, "", 0.0F, 0.0F, 0.0F, "", false);

    public static EnvironmentalRenderInputs from(CorpseEntity entity) {
        return new EnvironmentalRenderInputs(
                entity.getCharLevel(),
                entity.getScorchLevel(), entity.getScorchStyle(),
                entity.getCalcifyLevel(),
                entity.getFrostLevel(),
                entity.getWaterloggedLevel(), entity.getWaterloggedStyle(),
                entity.getDeathCause() == DeathCause.WITHERED);
    }
}
