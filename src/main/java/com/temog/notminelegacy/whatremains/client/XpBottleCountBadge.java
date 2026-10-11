package com.temog.notminelegacy.whatremains.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.temog.notminelegacy.whatremains.WhatRemains;
import com.temog.notminelegacy.whatremains.WhatRemainsConfig;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.IItemDecorator;

/**
 * Draws the stored level-equivalent in the top-left corner of a Bottle o' Enchanting's icon
 * (LB-03 V2, user ruling 2) — the number a player would otherwise have to open the tooltip to see.
 * <p>
 * XPB-04: by default the digit is no longer the bottle's own intrinsic level-from-zero figure —
 * it is the MARGINAL levels the local viewing player would actually gain by drinking/depositing
 * this bottle right now, computed from {@link Minecraft#getInstance()}'s player (always the
 * correct "who is looking at this slot" for any GUI-rendered item icon). See
 * {@link XpVessel#marginalLevelsFor}. Falls back to the intrinsic figure
 * ({@link XpVessel#storedLevel}) when the marginal gate is off or no viewer is resolvable.
 * <p>
 * XPB-03: also serves the DungeonNowLoading Great Bottle. Both {@code storedLevel}/{@code getStoredXp}
 * are already item-aware ({@link XpVessel#isGreatBottle}), so only the activation gate below needs
 * to dispatch by item — the render body is identical either way.
 * <p>
 * Registered against {@code Items#EXPERIENCE_BOTTLE} and (when present) the DNL item in
 * {@code WRClientSetup#registerItemDecorations} via {@code RegisterItemDecorationsEvent}, the only
 * registration hook Forge offers for this. There
 * is no vanilla "count badge" this mirrors — the layout below blits original blocky numeral cells
 * from a direct GUI texture. Each 3x5 numeral has a baked one-pixel dark outline, and three 5x7
 * cells occupy 15 pixels, fitting levels through the configured maximum of 150 without scaling.
 * Vanilla's own stack-count text remains separate in the bottom-right corner
 * ({@code GuiGraphics#renderItemDecorations}).
 * <p>
 * <b>Z-order is the whole trick here.</b> {@code GuiGraphics#renderItemDecorations} pushes its own
 * pose, draws the vanilla stack-count text at {@code z+200} inside that push, then <i>pops</i> the
 * pose back to ambient depth before handing off to {@code ItemDecoratorHandler}, which is what
 * finally calls {@link #render}. So this method starts at the same ambient depth the item slot
 * started from — well below the {@code z=150} the item mesh itself was drawn at a moment earlier in
 * {@code GuiGraphics#renderItem}. Skipping the translate below does not throw or warn; it just draws
 * the number behind the item mesh, where nobody will ever see it.
 */
public final class XpBottleCountBadge implements IItemDecorator {

    private static final ResourceLocation DIGIT_TEXTURE = new ResourceLocation(
            WhatRemains.MODID, "textures/gui/xp_bottle_digits.png");

    /** A 3x5 numeral plus a baked one-pixel outline on every side. */
    private static final int CELL_WIDTH = 5;
    private static final int CELL_HEIGHT = 7;
    private static final int TEXTURE_WIDTH = CELL_WIDTH * 10;
    private static final int TEXTURE_HEIGHT = CELL_HEIGHT;

    /** Depth pushed above the ambient decorator z — matches the vanilla stack-count text's own peak. */
    private static final float Z_RAISE = 200.0F;

    /** Inset from the icon's top/left edges, in screen pixels. */
    private static final int EDGE_INSET = 1;

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        // Checked at RENDER time, not only at registration, so a config reload (or the master switch)
        // takes effect immediately, and a pre-bake state fails closed to plain vanilla rendering.
        // Dispatch by item: each vessel keeps its own independent gate.
        boolean active = XpVessel.enabled() && WhatRemainsConfig.xpBottleCountBadge;
        if (!active) return false;

        if (XpVessel.getStoredXp(stack) <= 0) return false; // untagged/empty bottles must look exactly vanilla; bottles below level 1 still show "0"

        // XPB-04: the digit defaults to the bottle's own intrinsic level-from-zero figure, but
        // when the marginal gate is on, shows what THIS viewer would actually gain right now —
        // always <= the intrinsic figure (XpVessel#marginalLevelsFor's own guarantee).
        int level = XpVessel.storedLevel(stack);
        boolean marginal = WhatRemainsConfig.xpBottleCountBadgeMarginal;
        if (marginal) {
            LocalPlayer viewer = Minecraft.getInstance().player;
            if (viewer != null) level = XpVessel.marginalLevelsFor(viewer, stack);
        }

        String text = Integer.toString(level);

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(xOffset + EDGE_INSET, yOffset + EDGE_INSET, Z_RAISE);
        for (int index = 0; index < text.length(); index++) {
            int digit = text.charAt(index) - '0';
            guiGraphics.blit(
                    DIGIT_TEXTURE,
                    index * CELL_WIDTH,
                    0,
                    digit * CELL_WIDTH,
                    0,
                    CELL_WIDTH,
                    CELL_HEIGHT,
                    TEXTURE_WIDTH,
                    TEXTURE_HEIGHT);
        }
        pose.popPose();

        return false;
    }
}
