package com.temog.notminelegacy.whatremains.compat.dnl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.mojang.logging.LogUtils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;

/**
 * Reflective access to DungeonNowLoading's {@code GreatExperienceBottleEntity}.
 *
 * <p>DungeonNowLoading is NOT a compile-time dependency of this project — unlike Legendary Survival
 * Overhaul, Quark, GeckoLib etc. (see {@code build.gradle}: no {@code compileOnly fg.deobf(...)}
 * entry for {@code dungeonnowloading}, and no jar under {@code reference/} used that way; only the
 * dev-runtime jar in {@code run/mods_raw/}). Its class therefore cannot be imported anywhere in
 * this module's compile-time type surface, so the entity is constructed and driven entirely by
 * reflection.
 *
 * <p>Every member here takes/returns ONLY vanilla types ({@link Entity}, {@link Level},
 * {@link LivingEntity}), so this class compiles and classloads with zero risk regardless of whether
 * DNL is actually installed — a missing class simply fails {@link Class#forName} once, which is
 * caught and logged.
 *
 * <p>Package-private; only called from {@link DnlGreatBottleBridge} after its DNL-presence gate.
 */
final class DnlReflection {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String ENTITY_CLASS =
            "dev.hexnowloading.dungeonnowloading.entity.misc.GreatExperienceBottleEntity";
    private static final String SET_EXPERIENCE_AMOUNT_METHOD = "setExperienceAmount";

    private static volatile boolean resolveAttempted;
    private static Constructor<?> throwConstructor;
    private static Method setExperienceAmountMethod;

    private DnlReflection() {}

    private static synchronized void resolve() {
        if (resolveAttempted) return;
        resolveAttempted = true;
        try {
            Class<?> entityClass = Class.forName(ENTITY_CLASS);
            // Mirrors GreatExperienceBottleItem#use's own instantiation: new
            // GreatExperienceBottleEntity(level, player) — the (Level, LivingEntity) constructor
            // sets owner + spawn position exactly as vanilla Projectile's thrower constructor does,
            // so no manual setPos replication is needed here.
            throwConstructor = entityClass.getConstructor(Level.class, LivingEntity.class);
            setExperienceAmountMethod = entityClass.getMethod(SET_EXPERIENCE_AMOUNT_METHOD, int.class);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[WhatRemains] Could not reflectively resolve DungeonNowLoading's "
                    + "GreatExperienceBottleEntity (Level, LivingEntity) constructor / setExperienceAmount(int) "
                    + "— the Great Bottle throw bridge is disabled until this is fixed.", e);
        }
    }

    /**
     * Spawns via DNL's own {@code (Level, LivingEntity)} constructor. Returns null (logged once by
     * {@link #resolve()}) if reflection failed — callers must treat null as "throw bridge
     * unavailable" and decline gracefully rather than throwing.
     */
    static Entity createThrown(Level level, LivingEntity thrower) {
        resolve();
        if (throwConstructor == null) return null;
        try {
            return (Entity) throwConstructor.newInstance(level, thrower);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[WhatRemains] Failed to construct GreatExperienceBottleEntity reflectively", e);
            return null;
        }
    }

    /** No-op if reflection failed (already logged once by {@link #resolve()}) or entity is null. */
    static void setExperienceAmount(Entity entity, int amount) {
        resolve();
        if (setExperienceAmountMethod == null || entity == null) return;
        try {
            setExperienceAmountMethod.invoke(entity, amount);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[WhatRemains] Failed to invoke "
                    + "GreatExperienceBottleEntity#setExperienceAmount reflectively", e);
        }
    }
}
