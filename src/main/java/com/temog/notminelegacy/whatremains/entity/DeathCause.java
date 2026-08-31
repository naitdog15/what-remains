package com.temog.notminelegacy.whatremains.entity;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;

/**
 * How a player died, captured on their {@link CorpseEntity} at spawn ({@code PlayerDeathHandler})
 * so a later, separately-scoped task can give corpses a cause-specific visual treatment (e.g.
 * {@code BURNED} → charred). This enum is purely data — no rendering/texture logic lives here.
 */
public enum DeathCause {

    // ── Values (order here is display/iteration only — match priority lives in fromDamageSource) ──
    BURNED,
    FROZEN,
    DROWNED,
    LIGHTNING,
    EXPLODED,
    VOID,
    FALL,
    CRUSHED,
    WITHERED,
    STARVED,
    MAGIC,
    RANGED,
    MELEE,
    NATURAL;

    /**
     * Classifies a {@link DamageSource} into a {@link DeathCause}, first-match-wins in the exact
     * priority order below. Order matters — several vanilla damage types could otherwise match more
     * than one bucket, and the narratively-more-distinct bucket must win (e.g. {@code wither_skull}
     * reads as {@link #WITHERED}, not {@link #RANGED}, even though it's also a projectile; falling
     * into the void reads as {@link #VOID}, not a generic {@link #FALL}, even though it also carries
     * {@link DamageTypeTags#IS_FALL}).
     * <p>
     * Defaults to {@link #NATURAL} for anything unmatched (generic, generic_kill, dry_out,
     * bad_respawn_point, outside_border, sonic_boom — not a member of {@link DamageTypeTags#IS_EXPLOSION}
     * in vanilla — or any unrecognised/modded damage type). This is also the safe default for corpse
     * NBT saved before this field existed.
     */
    public static DeathCause fromDamageSource(DamageSource source) {
        if (source == null) return NATURAL;

        if (source.is(DamageTypeTags.IS_FIRE))       return BURNED;
        if (source.is(DamageTypeTags.IS_FREEZING))   return FROZEN;
        if (source.is(DamageTypeTags.IS_DROWNING))   return DROWNED;
        if (source.is(DamageTypeTags.IS_LIGHTNING))  return LIGHTNING;
        if (source.is(DamageTypeTags.IS_EXPLOSION))  return EXPLODED;

        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) return VOID;
        if (source.is(DamageTypeTags.IS_FALL))        return FALL;

        if (source.is(DamageTypes.FALLING_BLOCK)
                || source.is(DamageTypes.FALLING_ANVIL)
                || source.is(DamageTypes.FALLING_STALACTITE)
                || source.is(DamageTypes.STALAGMITE)
                || source.is(DamageTypes.IN_WALL)
                || source.is(DamageTypes.CRAMMING))   return CRUSHED;

        if (source.is(DamageTypes.WITHER)
                || source.is(DamageTypes.WITHER_SKULL)) return WITHERED;

        if (source.is(DamageTypes.STARVE))            return STARVED;

        if (source.is(DamageTypes.MAGIC)
                || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.DRAGON_BREATH)) return MAGIC;

        if (source.is(DamageTypeTags.IS_PROJECTILE))  return RANGED;

        if (source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK)
                || source.is(DamageTypes.THORNS)
                || source.is(DamageTypes.STING)
                || source.is(DamageTypes.CACTUS)
                || source.is(DamageTypes.SWEET_BERRY_BUSH)) return MELEE;

        return NATURAL;
    }

    /**
     * Safe ordinal→enum lookup for NBT/synced-data round-tripping — out-of-range or stale ordinals
     * (e.g. a corpse saved before this field existed, or before a future rebalance of the constant
     * list) fall back to {@link #NATURAL} rather than throwing.
     */
    public static DeathCause byOrdinal(int ordinal) {
        DeathCause[] values = values();
        if (ordinal < 0 || ordinal >= values.length) return NATURAL;
        return values[ordinal];
    }

    /**
     * Case-insensitive name→enum lookup, for hand-authored NBT (e.g. a {@code /summon} tag like
     * {@code {DeathCause:"BURNED"}}) where a raw ordinal byte is unfriendly to type. Falls back to
     * {@link #NATURAL} for an unrecognised name, same safe-default policy as {@link #byOrdinal(int)}.
     */
    public static DeathCause byName(String name) {
        if (name == null) return NATURAL;
        for (DeathCause cause : values()) {
            if (cause.name().equalsIgnoreCase(name)) return cause;
        }
        return NATURAL;
    }
}
