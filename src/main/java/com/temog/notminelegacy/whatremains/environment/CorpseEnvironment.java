package com.temog.notminelegacy.whatremains.environment;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * A one-shot sample of the physical conditions a {@code CorpseEntity} currently sits in —
 * fluid contact, live fire state, ambient biome temperature/identity, sky visibility, and corpse
 * age. Produced by
 * {@link CorpseEnvironmentSampler#sample} and consumed immediately by
 * {@code CorpseEntity#updateEnvironmentalExposure()} to update the persistent exposure trackers
 * (char/frost/waterlogged/scorch/calcify), resolve which style_key currently applies to a
 * multi-spec tracker (see {@link CorpseEnvironmentLoader#resolveStyleKey}), and recompute the
 * decay-rate multiplier; never stored itself.
 *
 * <p>{@code biome} and {@code ageTicks} were added alongside the {@code biomeTag}/{@code minAgeTicks}
 * {@link CorpseEnvironmentSpec.Condition} fields (env-corpse cases nether_ash_scorch/ocean_bloat and
 * cave_calcification respectively) — {@code biomeTemperature} alone can't answer "is this biome
 * tagged X", and nothing before this recorded the corpse's own age at all.
 *
 * <p>{@code onFire} is the corpse's live {@code Entity#isOnFire()} state — the ONLY entity-state
 * field here; every other one is a block/biome lookup. It is deliberately a separate axis from
 * {@code inLava} rather than folded into it: lava is a place, fire is a condition the corpse
 * carries with it, and the two do not imply each other in either direction. A corpse standing in a
 * fire block, struck by lightning, or hit by a Fire-Aspect mob is burning without any lava fluid
 * anywhere; conversely a corpse whose bounding box merely clips a lava pool is ignited by vanilla
 * while its {@code blockPosition()} — the only thing {@code inLava} looks at — is dry. Before this
 * field, nothing in the sampler could see any of that, so the entire {@code char}/charred route was
 * reachable by centred lava contact alone. The overlap is intentional and composes correctly:
 * {@code CorpseEntity#nextTrackerLevel} takes the MAX matching accumulate_rate, never the sum.
 */
public record CorpseEnvironment(boolean inLava, boolean inWater, boolean onFire,
                                 float biomeTemperature, boolean canSeeSky, Holder<Biome> biome,
                                 int ageTicks) {}
