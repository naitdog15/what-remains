package com.temog.notminelegacy.whatremains.mixin;

import com.temog.notminelegacy.compat.EarlyModCheck;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/** Conditional application gate for WhatRemains' optional-mod mixins. */
public final class WhatRemainsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("WhatRemains");

    /** Private literal: mixin plugins run before mod classes are safely loadable. */
    private static final String DNL_MODID = "dungeonnowloading";
    private static final String DNL_SUPPORTED_VERSION = "2.2";

    private static final String DNL_GREAT_BOTTLE_ITEM_MIXIN =
            "com.temog.notminelegacy.whatremains.mixin.compat.dnl.DnlGreatBottleItemMixin";
    private static final String DNL_GREAT_BOTTLE_ENTITY_MIXIN =
            "com.temog.notminelegacy.whatremains.mixin.compat.dnl.DnlGreatBottleEntityMixin";

    private static volatile Boolean dnlVersionSupported;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (DNL_GREAT_BOTTLE_ITEM_MIXIN.equals(mixinClassName)
                || DNL_GREAT_BOTTLE_ENTITY_MIXIN.equals(mixinClassName)) {
            return EarlyModCheck.isModLoadedEarlySafe(DNL_MODID)
                    && whatremains$isSupportedDnlVersion();
        }
        // WhatRemains' native XP-vessel mixins remain unconditional.
        return true;
    }

    /**
     * Whether the DungeonNowLoading build on the classpath is the exact version these two mixins
     * are ABI-matched against. Only reached after DNL presence has already been established.
     *
     * <p>Refuses only on positive evidence of a different version. If the version cannot be read
     * this early, the gate degrades to presence-only with one WARN, matching BuildingBridges'
     * established ABI-pin behaviour.
     */
    private static boolean whatremains$isSupportedDnlVersion() {
        Boolean cached = dnlVersionSupported;
        if (cached != null) {
            return cached;
        }
        String found = whatremains$readModVersionEarlySafe(DNL_MODID);
        boolean supported;
        if (found == null) {
            LOGGER.warn("[WhatRemains] Could not read the installed DungeonNowLoading version this early; "
                    + "applying the DNL Great Bottle mixins on presence alone. They are ABI-matched to {} — verify manually if DNL was updated.",
                    DNL_SUPPORTED_VERSION);
            supported = true;
        } else if (found.equals(DNL_SUPPORTED_VERSION) || found.endsWith("-" + DNL_SUPPORTED_VERSION)) {
            supported = true;
        } else {
            LOGGER.error("[WhatRemains] DungeonNowLoading {} is installed, but the DNL Great Bottle mixins are exact-ABI patches for {}. "
                    + "Both DNL Great Bottle mixins are DISABLED. Re-review the injection points against the new DNL source before widening the pin "
                    + "in WhatRemainsMixinPlugin and in whatremains.mods.toml.",
                    found, DNL_SUPPORTED_VERSION);
            supported = false;
        }
        dnlVersionSupported = supported;
        return supported;
    }

    /**
     * Reads a loaded mod's declared version at mixin-apply time, mirroring
     * {@code EarlyModCheck.isModLoadedEarlySafe}'s reflective LoadingModList fallback because
     * {@code ModList} is not guaranteed to be populated yet. Returns null when the version cannot
     * be determined. Methods are resolved off the Forge SPI interfaces so a non-public
     * implementation class can never make them inaccessible.
     */
    private static String whatremains$readModVersionEarlySafe(String modId) {
        try {
            Class<?> loadingModListClass = Class.forName("net.minecraftforge.fml.loading.LoadingModList");
            Object loadingModList = loadingModListClass.getMethod("get").invoke(null);
            if (loadingModList == null) {
                return null;
            }
            Object modFileInfo = loadingModListClass.getMethod("getModFileById", String.class)
                    .invoke(loadingModList, modId);
            if (modFileInfo == null) {
                return null;
            }
            Class<?> modFileInfoInterface = Class.forName("net.minecraftforge.forgespi.language.IModFileInfo");
            Class<?> modInfoInterface = Class.forName("net.minecraftforge.forgespi.language.IModInfo");
            Method getModId = modInfoInterface.getMethod("getModId");
            Method getVersion = modInfoInterface.getMethod("getVersion");
            Object mods = modFileInfoInterface.getMethod("getMods").invoke(modFileInfo);
            if (!(mods instanceof List<?> modList)) {
                return null;
            }
            for (Object mod : modList) {
                if (modId.equals(getModId.invoke(mod))) {
                    Object version = getVersion.invoke(mod);
                    return version == null ? null : version.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
            IMixinInfo mixinInfo) {
    }
}
