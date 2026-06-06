package com.rtsbuilding.rtsbuilding.client.screen.quickbuild;

import com.rtsbuilding.rtsbuilding.client.screen.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.quickbuild.BuildShape;
import com.rtsbuilding.rtsbuilding.client.screen.quickbuild.QuickBuildMode;
import com.rtsbuilding.rtsbuilding.client.screen.quickbuild.ShapeFillMode;
import com.rtsbuilding.rtsbuilding.client.screen.layout.PanelLayouts;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.screen.shape.ShapeGeometryUtil;
import com.rtsbuilding.rtsbuilding.client.screen.ultimine.AreaMineShape;
import com.rtsbuilding.rtsbuilding.client.util.RtsTextureRenderer;
import com.rtsbuilding.rtsbuilding.client.widget.WindowButton;
import com.rtsbuilding.rtsbuilding.progression.RtsProgressionNodes;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.util.List;

import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.*;

/**
 * 快速建造面板：形状选择 + 填充模式 + 旋转控制。
 * <p>
 * 继承 {@link RtsWindowPanel} 获得窗口能力。
 * 向后兼容 {@code isQuickBuildOpen() / setQuickBuildOpen() / toggleOpen()}。
 */
public final class QuickBuildPanel extends RtsWindowPanel {

    /** 右侧列（填充/旋转）相对于窗口左边缘的偏移 */
    private static final int RIGHT_COL_X = 88;

    /** 形状按钮行间距 */
    private static final int SHAPE_ROW_PITCH = QUICK_BUILD_SHAPE_SLOT + 6;
    private static final int MODE_TOGGLE_H = 18;
    private static final int MODE_TOGGLE_GAP = 4;
    private static final int MODE_ROW_TOP = 5;
    private static final int SECTION_TOP = 31;

    // ======================== 面板尺寸 ========================
    private static final int QUICK_BUILD_PANEL_W = 178;
    private static final int QUICK_BUILD_PANEL_H = 184;
    private static final int QUICK_BUILD_PANEL_MIN_H = 184;

    /** 底部提示文字区域额外高度 */
    private static final int BOTTOM_INFO_H = 30;

    /** 选择指示器贴图 */
    private static final ResourceLocation SELECTION_DOT_TEXTURE =
            ResourceLocation.tryParse("rtsbuilding:textures/gui/general/mode_button.png");

    // ======================== 精灵图参数 ========================
    private static final int SHEET_W = 450;
    private static final int SHEET_H = 900;
    private static final int STATE_H = 450;

    /** 模式按钮贴图：450×1350，3 行状态，每行 450px */
    private static final int MODE_BUTTON_H = STATE_H * 3;

    // ======================== 形状定义 ========================
    private static final BuildShape[] SHAPES = {
            BuildShape.BLOCK,
            BuildShape.LINE,
            BuildShape.SQUARE,
            BuildShape.WALL,
            BuildShape.CIRCLE,
            BuildShape.BOX
    };

    /** 各形状按钮对应的悬浮提示翻译键 */
    private static final String[] SHAPE_TOOLTIP_KEYS = {
            "screen.rtsbuilding.tooltip.shape_block",
            "screen.rtsbuilding.tooltip.shape_line",
            "screen.rtsbuilding.tooltip.shape_square",
            "screen.rtsbuilding.tooltip.shape_wall",
            "screen.rtsbuilding.tooltip.shape_circle",
            "screen.rtsbuilding.tooltip.shape_box"
    };

    /** 各形状按钮对应的精灵图纹理 */
    private static final ResourceLocation[] SHAPE_TEXTURES = {
            QUICK_BUILD_SINGLE_BLOCK,
            QUICK_BUILD_LINE_BLOCK,
            QUICK_BUILD_SQUARE_BLOCK,
            QUICK_BUILD_WALL_BLOCK,
            QUICK_BUILD_CIRCLE_BLOCK,
            QUICK_BUILD_BOX_BLOCK
    };

    // ======================== 实例 ========================
    private WindowButton[] shapeButtons;
    private WindowButton[] fillModeButtons;
    private QuickBuildMode quickBuildMode = QuickBuildMode.BUILD;

    /** 缓存的形状，用于检测 fill mode 是否需要重建 */
    private BuildShape lastFillShape;

    // ======================== 初始化 ========================

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.open = true;
        this.resizable = false;
        createShapeButtons();
        this.lastFillShape = controller.getBuildShape();
        syncAreaMineShapeFromBuildShape();
    }

    private void createShapeButtons() {
        shapeButtons = new WindowButton[SHAPES.length];
        for (int i = 0; i < SHAPES.length; i++) {
            shapeButtons[i] = createShapeButton(i);
        }
    }

    /**
     * 创建指定索引的形状按钮，使用 WindowButton 内置纹理渲染。
     * 选中状态：始终显示下半（active）贴图；未选中：上半（inactive），悬停时切换至下半。
     */
    private WindowButton createShapeButton(int index) {
        boolean selected = controller.getBuildShape() == SHAPES[index];
        int normalV = selected ? STATE_H : 0;
        return new WindowButton(0, 0,
                QUICK_BUILD_SHAPE_SLOT, QUICK_BUILD_SHAPE_SLOT,
                Component.empty(),
                SHAPE_TEXTURES[index],
                0, normalV,
                SHEET_W, STATE_H,
                STATE_H, STATE_H,
                SHEET_W, SHEET_H,
                btn -> {
                    this.controller.setBuildShape(SHAPES[index]);
                    syncAreaMineShapeFromBuildShape();
                    screen.ensureFillModeForShape(SHAPES[index]);
                    screen.clearShapeBuildSession();
                    this.controller.clearAreaMineSession();
                    screen.persistUiState();
                    rebuildFillModeButtons();
                    rebuildAllShapeButtons();
                });
    }

    /** 当形状切换时刷新所有按钮贴图（选中/未选中状态）。 */
    private void rebuildAllShapeButtons() {
        for (int i = 0; i < shapeButtons.length; i++) {
            shapeButtons[i] = createShapeButton(i);
        }
    }

    private void rebuildFillModeButtons() {
        this.lastFillShape = controller.getBuildShape();
        List<ShapeFillMode> modes =
                ShapeGeometryUtil.availableFillModes(controller.getBuildShape());
        fillModeButtons = new WindowButton[modes.size()];
        for (int i = 0; i < modes.size(); i++) {
            int idx = i;
            fillModeButtons[i] = new WindowButton(0, 0, 84, 20,
                    Component.literal(screen.fillModeLabel(modes.get(i))), btn -> {
                screen.setShapeFillMode(modes.get(idx));
                screen.persistUiState();
            });
        }
    }

    // ======================== 渲染 ========================

    /**
     * 动态调整窗口高度：底部信息显示时增加 {@value #BOTTOM_INFO_H}px。
     */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.windowHeight = QUICK_BUILD_PANEL_H + (shouldShowBottomInfo() ? BOTTOM_INFO_H : 0);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderOverlays(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.open || !canShowWindow()) return;
        renderShapeTooltip(g, mouseX, mouseY);
    }

    private void renderShapeTooltip(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < shapeButtons.length; i++) {
            WindowButton btn = shapeButtons[i];
            if (mouseX >= btn.getX() && mouseX < btn.getX() + btn.getWidth()
                    && mouseY >= btn.getY() && mouseY < btn.getY() + btn.getHeight()) {
                g.renderTooltip(screen.font(), Component.translatable(SHAPE_TOOLTIP_KEYS[i]), mouseX, mouseY);
                break;
            }
        }
    }

    private void renderModeToggles(GuiGraphics g, int mouseX, int mouseY) {
        int bodyY = contentY();
        int totalW = this.windowWidth - 16;
        int buttonW = (totalW - MODE_TOGGLE_GAP) / 2;
        int buildX = this.windowX + 8;
        int destroyX = buildX + buttonW + MODE_TOGGLE_GAP;
        int y = bodyY + MODE_ROW_TOP;
        renderModeToggle(g, buildX, y, buttonW, QuickBuildMode.BUILD,
                Component.translatable("screen.rtsbuilding.quick_build.mode_build"), mouseX, mouseY);
        renderModeToggle(g, destroyX, y, buttonW, QuickBuildMode.DESTROY,
                Component.translatable("screen.rtsbuilding.quick_build.mode_destroy"), mouseX, mouseY);
    }

    private void renderModeToggle(GuiGraphics g, int x, int y, int w, QuickBuildMode mode,
            Component label, int mouseX, int mouseY) {
        boolean active = this.quickBuildMode == mode;
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + MODE_TOGGLE_H;
        int border = active ? 0xFF5FE36C : (hovered ? 0xFF7B91A6 : 0xFF647B92);
        int bg = active ? 0xFF29583E : (hovered ? 0xFF223040 : 0xFF141C26);
        g.fill(x, y, x + w, y + MODE_TOGGLE_H, border);
        g.fill(x + 1, y + 1, x + w - 1, y + MODE_TOGGLE_H - 1, bg);
        int labelX = x + Math.max(2, (w - screen.font().width(label)) / 2);
        int labelY = y + (MODE_TOGGLE_H - screen.font().lineHeight) / 2;
        g.drawString(screen.font(), label, labelX, labelY, active ? 0xFFD8FFE0 : 0xFFD8E3EE, false);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.windowX;
        int y = this.windowY;
        int bodyY = contentY();
        renderModeToggles(g, mouseX, mouseY);
        int shapeTitleY = bodyY + SECTION_TOP;

        // --- 形状模式 ---
        g.drawString(screen.font(), Component.translatable("screen.rtsbuilding.quick_build.shape"),
                x + 10, shapeTitleY, 0xD8E3EE, false);

        // --- 形状按钮 ---
        for (int i = 0; i < shapeButtons.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int slotX = x + 8 + (col * (QUICK_BUILD_SHAPE_SLOT + QUICK_BUILD_SHAPE_GAP));
            int slotY = bodyY + SECTION_TOP + 15 + (row * SHAPE_ROW_PITCH);
            shapeButtons[i].setX(slotX);
            shapeButtons[i].setY(slotY);
            shapeButtons[i].render(g, mouseX, mouseY, partialTick);
        }

        // --- 填充模式 ---
        int rightX = x + RIGHT_COL_X;
        g.drawString(screen.font(), Component.translatable("screen.rtsbuilding.quick_build.fill"),
                rightX, shapeTitleY, 0xD8E3EE, false);

        if (fillModeButtons == null || controller.getBuildShape() != lastFillShape) {
            rebuildFillModeButtons();
        }
        List<ShapeFillMode> modes =
                ShapeGeometryUtil.availableFillModes(controller.getBuildShape());
        for (int i = 0; i < fillModeButtons.length; i++) {
            int rowY = bodyY + SECTION_TOP + 15 + (i * 38); // 垂直居中对齐对应行的形状按钮
            fillModeButtons[i].setX(rightX);
            fillModeButtons[i].setY(rowY);
            fillModeButtons[i].render(g, mouseX, mouseY, partialTick);

            boolean selected = screen.getShapeFillMode() == modes.get(i);
            boolean hovered = fillModeButtons[i].isHoveredOrFocused();
            int vOffset = selected ? STATE_H * 2 : (hovered ? STATE_H : 0);
            RtsTextureRenderer.drawTextureHighPrecision(
                    g, SELECTION_DOT_TEXTURE,
                    rightX + 2, rowY + 2, 16, 16,
                    0, vOffset, SHEET_W, STATE_H,
                    SHEET_W, MODE_BUTTON_H,
                    0, 0xFFFFFFFF
            );
        }

        // --- 底部提示文字（仅在选中物品时显示，使用面板扩展区域） ---
        if (shouldShowBottomInfo()) {
            // 分界线
            int dividerY = y + QUICK_BUILD_PANEL_H;
            g.fill(x + 6, dividerY - 1, x + windowWidth - 6, dividerY, 0xFF647B92);

            // 扩展区域中心线
            int centerY = dividerY + BOTTOM_INFO_H / 2;
            int textY = centerY - screen.font().lineHeight / 2;
            int itemY = centerY - 8;

            if (this.quickBuildMode == QuickBuildMode.DESTROY) {
                g.drawString(screen.font(), screen.text("screen.rtsbuilding.quick_build.destroy_hint"),
                        x + 8, textY, 0xFFB8B8, false);
                return;
            }

            String costText = "x " + screen.currentShapeCostText();
            int textWidth = screen.font().width(costText);
            g.drawString(screen.font(), costText, x + 8, textY, 0xB8FFB8, false);

            // 渲染所选方块的物品图标，同时记录右侧边界
            ItemStack preview = resolveShapeBuildItem();
            int rightEdge = x + 8 + textWidth;
            if (!preview.isEmpty()) {
                int itemX = x + 8 + textWidth + 4;
                g.renderItem(preview, itemX, itemY);
                // 立即 flush 物品渲染，确保在 scissor 仍生效时提交到帧缓冲区
                g.flush();
                rightEdge = itemX + 16;
            }

            // 仓库库存检查：缺少数量，紧靠右侧（创造模式下跳过）
            boolean isCreative = screen.getMinecraft().player != null && screen.getMinecraft().player.isCreative();
            if (!isCreative) {
                String selectedId = controller.getSelectedItemId();
                if (!selectedId.isBlank()) {
                    try {
                        long needed = Long.parseLong(screen.currentShapeCostText());
                        long available = controller.getStorageTotalCount(selectedId);
                        long missing = needed - available;
                        if (missing > 0) {
                            String missText = screen.text("screen.rtsbuilding.quick_build.missing_blocks", missing);
                            int missTextX = rightEdge + 8;
                            g.drawString(screen.font(), missText, missTextX, textY, 0xFFB8B8, false);

                            if (!preview.isEmpty()) {
                                int missIconX = missTextX + screen.font().width(missText) + 4;
                                g.renderItem(preview, missIconX, itemY);
                                g.flush();
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    // ======================== 输入处理 ========================

    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (handleModeToggleClick(mouseX, mouseY)) {
            return;
        }
        // 委托给按钮处理
        for (WindowButton btn : shapeButtons) {
            if (btn.mouseClicked(mouseX, mouseY, button)) {
                return;
            }
        }
        if (fillModeButtons != null) {
            for (WindowButton btn : fillModeButtons) {
                if (btn.mouseClicked(mouseX, mouseY, button)) {
                    return;
                }
            }
        }
    }

    private boolean handleModeToggleClick(double mouseX, double mouseY) {
        int bodyY = contentY();
        int totalW = this.windowWidth - 16;
        int buttonW = (totalW - MODE_TOGGLE_GAP) / 2;
        int buildX = this.windowX + 8;
        int destroyX = buildX + buttonW + MODE_TOGGLE_GAP;
        int y = bodyY + MODE_ROW_TOP;
        if (mouseY < y || mouseY >= y + MODE_TOGGLE_H) {
            return false;
        }
        if (mouseX >= buildX && mouseX < buildX + buttonW) {
            setMode(QuickBuildMode.BUILD);
            return true;
        }
        if (mouseX >= destroyX && mouseX < destroyX + buttonW) {
            setMode(QuickBuildMode.DESTROY);
            return true;
        }
        return false;
    }

    // ======================== 抽象方法实现 ========================

    @Override
    protected Component getTitle() {
        return Component.translatable("screen.rtsbuilding.quick_build.title");
    }

    @Override
    protected int getDefaultWidth() {
        return QUICK_BUILD_PANEL_W;
    }

    @Override
    protected int getDefaultHeight() {
        return QUICK_BUILD_PANEL_H;
    }

    @Override
    protected int getMinWindowWidth() {
        return QUICK_BUILD_PANEL_W; // 固定宽度，不允许横向缩放
    }

    @Override
    protected int getMinWindowHeight() {
        return QUICK_BUILD_PANEL_MIN_H;
    }

    @Override
    protected void computeDefaultPosition() {
        int y = TOP_H + 40;
        int availableH = screen.getFloatingPanelAvailableHeight(y);
        if (availableH >= QUICK_BUILD_PANEL_MIN_H) {
            this.windowHeight = QUICK_BUILD_PANEL_H;
        }
        this.windowX = screen.width - QUICK_BUILD_PANEL_W - 4;
        this.windowY = y;
    }

    @Override
    protected boolean canShowWindow() {
        return super.canShowWindow()
                && screen.hasProgressionNode(RtsProgressionNodes.REMOTE_PLACE);
    }

    // ======================== 向后兼容 ========================

    @Override
    protected void onClose() {
        this.controller.clearAreaMineSession();
        screen.persistUiState();
    }

    public QuickBuildMode getMode() {
        return this.quickBuildMode;
    }

    public void setMode(QuickBuildMode mode) {
        QuickBuildMode next = mode == null ? QuickBuildMode.BUILD : mode;
        if (this.quickBuildMode == next) {
            syncAreaMineShapeFromBuildShape();
            return;
        }
        this.quickBuildMode = next;
        syncAreaMineShapeFromBuildShape();
        if (next == QuickBuildMode.DESTROY) {
            screen.clearShapeBuildSession();
        } else {
            this.controller.clearAreaMineSession();
        }
        screen.persistUiState();
    }

    public boolean isRangeDestroyMode() {
        return this.quickBuildMode == QuickBuildMode.DESTROY;
    }

    public static AreaMineShape toAreaMineShape(BuildShape shape) {
        return switch (shape == null ? BuildShape.BLOCK : shape) {
            case LINE -> AreaMineShape.LINE;
            case SQUARE -> AreaMineShape.SQUARE;
            case WALL -> AreaMineShape.WALL;
            case CIRCLE -> AreaMineShape.CIRCLE;
            case BOX -> AreaMineShape.BOX;
            case BLOCK -> AreaMineShape.BLOCK;
        };
    }

    /** @deprecated 改用 {@link #isOpen()} */
    @Deprecated
    public boolean isQuickBuildOpen() {
        return isOpen();
    }

    /** @deprecated 改用 {@link #setOpen(boolean)} */
    @Deprecated
    public void setQuickBuildOpen(boolean open) {
        setOpen(open);
    }

    /** @deprecated 改用 {@link #toggleOpen()} */
    @Deprecated
    public void toggleOpen() {
        super.toggleOpen();
    }

    /** 返回当前布局信息，供其他面板计算相对位置。 */
    public PanelLayouts.QuickBuildPanelLayout resolveLayout() {
        if (!isOpen() || !canShowWindow()) {
            return null;
        }
        return new PanelLayouts.QuickBuildPanelLayout(
                windowX, windowY, windowWidth, windowHeight);
    }

    // ======================== 私有辅助方法 ========================

    /**
     * 是否显示底部提示文字。
     * 仅在玩家选中了可放置的方块物品时扩展面板并显示。
     */
    private boolean shouldShowBottomInfo() {
        if (this.quickBuildMode == QuickBuildMode.DESTROY) {
            return true;
        }
        ItemStack preview = resolveShapeBuildItem();
        return !preview.isEmpty() && preview.getItem() instanceof BlockItem;
    }

    private void syncAreaMineShapeFromBuildShape() {
        this.controller.setAreaMineShape(toAreaMineShape(this.controller.getBuildShape()));
    }

    /**
     * 解析当前用于形状建造的物品栈：
     * 优先返回 RTS 存储中选中的物品，其次返回玩家手持工具槽位的物品。
     */
    private ItemStack resolveShapeBuildItem() {
        ItemStack selected = controller.getSelectedItemPreview();
        if (!selected.isEmpty()) {
            return selected;
        }
        var mc = screen.getMinecraft();
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        return mc.player.getInventory().getItem(mc.player.getInventory().selected);
    }
}
