package com.temog.notminelegacy.whatremains.environment;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code data/<namespace>/corpse_environment/<name>.json} files and builds a flat list of
 * {@link CorpseEnvironmentSpec}s — a datapack registry following {@code EcosystemLoader}/
 * {@code CollisionLoader}'s exact idiom (manual {@link GsonHelper} parsing, a
 * {@code volatile} list swapped atomically at the end of {@link #apply}, a
 * {@code public static all()} getter, per-file try/catch so one bad file never breaks reload).
 *
 * <p>JSON format:
 * <pre>{@code
 * {
 *   "tracker": "char",
 *   "condition": {
 *     "fluid": "lava",
 *     "require_on_fire": true,
 *     "max_temperature": 0.15,
 *     "min_temperature": 0.8,
 *     "require_sky_visible": true,
 *     "require_no_sky": true,
 *     "biome_tag": "minecraft:is_nether",
 *     "min_age_ticks": 24000
 *   },
 *   "accumulate_rate": 0.005,
 *   "decay_rate": 0.0,
 *   "style_key": "charred",
 *   "style_priority": 0
 * }
 * }</pre>
 *
 * <p>{@code tracker} must be one of the five closed ids ({@link CorpseEnvironmentSpec#TRACKER_CHAR}/
 * {@link CorpseEnvironmentSpec#TRACKER_FROST}/{@link CorpseEnvironmentSpec#TRACKER_WATERLOGGED}/
 * {@link CorpseEnvironmentSpec#TRACKER_SCORCH}/{@link CorpseEnvironmentSpec#TRACKER_CALCIFY}) —
 * each needs a matching hardcoded synced field in {@code CorpseEntity}, so an unrecognised tracker
 * id is discarded with a warning rather than silently accepted. All {@code condition} sub-fields
 * are optional and AND-combined; omitting all of them means "always matches". {@code style_priority}
 * is optional, defaulting to {@code 0}.
 *
 * <p><b>{@code char}/{@code scorch}/{@code calcify} are one-way — enforced here, belt-and-braces.</b>
 * Regardless of whatever {@code decay_rate} a JSON author sets on a file feeding one of these three
 * trackers, this loader forces it to {@code 0.0} (with a warning log so an author-set nonzero value
 * isn't silently swallowed with no trace). This is one of TWO independent enforcement points —
 * {@code CorpseEntity}'s own tick-side accumulator also independently clamps each one-way tracker to
 * never decrease — neither relies on the other to hold the invariant.
 */
public class CorpseEnvironmentLoader extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final Set<String> KNOWN_TRACKERS = Set.of(
            CorpseEnvironmentSpec.TRACKER_CHAR,
            CorpseEnvironmentSpec.TRACKER_FROST,
            CorpseEnvironmentSpec.TRACKER_WATERLOGGED,
            CorpseEnvironmentSpec.TRACKER_SCORCH,
            CorpseEnvironmentSpec.TRACKER_CALCIFY);

    /** Trackers whose level never decreases — see the class javadoc's enforcement note. */
    private static final Set<String> ONE_WAY_TRACKERS = Set.of(
            CorpseEnvironmentSpec.TRACKER_CHAR,
            CorpseEnvironmentSpec.TRACKER_SCORCH,
            CorpseEnvironmentSpec.TRACKER_CALCIFY);

    // Volatile reference — swapped atomically on each reload so CorpseEntity always sees a
    // complete list, never a partially-built one.
    private static volatile List<CorpseEnvironmentSpec> SPECS = List.of();

    public CorpseEnvironmentLoader() {
        super(GSON, "corpse_environment");
    }

    /** Returns every currently-loaded spec. Filter by {@link CorpseEnvironmentSpec#tracker()} at
     *  the call site — multiple specs may legitimately target the same tracker. */
    public static List<CorpseEnvironmentSpec> all() {
        return SPECS;
    }

    /**
     * Resolves which {@code style_key} currently applies to {@code tracker} against the sampled
     * environment: the {@link CorpseEnvironmentSpec#priority()}-highest spec among every spec
     * targeting this tracker that currently {@link CorpseEnvironmentSpec#matches}. Ties keep
     * whichever spec was encountered first in load order — only expected to matter between specs
     * whose conditions are mutually exclusive in practice (e.g. {@code desert_scorch.json}'s
     * {@code require_sky_visible} vs. {@code nether_ash.json}'s {@code biome_tag: is_nether} can
     * never both hold at once), so an arbitrary load-order tie-break is an acceptable, "simple and
     * explicit" answer rather than a real design gap.
     * <p>
     * Returns {@code ""} when nothing currently matches. Callers (see {@code CorpseEntity}) are
     * expected to treat that as "no change" and keep whatever style they last resolved, rather than
     * clearing it — a one-way tracker's chosen style must survive the corpse later leaving the
     * environment that produced it, exactly as the numeric level itself never resets either.
     */
    public static String resolveStyleKey(String tracker, CorpseEnvironment env) {
        String bestStyle = "";
        int bestPriority = Integer.MIN_VALUE;
        for (CorpseEnvironmentSpec spec : SPECS) {
            if (!spec.tracker().equals(tracker)) continue;
            if (!spec.matches(env)) continue;
            if (spec.priority() > bestPriority) {
                bestPriority = spec.priority();
                bestStyle = spec.styleKey();
            }
        }
        return bestStyle;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data,
                          ResourceManager manager,
                          ProfilerFiller profiler) {

        List<CorpseEnvironmentSpec> loaded = new ArrayList<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : data.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try {
                JsonObject json = entry.getValue().getAsJsonObject();

                String tracker = GsonHelper.getAsString(json, "tracker");
                if (!KNOWN_TRACKERS.contains(tracker)) {
                    LOGGER.warn("[WhatRemains/CorpseEnvironment] {} — unknown tracker '{}' (must be one of {}), skipping",
                            fileId, tracker, KNOWN_TRACKERS);
                    continue;
                }

                CorpseEnvironmentSpec.Condition condition = resolveCondition(json);

                double accumulateRate = GsonHelper.getAsDouble(json, "accumulate_rate", 0.0);
                double decayRate = GsonHelper.getAsDouble(json, "decay_rate", 0.0);
                String styleKey = GsonHelper.getAsString(json, "style_key", "");
                int stylePriority = GsonHelper.getAsInt(json, "style_priority", 0);

                // Hard-enforce one-way trackers' rule server-side, regardless of what the JSON says.
                if (ONE_WAY_TRACKERS.contains(tracker) && decayRate != 0.0) {
                    LOGGER.warn("[WhatRemains/CorpseEnvironment] {} — tracker '{}' is one-way (never "
                                    + "decreases); ignoring nonzero decay_rate {} and forcing it to 0.0",
                            fileId, tracker, decayRate);
                    decayRate = 0.0;
                }

                loaded.add(new CorpseEnvironmentSpec(tracker, condition, accumulateRate, decayRate, styleKey, stylePriority));

                LOGGER.debug("[WhatRemains/CorpseEnvironment] {}: tracker={}, accumulateRate={}, decayRate={}, styleKey={}, stylePriority={}",
                        fileId, tracker, accumulateRate, decayRate, styleKey, stylePriority);

            } catch (Exception e) {
                LOGGER.error("[WhatRemains/CorpseEnvironment] Failed to load '{}': {}", fileId, e.getMessage());
            }
        }

        SPECS = List.copyOf(loaded);
        LOGGER.info("[WhatRemains/CorpseEnvironment] Loaded {} spec(s)", SPECS.size());
    }

    /** Parses the optional {@code condition} object. Absent entirely means "always matches" (no
     *  gate on any axis at all). */
    private static CorpseEnvironmentSpec.Condition resolveCondition(JsonObject json) {
        if (!json.has("condition")) {
            return new CorpseEnvironmentSpec.Condition(null, null, false, null, null, null, null, false);
        }
        JsonObject section = GsonHelper.getAsJsonObject(json, "condition");

        String fluid = section.has("fluid") ? GsonHelper.getAsString(section, "fluid") : null;
        Float maxTemperature = section.has("max_temperature")
                ? GsonHelper.getAsFloat(section, "max_temperature")
                : null;
        boolean requireSkyVisible = GsonHelper.getAsBoolean(section, "require_sky_visible", false);
        Float minTemperature = section.has("min_temperature")
                ? GsonHelper.getAsFloat(section, "min_temperature")
                : null;
        TagKey<Biome> biomeTag = section.has("biome_tag")
                ? TagKey.create(Registries.BIOME, new ResourceLocation(GsonHelper.getAsString(section, "biome_tag")))
                : null;
        Boolean requireNoSky = section.has("require_no_sky")
                ? GsonHelper.getAsBoolean(section, "require_no_sky")
                : null;
        Integer minAgeTicks = section.has("min_age_ticks")
                ? GsonHelper.getAsInt(section, "min_age_ticks")
                : null;
        boolean requireOnFire = GsonHelper.getAsBoolean(section, "require_on_fire", false);

        return new CorpseEnvironmentSpec.Condition(fluid, maxTemperature, requireSkyVisible,
                minTemperature, biomeTag, requireNoSky, minAgeTicks, requireOnFire);
    }
}
