package com.temog.notminelegacy.whatremains.client;

import com.temog.notminelegacy.xp.XpMath;
import com.temog.notminelegacy.compat.ModCompat;
import com.temog.notminelegacy.whatremains.compat.WRCuriosCompat;
import com.temog.notminelegacy.whatremains.entity.CorpseEntity;
import com.temog.notminelegacy.whatremains.menu.CorpseMenu;
import com.temog.notminelegacy.whatremains.xp.XpVessel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side GUI screen for the corpse container (176×222).
 * <p>
 * Layout matches corpse_container.png exactly:
 * <ul>
 *   <li>Top-left: entity preview (black inset 48×70)</li>
 *   <li>Right of preview: vertical armor column + offhand</li>
 *   <li>Top-right area: general corpse slots (irregular 4+4+5+5 grid)</li>
 *   <li>Middle: 2×9 general corpse rows</li>
 *   <li>Bottom: 3×9 player inventory + 1×9 hotbar</li>
 *   <li>Right icons: Curios toggle + Aether toggle</li>
 *   <li>Left panel (toggle): accessory slots for active system</li>
 * </ul>
 */
public class CorpseMenuScreen extends AbstractContainerScreen<CorpseMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation CONTAINER_TEXTURE =
            ResourceLocation.parse("whatremains:textures/gui/corpse_container.png");

    // ── Preview box (entity render area, black interior in texture) ─────────
    private static final int PREVIEW_LEFT   = 8;
    private static final int PREVIEW_TOP    = 16;
    private static final int PREVIEW_RIGHT  = 55;
    private static final int PREVIEW_BOTTOM = 85;

    // ── Stored-XP mini bar (blank strip above the general-slot grid) ────────
    // Real vanilla icons.png bar strip (UV 0,64/0,69, 182×5), cropped to a
    // shortened width with the corner-clip rounding preserved on both ends.
    private static final ResourceLocation XP_BAR_EMPTY_TEXTURE =
            ResourceLocation.parse("whatremains:textures/gui/xp_bar_mini_empty.png");
    private static final ResourceLocation XP_BAR_FILLED_TEXTURE =
            ResourceLocation.parse("whatremains:textures/gui/xp_bar_mini_filled.png");
    private static final int XP_BAR_W = 48;
    private static final int XP_BAR_H = 5;
    private static final int XP_BAR_Y = 7;
    private static final int XP_NUM_Y = 6;
    private static final int XP_GAP_BEFORE_NUM  = 3;
    // Fixed, uniform position (independent of corpse name length): the level
    // number's LEFT edge is anchored so a 2-digit level's right edge lands
    // exactly on the general-slot grid's own right edge (row A's last slot,
    // x=152, ends at x=170). A 1-digit level therefore falls short of that
    // edge (expected); a 3+ digit level right-aligns flush to it instead of
    // overflowing.
    private static final int XP_SLOT_GRID_RIGHT_EDGE = 170;
    private static final int XP_NUM_COLOR = 0xFF80FF20;

    // ── Button icon UV coordinates in texture atlas ─────────────────────────
    // Curios button: 10×10 raised-bevel ring icon
    private static final int CURIOS_BTN_U_OFF = 176;
    private static final int CURIOS_BTN_V_OFF = 1;
    private static final int CURIOS_BTN_U_ON  = 176;
    private static final int CURIOS_BTN_V_ON  = 15;
    private static final int CURIOS_BTN_W     = 10;
    private static final int CURIOS_BTN_H     = 10;

    // Aether button: 12×8 cloud icon
    private static final int AETHER_BTN_U_OFF = 176;
    private static final int AETHER_BTN_V_OFF = 28;
    private static final int AETHER_BTN_U_ON  = 176;
    private static final int AETHER_BTN_V_ON  = 36;
    private static final int AETHER_BTN_W     = 12;
    private static final int AETHER_BTN_H     = 8;

    // Button render positions (GUI-relative, inside left side of preview box)
    private static final int CURIOS_BTN_X = 9;
    private static final int CURIOS_BTN_Y = 17;
    private static final int AETHER_BTN_X = 9;
    private static final int AETHER_BTN_Y = 29;

    // ── Accessory side panel (extends LEFT of main GUI) ─────────────────────
    private static final int PANEL_WIDTH    = 25;
    private static final int PANEL_GAP      = 2;
    private static final int PANEL_X        = -(PANEL_WIDTH + PANEL_GAP);  // -27
    private static final int PANEL_TOP      = 4;
    private static final int SLOT_IN_PANEL_X = PANEL_X + 4;  // -23
    private static final int MAX_VISIBLE_ROWS = 11;
    private static final int PAGE_HEADER_H  = 14;

    // ── Vanilla GUI colours ─────────────────────────────────────────────────
    private static final int COL_BG     = 0xFFC6C6C6;
    private static final int COL_WHITE  = 0xFFFFFFFF;
    private static final int COL_DARK   = 0xFF373737;
    private static final int COL_SLOT   = 0xFF8B8B8B;
    private static final int COL_BLACK  = 0xFF000000;
    private static final int COL_FRAME_SHADOW = 0xFF555555;

    // ── Panel state ─────────────────────────────────────────────────────────
    /** Which accessory panel is open: NONE, CURIOS, AETHER. */
    private enum PanelMode { NONE, CURIOS, AETHER }
    private PanelMode activePanel = PanelMode.NONE;
    private int panelPage = 0;

    // ── Cached slot index lists for Curios vs Aether filtering ──────────────
    private List<Integer> curiosOnlyIndices = new ArrayList<>();
    private List<Integer> aetherOnlyIndices = new ArrayList<>();

    public CorpseMenuScreen(CorpseMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = this.imageHeight - 94;  // 128
    }

    @Override
    protected void init() {
        super.init();
        // Left-aligned corpse title
        this.titleLabelX = 8;
        buildAccessoryIndices();
        repositionAccessorySlots();

        LOGGER.debug("[WhatRemains] CorpseMenuScreen init: curiosLoaded={}, aetherLoaded={}, curiosCount={}, curiosOnly={}, aetherOnly={}",
                ModCompat.isCuriosLoaded(), ModCompat.isAetherLoaded(),
                this.menu.getCuriosCount(), curiosOnlyIndices.size(), aetherOnlyIndices.size());
    }

    // ── Accessory index partitioning ────────────────────────────────────────

    /** Partitions curios slots into Aether-specific and regular Curios groups. */
    private void buildAccessoryIndices() {
        curiosOnlyIndices.clear();
        aetherOnlyIndices.clear();

        int curiosCount = this.menu.getCuriosCount();
        if (curiosCount == 0) return;

        CorpseEntity corpse = this.menu.getCorpse();
        if (corpse == null) return;

        for (int i = 0; i < curiosCount; i++) {
            String type = corpse.getCuriosSlotType(i);
            if (isAetherSlotType(type)) {
                aetherOnlyIndices.add(i);
            } else {
                curiosOnlyIndices.add(i);
            }
        }

        LOGGER.debug("[WhatRemains] Accessory partition: {} curios-only, {} aether-only",
                curiosOnlyIndices.size(), aetherOnlyIndices.size());
        for (int i = 0; i < curiosCount; i++) {
            String type = corpse.getCuriosSlotType(i);
            LOGGER.debug("[WhatRemains]   slot[{}] type='{}' -> {}",
                    i, type, isAetherSlotType(type) ? "AETHER" : "CURIOS");
        }
    }

    /** Returns true if the curios slot type belongs to the Aether mod. */
    private static boolean isAetherSlotType(String type) {
        if (type == null || type.isEmpty()) return false;
        return type.startsWith("aether_");
    }

    /** Gets the active index list for the current panel mode. */
    private List<Integer> getActivePanelIndices() {
        return switch (activePanel) {
            case CURIOS -> curiosOnlyIndices;
            case AETHER -> aetherOnlyIndices;
            default -> List.of();
        };
    }

    // ── Mod detection helpers ───────────────────────────────────────────────

    private boolean hasCurios() {
        return ModCompat.isCuriosLoaded() && !curiosOnlyIndices.isEmpty();
    }

    private boolean hasAether() {
        return ModCompat.isAetherLoaded() && ModCompat.isCuriosLoaded() && !aetherOnlyIndices.isEmpty();
    }

    // ── Panel pagination helpers ────────────────────────────────────────────

    private int getActivePanelSize() {
        return getActivePanelIndices().size();
    }

    private int getMaxPage() {
        int count = getActivePanelSize();
        return count <= 0 ? 0 : (count - 1) / MAX_VISIBLE_ROWS;
    }

    private boolean needsPagination() {
        return getActivePanelSize() > MAX_VISIBLE_ROWS;
    }

    private int getVisibleRowsOnPage() {
        int count = getActivePanelSize();
        int start = panelPage * MAX_VISIBLE_ROWS;
        return Math.min(count - start, MAX_VISIBLE_ROWS);
    }

    private int getFirstSlotY() {
        return PANEL_TOP + 4 + (needsPagination() ? PAGE_HEADER_H : 0);
    }

    private int getPanelHeight() {
        return 8 + (needsPagination() ? PAGE_HEADER_H : 0) + getVisibleRowsOnPage() * 18;
    }

    // ── Slot repositioning ──────────────────────────────────────────────────

    private void repositionAccessorySlots() {
        int curiosCount = this.menu.getCuriosCount();
        if (curiosCount == 0) return;

        int startIndex = this.menu.getCuriosMenuStart();
        int firstSlotY = getFirstSlotY();
        List<Integer> activeIndices = getActivePanelIndices();

        for (int i = 0; i < curiosCount; i++) {
            Slot slot = this.menu.slots.get(startIndex + i);
            // Check if this slot is in the active panel's visible page
            int posInActive = activeIndices.indexOf(i);
            if (activePanel != PanelMode.NONE && posInActive >= 0) {
                int page = posInActive / MAX_VISIBLE_ROWS;
                int rowOnPage = posInActive % MAX_VISIBLE_ROWS;
                if (page == panelPage) {
                    slot.x = SLOT_IN_PANEL_X;
                    slot.y = firstSlotY + rowOnPage * 18;
                } else {
                    slot.x = -999;
                    slot.y = -999;
                }
            } else {
                slot.x = -999;
                slot.y = -999;
            }
        }
    }

    // ── Mouse interaction ───────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Curios toggle button
            if (hasCurios()) {
                int bx = this.leftPos + CURIOS_BTN_X;
                int by = this.topPos + CURIOS_BTN_Y;
                if (mouseX >= bx && mouseX < bx + CURIOS_BTN_W
                        && mouseY >= by && mouseY < by + CURIOS_BTN_H) {
                    if (activePanel == PanelMode.CURIOS) {
                        activePanel = PanelMode.NONE;
                        LOGGER.debug("[WhatRemains] Curios panel CLOSED");
                    } else {
                        activePanel = PanelMode.CURIOS;
                        LOGGER.debug("[WhatRemains] Curios panel OPENED ({} slots)", curiosOnlyIndices.size());
                    }
                    panelPage = 0;
                    repositionAccessorySlots();
                    return true;
                }
            }

            // Aether toggle button
            if (hasAether()) {
                int bx = this.leftPos + AETHER_BTN_X;
                int by = this.topPos + AETHER_BTN_Y;
                if (mouseX >= bx && mouseX < bx + AETHER_BTN_W
                        && mouseY >= by && mouseY < by + AETHER_BTN_H) {
                    if (activePanel == PanelMode.AETHER) {
                        activePanel = PanelMode.NONE;
                        LOGGER.debug("[WhatRemains] Aether panel CLOSED");
                    } else {
                        activePanel = PanelMode.AETHER;
                        LOGGER.debug("[WhatRemains] Aether panel OPENED ({} slots)", aetherOnlyIndices.size());
                    }
                    panelPage = 0;
                    repositionAccessorySlots();
                    return true;
                }
            }

            // Page arrows (when panel open and paginated)
            if (activePanel != PanelMode.NONE && needsPagination()) {
                int arrowY = this.topPos + PANEL_TOP + 4;
                int panelCx = this.leftPos + PANEL_X + PANEL_WIDTH / 2;
                // Up arrow (prev page)
                if (mouseX >= panelCx - 8 && mouseX < panelCx - 1
                        && mouseY >= arrowY && mouseY < arrowY + 7) {
                    if (panelPage > 0) {
                        panelPage--;
                        repositionAccessorySlots();
                    }
                    return true;
                }
                // Down arrow (next page)
                if (mouseX >= panelCx + 1 && mouseX < panelCx + 8
                        && mouseY >= arrowY && mouseY < arrowY + 7) {
                    if (panelPage < getMaxPage()) {
                        panelPage++;
                        repositionAccessorySlots();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activePanel != PanelMode.NONE && needsPagination()) {
            int px = this.leftPos + PANEL_X;
            int py = this.topPos + PANEL_TOP;
            int ph = getPanelHeight();
            if (mouseX >= px && mouseX < px + PANEL_WIDTH
                    && mouseY >= py && mouseY < py + ph) {
                if (delta > 0 && panelPage > 0) {
                    panelPage--;
                    repositionAccessorySlots();
                    return true;
                } else if (delta < 0 && panelPage < getMaxPage()) {
                    panelPage++;
                    repositionAccessorySlots();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;

        // Main GUI texture (176×222 from UV 0,0)
        g.blit(CONTAINER_TEXTURE, x, y, 0, 0, this.imageWidth, this.imageHeight);

        // Entity preview
        renderCorpsePreview(g, mouseX, mouseY);

        // Stored-XP mini bar + level number (right of the title text)
        renderStoredXpBar(g);

        // Curios button icon (right of frame)
        if (hasCurios()) {
            boolean on = activePanel == PanelMode.CURIOS;
            int u = on ? CURIOS_BTN_U_ON : CURIOS_BTN_U_OFF;
            int v = on ? CURIOS_BTN_V_ON : CURIOS_BTN_V_OFF;
            g.blit(CONTAINER_TEXTURE, x + CURIOS_BTN_X, y + CURIOS_BTN_Y,
                    u, v, CURIOS_BTN_W, CURIOS_BTN_H);
        }

        // Aether button icon (right of frame, below Curios)
        if (hasAether()) {
            boolean on = activePanel == PanelMode.AETHER;
            int u = on ? AETHER_BTN_U_ON : AETHER_BTN_U_OFF;
            int v = on ? AETHER_BTN_V_ON : AETHER_BTN_V_OFF;
            g.blit(CONTAINER_TEXTURE, x + AETHER_BTN_X, y + AETHER_BTN_Y,
                    u, v, AETHER_BTN_W, AETHER_BTN_H);
        }

        // Accessory side panel (left of GUI)
        if (activePanel != PanelMode.NONE && getActivePanelSize() > 0) {
            renderAccessoryPanel(g, mouseX, mouseY);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
        renderLockedSlots(g);
    }

    // ── Accessory side panel ────────────────────────────────────────────────

    private void renderAccessoryPanel(GuiGraphics g, int mouseX, int mouseY) {
        int px = this.leftPos + PANEL_X;
        int py = this.topPos + PANEL_TOP;
        int ph = getPanelHeight();

        // Panel bevel frame
        drawBevelFrame(g, px, py, PANEL_WIDTH, ph);

        // Page arrows (if paginated)
        if (needsPagination()) {
            int arrowY = py + 4;
            int panelCx = px + PANEL_WIDTH / 2;
            boolean upHov = mouseX >= panelCx - 8 && mouseX < panelCx - 1
                    && mouseY >= arrowY && mouseY < arrowY + 7;
            boolean downHov = mouseX >= panelCx + 1 && mouseX < panelCx + 8
                    && mouseY >= arrowY && mouseY < arrowY + 7;
            drawUpArrow(g, panelCx - 8, arrowY, panelPage > 0, upHov);
            drawDownArrow(g, panelCx + 1, arrowY, panelPage < getMaxPage(), downHov);
        }

        // Slot frames for visible accessories on current page
        int startIndex = this.menu.getCuriosMenuStart();
        List<Integer> activeIndices = getActivePanelIndices();
        int pageStart = panelPage * MAX_VISIBLE_ROWS;
        int visibleRows = getVisibleRowsOnPage();

        for (int i = 0; i < visibleRows; i++) {
            int globalIdx = pageStart + i;
            if (globalIdx >= activeIndices.size()) break;
            int curiosIndex = activeIndices.get(globalIdx);
            Slot slot = this.menu.slots.get(startIndex + curiosIndex);
            drawSlotFrame(g, this.leftPos + slot.x - 1, this.topPos + slot.y - 1);
        }
    }

    // ── Drawing helpers ─────────────────────────────────────────────────────

    private void drawBevelFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, COL_BG);
        g.fill(x, y, x + w, y + 1, COL_BLACK);
        g.fill(x, y + h - 1, x + w, y + h, COL_BLACK);
        g.fill(x, y + 1, x + 1, y + h - 1, COL_BLACK);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, COL_BLACK);
        g.fill(x + 1, y + 2, x + 3, y + h - 3, COL_WHITE);
        g.fill(x + 2, y + 1, x + w - 3, y + 3, COL_WHITE);
        g.fill(x + 3, y + 3, x + 4, y + 4, COL_WHITE);
        g.fill(x + w - 3, y + 3, x + w - 1, y + h - 3, COL_FRAME_SHADOW);
        g.fill(x + 3, y + h - 3, x + w - 1, y + h - 1, COL_FRAME_SHADOW);
        g.fill(x + w - 4, y + h - 4, x + w - 3, y + h - 3, COL_FRAME_SHADOW);
        g.fill(x + 1, y + 1, x + 2, y + 2, COL_BLACK);
        g.fill(x + w - 3, y + 1, x + w - 1, y + 2, COL_BLACK);
        g.fill(x + w - 2, y + 2, x + w - 1, y + 3, COL_BLACK);
        g.fill(x + 1, y + h - 3, x + 2, y + h - 1, COL_BLACK);
        g.fill(x + 2, y + h - 2, x + 3, y + h - 1, COL_BLACK);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, COL_BLACK);
    }

    private void drawSlotFrame(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, COL_SLOT);
        g.fill(x, y, x + 17, y + 1, COL_DARK);
        g.fill(x, y + 1, x + 1, y + 17, COL_DARK);
        g.fill(x + 1, y + 17, x + 18, y + 18, COL_WHITE);
        g.fill(x + 17, y + 1, x + 18, y + 17, COL_WHITE);
    }

    private void drawUpArrow(GuiGraphics g, int x, int y, boolean active, boolean hovered) {
        int c = !active ? 0xFF555555 : hovered ? COL_WHITE : 0xFFA0A0A0;
        g.fill(x + 3, y,     x + 4, y + 1, c);
        g.fill(x + 2, y + 1, x + 5, y + 2, c);
        g.fill(x + 1, y + 2, x + 6, y + 3, c);
        g.fill(x,     y + 3, x + 7, y + 4, c);
    }

    private void drawDownArrow(GuiGraphics g, int x, int y, boolean active, boolean hovered) {
        int c = !active ? 0xFF555555 : hovered ? COL_WHITE : 0xFFA0A0A0;
        g.fill(x,     y,     x + 7, y + 1, c);
        g.fill(x + 1, y + 1, x + 6, y + 2, c);
        g.fill(x + 2, y + 2, x + 5, y + 3, c);
        g.fill(x + 3, y + 3, x + 4, y + 4, c);
    }

    // ── Locked-slot overlays ────────────────────────────────────────────────

    private void renderLockedSlots(GuiGraphics g) {
        CorpseEntity corpse = this.menu.getCorpse();
        if (corpse == null) return;

        int generalMenuStart = CorpseEntity.ARMOR_SLOTS + 1; // after armor + offhand
        for (int i = 0; i < CorpseEntity.GENERAL_SLOTS; i++) {
            int containerIdx = CorpseEntity.GENERAL_START + i;
            if (!corpse.isSlotActive(containerIdx)) {
                Slot slot = this.menu.slots.get(generalMenuStart + i);
                int sx = this.leftPos + slot.x;
                int sy = this.topPos + slot.y;
                g.fill(sx, sy, sx + 16, sy + 16, 0xC0101010);
            }
        }
    }

    // ── Entity preview ──────────────────────────────────────────────────────

    private void renderCorpsePreview(GuiGraphics g, int mouseX, int mouseY) {
        CorpseEntity corpse = this.menu.getCorpse();
        if (corpse == null) return;

        int previewCenterX = this.leftPos + (PREVIEW_LEFT + PREVIEW_RIGHT) / 2;
        int previewBottomY = this.topPos + PREVIEW_BOTTOM - 2;
        int scale = 30;

        corpse.renderUpright = true;
        InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, previewCenterX, previewBottomY, scale,
                (float) (previewCenterX - mouseX),
                (float) (previewBottomY - 25 - mouseY),
                corpse);
        corpse.renderUpright = false;
    }

    // ── Stored-XP mini bar ──────────────────────────────────────────────────

    /**
     * Small vanilla-styled XP bar + level number, showing the corpse's own
     * stored XP (independent of any bottle). Hidden when nothing is stored.
     */
    private void renderStoredXpBar(GuiGraphics g) {
        CorpseEntity corpse = this.menu.getCorpse();
        if (corpse == null) return;

        int stored = corpse.getStoredXp();
        if (stored <= 0) return;

        XpMath.LevelAndProgress lp = XpMath.levelAndProgressForExperience(stored);

        // Reserve enough width for a 2-digit level so the bar's own position
        // stays fixed regardless of the actual level's digit count.
        int twoDigitReserve = this.font.width("88");
        int barX = XP_SLOT_GRID_RIGHT_EDGE - twoDigitReserve - XP_GAP_BEFORE_NUM - XP_BAR_W;

        int x = this.leftPos + barX;
        int y = this.topPos + XP_BAR_Y;

        g.blit(XP_BAR_EMPTY_TEXTURE, x, y, 0, 0, XP_BAR_W, XP_BAR_H, XP_BAR_W, XP_BAR_H);
        int fillW = Math.round(XP_BAR_W * lp.progress());
        if (fillW > 0) {
            g.blit(XP_BAR_FILLED_TEXTURE, x, y, 0, 0, fillW, XP_BAR_H, XP_BAR_W, XP_BAR_H);
        }

        String numStr = String.valueOf(lp.level());
        int numW = this.font.width(numStr);
        int numGuiX = (numW <= twoDigitReserve)
                ? barX + XP_BAR_W + XP_GAP_BEFORE_NUM
                : XP_SLOT_GRID_RIGHT_EDGE - numW;
        int numX = this.leftPos + numGuiX;
        int numY = this.topPos + XP_NUM_Y;
        g.drawString(this.font, numStr, numX - 1, numY, COL_BLACK, false);
        g.drawString(this.font, numStr, numX + 1, numY, COL_BLACK, false);
        g.drawString(this.font, numStr, numX, numY - 1, COL_BLACK, false);
        g.drawString(this.font, numStr, numX, numY + 1, COL_BLACK, false);
        g.drawString(this.font, numStr, numX, numY, XP_NUM_COLOR, false);
    }
}
