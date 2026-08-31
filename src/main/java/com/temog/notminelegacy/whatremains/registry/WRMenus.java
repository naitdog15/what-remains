package com.temog.notminelegacy.whatremains.registry;

import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.menu.CorpseMenu;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class WRMenus {

    private WRMenus() {}

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, WhatRemains.MODID);

    /**
     * The corpse menu requires a CorpseEntity on the server side.
     * For the client-side factory we use a dummy — the real CorpseMenu
     * is only created server-side via SimpleMenuProvider.
     * On client, Forge will create via the IForgeMenuType factory.
     */
    public static final RegistryObject<MenuType<CorpseMenu>> CORPSE_MENU =
            MENU_TYPES.register("corpse_menu", () ->
                    IForgeMenuType.create((windowId, playerInv, data) -> {
                        // Client: read entity ID from network buffer
                        int entityId = data.readInt();
                        var entity = playerInv.player.level().getEntity(entityId);
                        if (entity instanceof com.temog.notminelegacy.whatremains.entity.CorpseEntity corpse) {
                            return new CorpseMenu(windowId, playerInv, corpse);
                        }
                        return null;
                    }));

    public static void register(IEventBus bus) {
        MENU_TYPES.register(bus);
    }
}
