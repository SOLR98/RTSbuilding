package com.rtsbuilding.rtsbuilding.client.screen.workflow;

import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.screen.standalone.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.state.RtsClientUiStateStore;
import com.rtsbuilding.rtsbuilding.client.util.RtsClientUiUtil;
import com.rtsbuilding.rtsbuilding.network.builder.C2SRtsDeleteWorkflowPayload;
import com.rtsbuilding.rtsbuilding.network.builder.C2SRtsPauseWorkflowPayload;
import com.rtsbuilding.rtsbuilding.network.builder.C2SRtsScanBlueprintResumePayload;
import com.rtsbuilding.rtsbuilding.network.builder.C2SRtsScanResumePlacementPayload;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowProgressProcessor;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.rtsbuilding.rtsbuilding.client.screen.standalone.BuilderScreenConstants.TOP_H;

/**
 * A movable window panel showing active workflows, progress bars, delete buttons,
 * and a submit-pending button for suspended placement jobs.
 *
 * <p>Extends {@link RtsWindowPanel} so it integrates with the floating window layer
 * (dragging, z-order, consistent chrome).  The panel auto-sizes to fit content and
 * hides when no workflows or pending jobs exist.</p>
 */
public final class RtsWorkflowPanel extends RtsWindowPanel {

    private static final int PANEL_W = 220;
    private static final int ROW_H = 22;
    private static final int PADDING = 6;
    private static final int BTN_W = 16;
    private static final int BAR_H = 6;
    private static final int FOOTER_H = 18;

    private int cachedVisibleRows = -1;

    public RtsWorkflowPanel() {
    }

    // ======================================================================
    //  RtsWindowPanel abstract methods
    // ======================================================================

    @Override
    protected Component getTitle() {
        return Component.literal("Workflows");
    }

    @Override
    protected int getDefaultWidth() {
        return PANEL_W;
    }

    @Override
    protected int getDefaultHeight() {
        // Estimate: 1 row + padding + title bar + border
        return getTitleBarHeight() + 1 + PADDING + ROW_H + PADDING;
    }

    @Override
    protected void computeDefaultPosition() {
        if (this.screen == null) return;
        this.windowX = Math.max(8, this.screen.width - PANEL_W - 8);
        this.windowY = TOP_H + 8;
    }

    @Override
    protected boolean canShowWindow() {
        if (!RtsClientUiStateStore.isShowWorkflowPanelEnabled()) {
            return false;
        }
        return getActiveCount() > 0 || getSuspendedCount() > 0 || hasPending();
    }

    @Override
    protected boolean shouldClipContent() {
        return false; // No scrollable content, no clipping needed
    }

    @Override
    protected boolean handleContentScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false; // Don't consume scroll events (allow camera zoom through)
    }

    // ======================================================================
    //  Render
    // ======================================================================

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.draggable = true;
        this.resizable = false;
        this.closable = false;
        setOpen(true); // Always available; visibility handled by canShowWindow()
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!this.open || !canShowWindow()) {
            this.mouseHovering = false;
            return;
        }
        recomputeSize();
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * Dynamically resizes the window to fit the visible rows.
     * Called before every render frame.
     */
    private void recomputeSize() {
        int visibleRows = getActiveCount() + getSuspendedCount();
        int totalRows = visibleRows;
        if (totalRows == cachedVisibleRows) return;
        cachedVisibleRows = totalRows;
        int contentH = PADDING + visibleRows * ROW_H + PADDING;
        int totalH = getTitleBarHeight() + 1 + contentH;
        if (hasUserBoundsPreference()) {
            setBounds(this.windowX, this.windowY, PANEL_W, totalH);
        } else {
            computeDefaultPosition();
            setTransientBounds(this.windowX, this.windowY, PANEL_W, totalH);
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int baseX = contentX();
        int baseY = contentY() + PADDING;

        RtsWorkflowStatus[] workflows = this.controller.getWorkflowStatuses();
        // Only iterate slots that the server considers valid (active + suspended), so stale
        // entries from recently completed workflows are not rendered.
        int count = Math.min(getActiveCount(), workflows.length);

        int rowY = baseY;

        // Render all occupied workflow entries (active + suspended)
        for (int i = 0; i < count; i++) {
            RtsWorkflowStatus status = workflows[i];
            if (status == null || !status.isActive()) continue;
            rowY = renderWorkflowRow(g, baseX, rowY, status, mouseX, mouseY);
        }
    }

    // ======================================================================
    //  Row rendering
    // ======================================================================

    private int renderWorkflowRow(GuiGraphics g, int x, int y,
                                   RtsWorkflowStatus status,
                                   int mouseX, int mouseY) {
        Font font = this.screen.font();
        boolean suspended = status.suspended();
        String label = RtsWorkflowProgressProcessor.formatLabel(status);
        String progress = RtsWorkflowProgressProcessor.formatProgressText(status);

        if (suspended) {
            // Suspended workflow: need space for 2 buttons ▶ + ✖
            int btnArea = BTN_W * 2 + 2;
            int rowW = PANEL_W - PADDING * 2 - btnArea - 2;
            boolean hovered = isInside(mouseX, mouseY, x, y, rowW, ROW_H);

            // Amber/warning tint background
            RtsClientUiUtil.drawPanelFrame(g, x, y, rowW, ROW_H,
                    hovered ? 0xAA4A3A1A : 0xAA2A2820,
                    0xFF8A7A4A, 0xFF0D0D0A);
            g.drawString(font, label, x + 4, y + 2, 0xFFE7C46A, false);

            // Dimmed progress bar
            int barX = x + 4;
            int barY = y + 12;
            int barW = rowW - 8;
            int fillW = RtsWorkflowProgressProcessor.computeFillWidth(status, barW);
            g.fill(barX, barY, barX + barW, barY + BAR_H, 0xAA303030);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + BAR_H, 0xAA8A7A3A);
            }
            g.hLine(barX, barX + barW, barY, 0xFF5A4A2A);
            g.hLine(barX, barX + barW, barY + BAR_H, 0xFF0A0A05);
            g.vLine(barX, barY, barY + BAR_H, 0xFF5A4A2A);
            g.vLine(barX + barW, barY, barY + BAR_H, 0xFF0A0A05);
            g.drawString(font, progress, barX + 2, barY + 1, 0xAAFFFFFF, false);

            // Resume button (▶) — rightmost
            int resumeBtnX = x + rowW + 2;
            boolean resumeHovered = isInside(mouseX, mouseY, resumeBtnX, y, BTN_W, ROW_H);
            int resumeBg = resumeHovered ? 0xCC3AA156 : 0xCC2C873F;
            RtsClientUiUtil.drawPanelFrame(g, resumeBtnX, y, BTN_W, ROW_H,
                    resumeBg, 0xFF74E88C, 0xFF123A1D);
            RtsClientUiUtil.drawCenteredStringNoShadow(g, font, "▶",
                    resumeBtnX + BTN_W / 2, y + 4, 0xFFFFFF);

            // Cancel button (✖) — second from right
            int cancelBtnX = resumeBtnX + BTN_W + 2;
            boolean cancelHovered = isInside(mouseX, mouseY, cancelBtnX, y, BTN_W, ROW_H);
            int cancelBg = cancelHovered ? 0xCCB04A4A : 0xAA4A2A2A;
            RtsClientUiUtil.drawPanelFrame(g, cancelBtnX, y, BTN_W, ROW_H,
                    cancelBg, 0xFFC07070, 0xFF1A0D0D);
            RtsClientUiUtil.drawCenteredStringNoShadow(g, font, "✖",
                    cancelBtnX + BTN_W / 2, y + 4, 0xFFFFFF);
        } else {
            // Active workflow: normal display, 2 buttons — ⏸/▶ (pause/resume) + ✖ (delete)
            int btnArea = BTN_W * 2 + 2;
            int rowW = PANEL_W - PADDING * 2 - btnArea - 2;
            boolean hovered = isInside(mouseX, mouseY, x, y, rowW, ROW_H);

            RtsClientUiUtil.drawPanelFrame(g, x, y, rowW, ROW_H,
                    hovered ? 0xAA2A3A4A : 0xAA1A222C,
                    0xFF5E738A, 0xFF0D1117);
            g.drawString(font, label, x + 4, y + 2, 0xEAF2FF, false);

            // Progress bar
            int barX = x + 4;
            int barY = y + 12;
            int barW = rowW - 8;
            int fillW = RtsWorkflowProgressProcessor.computeFillWidth(status, barW);
            g.fill(barX, barY, barX + barW, barY + BAR_H, 0xAA202832);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + BAR_H, 0xFF88BEF4);
            }
            g.hLine(barX, barX + barW, barY, 0xFF405064);
            g.hLine(barX, barX + barW, barY + BAR_H, 0xFF0A0D12);
            g.vLine(barX, barY, barY + BAR_H, 0xFF405064);
            g.vLine(barX + barW, barY, barY + BAR_H, 0xFF0A0D12);

            // Progress text overlay
            g.drawString(font, progress, barX + 2, barY + 1, 0xCCFFFFFF, false);

            boolean isPaused = status.paused();

            // Pause/Resume button (⏸/▶) — rightmost
            int pauseBtnX = x + rowW + 2;
            boolean pauseHovered = isInside(mouseX, mouseY, pauseBtnX, y, BTN_W, ROW_H);
            int pauseBg;
            int pauseBorder;
            if (isPaused) {
                // Resume — green
                pauseBg = pauseHovered ? 0xCC3AA156 : 0xCC2C873F;
                pauseBorder = 0xFF74E88C;
            } else {
                // Pause — amber
                pauseBg = pauseHovered ? 0xCCA07A2A : 0xCC705A1A;
                pauseBorder = 0xFFE7C46A;
            }
            RtsClientUiUtil.drawPanelFrame(g, pauseBtnX, y, BTN_W, ROW_H,
                    pauseBg, pauseBorder, 0xFF1A2A1A);
            RtsClientUiUtil.drawCenteredStringNoShadow(g, font, isPaused ? "▶" : "⏸",
                    pauseBtnX + BTN_W / 2, y + 4, 0xFFFFFF);

            // Delete button (✖) — second from right
            int deleteBtnX = pauseBtnX + BTN_W + 2;
            boolean deleteHovered = isInside(mouseX, mouseY, deleteBtnX, y, BTN_W, ROW_H);
            int deleteBg = deleteHovered ? 0xCCB04A4A : 0xAA4A2A2A;
            RtsClientUiUtil.drawPanelFrame(g, deleteBtnX, y, BTN_W, ROW_H,
                    deleteBg, 0xFFC07070, 0xFF1A0D0D);
            RtsClientUiUtil.drawCenteredStringNoShadow(g, font, "✖",
                    deleteBtnX + BTN_W / 2, y + 4, 0xFFFFFF);
        }

        return y + ROW_H;
    }

    // ======================================================================
    //  Click handling
    // ======================================================================

    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        if (button != 0) return; // Left click only

        RtsWorkflowStatus[] workflows = this.controller.getWorkflowStatuses();
        // Only iterate slots within the active count to avoid responding to stale entries.
        int count = Math.min(getActiveCount(), workflows.length);

        int baseX = contentX();
        int rowY = contentY() + PADDING;

        // Check buttons for each workflow row
        for (int i = 0; i < count; i++) {
            RtsWorkflowStatus status = workflows[i];
            if (status == null || status.type() == null) continue;

            if (status.suspended()) {
                // Suspended workflow: 2 buttons — ▶ (resume) at rightmost, ✖ (cancel) next to it
                int btnArea = BTN_W * 2 + 2;
                int rowW = PANEL_W - PADDING * 2 - btnArea - 2;
                int resumeBtnX = baseX + rowW + 2;
                int cancelBtnX = resumeBtnX + BTN_W + 2;
                if (isInside(mouseX, mouseY, resumeBtnX, rowY, BTN_W, ROW_H)) {
                    // ▶ Resume
                    if (status.type() == RtsWorkflowType.BLUEPRINT_BUILD) {
                        // 蓝图：扫描剩余材料需求，弹出材料清单面板
                        PacketDistributor.sendToServer(new C2SRtsScanBlueprintResumePayload(status.entryId()));
                    } else {
                        // 范围放置：先扫描，再打开重启面板
                        PacketDistributor.sendToServer(new C2SRtsScanResumePlacementPayload(status.entryId()));
                    }
                    return;
                }
                if (isInside(mouseX, mouseY, cancelBtnX, rowY, BTN_W, ROW_H)) {
                    // ✖ Cancel (delete) this workflow — 用 entryId 而非位置索引
                    PacketDistributor.sendToServer(new C2SRtsDeleteWorkflowPayload(status.entryId()));
                    return;
                }
            } else {
                // Active workflow: 2 buttons — ⏸/▶ (pause/resume) + ✖ (delete)
                int btnArea = BTN_W * 2 + 2;
                int rowW = PANEL_W - PADDING * 2 - btnArea - 2;
                int pauseBtnX = baseX + rowW + 2;
                int deleteBtnX = pauseBtnX + BTN_W + 2;
                if (isInside(mouseX, mouseY, pauseBtnX, rowY, BTN_W, ROW_H)) {
                    PacketDistributor.sendToServer(new C2SRtsPauseWorkflowPayload(status.entryId()));
                    return;
                }
                if (isInside(mouseX, mouseY, deleteBtnX, rowY, BTN_W, ROW_H)) {
                    PacketDistributor.sendToServer(new C2SRtsDeleteWorkflowPayload(status.entryId()));
                    return;
                }
            }

            rowY += ROW_H;
        }


    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    private int getActiveCount() {
        return this.controller.getWorkflowActiveCount();
    }

    private int getSuspendedCount() {
        RtsWorkflowStatus[] workflows = this.controller.getWorkflowStatuses();
        int limit = Math.min(getActiveCount(), workflows.length);
        int count = 0;
        for (int i = 0; i < limit; i++) {
            RtsWorkflowStatus s = workflows[i];
            if (s != null && s.type() != null && s.suspended()) count++;
        }
        return count;
    }

    private boolean hasPending() {
        return this.controller.hasPendingJobs();
    }

    private static boolean isInside(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
