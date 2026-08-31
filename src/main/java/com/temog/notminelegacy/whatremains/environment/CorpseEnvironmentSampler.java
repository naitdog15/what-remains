package com.temog.notminelegacy.whatremains.environment;

import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Stateless sampler — reads a corpse's current physical surroundings, keyed off
 * {@code corpse.level()} + {@code corpse.blockPosition()}. It also reads one field off the entity
 * itself: the corpse's live fire state. Modeled on
 * {@code CorpseFlyAccessibility}'s shape (same "cheap static utility over a live entity" idiom),
 * but deliberately diverges on fluid detection: this uses tag-based
 * {@code getFluidState(pos).is(FluidTags.LAVA/WATER)} rather than the entity convenience methods
 * ({@code Entity#isInLava()}/{@code isUnderWater()}) CorpseFlyAccessibility uses, so a modded
 * fluid tagged into {@code #minecraft:lava}/{@code #minecraft:water} is picked up automatically —
 * matching the extensibility precedent {@code DeathCause} set with {@code DamageTypeTags}. That's
 * a different concern (material classification vs. pathability), so a different method here is
 * justified, not an inconsistency.
 */
public final class CorpseEnvironmentSampler {

    private CorpseEnvironmentSampler() {}

    public static CorpseEnvironment sample(CorpseEntity corpse) {
        Level level = corpse.level();
        BlockPos pos = corpse.blockPosition();
        boolean inLava = level.getFluidState(pos).is(FluidTags.LAVA);
        boolean inWater = level.getFluidState(pos).is(FluidTags.WATER);
        // Entity state, not block state — the one exception in this method. isOnFire() is preferred
        // over a raw getRemainingFireTicks() > 0 because it also honours fireImmune(): if this
        // EntityType is ever given .fireImmune(), or a mod grants immunity, charring should stop,
        // and this is the same predicate vanilla uses to decide the flame overlay. Server-only call
        // site (CorpseEntity#updateEnvironmentalExposure runs inside the !isClientSide branch), so
        // it reads the authoritative remainingFireTicks, never the client shared flag.
        boolean onFire = corpse.isOnFire();
        Holder<Biome> biome = level.getBiome(pos);
        float temp = biome.value().getBaseTemperature();
        boolean sky = level.canSeeSky(pos);
        int ageTicks = corpse.getCorpseAgeTicks();
        return new CorpseEnvironment(inLava, inWater, onFire, temp, sky, biome, ageTicks);
    }
}
