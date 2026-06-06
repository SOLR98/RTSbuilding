package com.rtsbuilding.rtsbuilding.client.screen.ultimine;

import com.rtsbuilding.rtsbuilding.client.screen.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.util.RtsClientUiUtil;
import com.rtsbuilding.rtsbuilding.client.screen.layout.PanelLayouts;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.screen.ultimine.AreaMineShape;
import com.rtsbuilding.rtsbuilding.progression.RtsProgressionNodes;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.TOP_H;

/**
 * Movable Ultimine settings window.
 *
 * <p>This class owns only the small Ultimine UI state: open state, limit editing,
 * and progress display. It deliberately leaves mining execution, packets, camera
 * input, and storage/container overlays to the existing controller and screen
 * paths so the window migration does not alter gameplay behavior.
 */
public final class UltiminePanel extends RtsWindowPanel {
    // ======================== 面板尺寸配置 ========================
    /** 连锁挖掘面板宽度 */
    private static final int ULTIMINE_PANEL_W = 178;
    /** 连锁挖掘面板高度 */
    private static final int ULTIMINE_PANEL_H = 112;
    private static final int ULTIMINE_PANEL_MIN_H = 112;
    /** 连锁挖掘最小限制 */
    private static final int ULTIMINE_MIN_LIMIT = 1;
    /** 连锁挖掘最大限制 */
    private static final int ULTIMINE_MAX_LIMIT = 256;

    private int ultimineLimit = 64;
    private int lastUltimineSentLimit = 0;
    /** Whether the user is currently dragging the slider knob. */
    private boolean sliderDragging = false;
    /** Whether the user is typing a limit value into the text input. */
    private boolean limitInputFocused = false;
    /** Text being typed in the limit input field. */
    private final StringBuilder limitInputText = new StringBuilder();
    /** Current ultimine mode: CHAIN (connected same-type) or AREA (volume break). */
    private UltimineMode ultimineMode = UltimineMode.CHAIN;
    /** Current area mine shape (used when mode is AREA). */
    private AreaMineShape areaMineShape = AreaMineShape.BOX;

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.resizable = false;
    }

    public void applyOpenState(boolean open) {
        this.open = open;
    }

    public int getLimit() {
        return this.ultimineLimit;
    }

    public void setLimit(int limit) {
        this.ultimineLimit = clampUltimineLimit(limit);
    }

    public int getLastSentLimit() {
        return this.lastUltimineSentLimit;
    }

    public void setLastSentLimit(int limit) {
        this.lastUltimineSentLimit = limit;
    }

    public UltimineMode getMode() {
        return UltimineMode.CHAIN;
    }

    public void setMode(UltimineMode mode) {
        this.ultimineMode = UltimineMode.CHAIN;
    }

    public AreaMineShape getAreaMineShape() {
        return this.areaMineShape;
    }

    public void setAreaMineShape(AreaMineShape shape) {
        this.areaMineShape = shape == null ? AreaMineShape.BOX : shape;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.windowHeight = ULTIMINE_PANEL_H;
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = this.windowX;
        int range = ULTIMINE_MAX_LIMIT - ULTIMINE_MIN_LIMIT;
        double fraction = (this.ultimineLimit - ULTIMINE_MIN_LIMIT) / (double) range;

        // ---- 进度条区域 ----
        // 只显示范围破坏（Ultimine）进度，不混用单方块挖掘进度
        int progressY = contentY() + 34;
        int processed = this.controller.getUltimineProgressProcessed();
        int total = this.controller.getUltimineProgressTotal();
        boolean ultimineActive = processed >= 0 && total > 0;

        String progressLabel;
        double progressFraction;
        int labelColor;

        if (ultimineActive) {
            progressLabel = screen.text("screen.rtsbuilding.ultimine.breaking_progress", processed, total);
            progressFraction = Math.min(1.0D, processed / (double) total);
            labelColor = 0xB8FFB8;
        } else {
            progressLabel = screen.text("screen.rtsbuilding.ultimine.ready");
            progressFraction = 0.0D;
            labelColor = 0xAFC0D3;
        }

        g.drawString(screen.font(), progressLabel, x + 8, progressY - 12, labelColor, false);
        RtsClientUiUtil.drawPanelFrame(g, x + 8, progressY, ULTIMINE_PANEL_W - 16, 12, 0xAA101820, 0xFF647B92, 0xFF0D1117);
        if (progressFraction > 0.0D) {
            int fillW = Math.min(ULTIMINE_PANEL_W - 20, Math.max(1, (int) (progressFraction * (ULTIMINE_PANEL_W - 20))));
            g.fill(x + 10, progressY + 2, x + 10 + fillW, progressY + 10, 0xFF78B28C);
        }

        // ---- 数值输入框 ----
        int inputX = x + (ULTIMINE_PANEL_W - 50) / 2;
        int inputY = contentY() + 52;
        int inputW = 50;
        int inputH = 12;
        g.fill(inputX, inputY, inputX + inputW, inputY + inputH, 0xFF0D1117);
        g.fill(inputX + 1, inputY + 1, inputX + inputW - 1, inputY + inputH - 1, 0xFF1A2330);

        String displayText = limitInputFocused
                ? limitInputText.toString()
                : Integer.toString(this.ultimineLimit);
        boolean showCursor = limitInputFocused && (System.currentTimeMillis() / 500 % 2 == 0);
        String cursorStr = showCursor ? "|" : "";
        int textX = inputX + (inputW - screen.font().width(displayText + cursorStr)) / 2;
        int textY = inputY + (inputH - screen.font().lineHeight) / 2;
        g.drawString(screen.font(), displayText + cursorStr, textX, textY, 0xF2F7FF, false);

        // ---- 滑块 + 标签 ----
        int trackX = x + 8;
        int trackY = contentY() + 72;
        int trackW = ULTIMINE_PANEL_W - 16;
        int knobX = trackX + (int) Math.round(fraction * trackW);

        // 轨道背景
        g.fill(trackX, trackY, trackX + trackW, trackY + 4, 0xFF07090D);
        g.fill(trackX + 1, trackY + 1, trackX + trackW - 1, trackY + 3, 0xFF313946);
        // 已填充部分
        g.fill(trackX + 1, trackY + 1, knobX, trackY + 3, 0xFF5FE36C);
        // 滑块 (knob)
        boolean hoverSlider = !this.sliderDragging && mouseY >= trackY - 6 && mouseY <= trackY + 10
                && mouseX >= knobX - 4 && mouseX <= knobX + 5;
        int knobColor = this.sliderDragging ? 0xFF8AFF8A : (hoverSlider ? 0xFF6AFF7A : 0xFF5FE36C);
        g.fill(knobX - 3, trackY - 5, knobX + 4, trackY + 8, knobColor);

        // 最小/最大值标签
        g.drawString(screen.font(), Integer.toString(ULTIMINE_MIN_LIMIT), trackX, trackY + 10, 0xB5C1CE, false);
        g.drawString(screen.font(), Integer.toString(ULTIMINE_MAX_LIMIT), trackX + trackW - 20, trackY + 10, 0xB5C1CE,
                false);

        // ---- 模式切换 ----
        int modeBtnY = contentY() + 92;
        int modeBtnW = (ULTIMINE_PANEL_W - 20) / 2;
        int chainBtnX = x + 8;
        int areaBtnX = chainBtnX + modeBtnW + 4;

        boolean chainActive = this.ultimineMode == UltimineMode.CHAIN;
        boolean hoverChain = mouseY >= modeBtnY && mouseY <= modeBtnY + 14
                && mouseX >= chainBtnX && mouseX <= chainBtnX + modeBtnW;
        boolean hoverArea = mouseY >= modeBtnY && mouseY <= modeBtnY + 14
                && mouseX >= areaBtnX && mouseX <= areaBtnX + modeBtnW;

        int chainBg = chainActive ? 0xFF5FE36C : (hoverChain ? 0xFF2A3A4A : 0xFF1A2330);
        int chainBorder = chainActive ? 0xFF5FE36C : (hoverChain ? 0xFF647B92 : 0xFF313946);
        g.fill(chainBtnX, modeBtnY, chainBtnX + modeBtnW, modeBtnY + 14, chainBorder);
        g.fill(chainBtnX + 1, modeBtnY + 1, chainBtnX + modeBtnW - 1, modeBtnY + 13, chainBg);
        int chainLabelX = chainBtnX + (modeBtnW - screen.font().width(screen.text("screen.rtsbuilding.ultimine.mode_chain"))) / 2;
        g.drawString(screen.font(), screen.text("screen.rtsbuilding.ultimine.mode_chain"), chainLabelX, modeBtnY + 3,
                chainActive ? 0xFF0D1117 : 0xB5C1CE, false);

        int areaBg = !chainActive ? 0xFF5FE36C : (hoverArea ? 0xFF2A3A4A : 0xFF1A2330);
        int areaBorder = !chainActive ? 0xFF5FE36C : (hoverArea ? 0xFF647B92 : 0xFF313946);
        g.fill(areaBtnX, modeBtnY, areaBtnX + modeBtnW, modeBtnY + 14, areaBorder);
        g.fill(areaBtnX + 1, modeBtnY + 1, areaBtnX + modeBtnW - 1, modeBtnY + 13, areaBg);
        int areaLabelX = areaBtnX + (modeBtnW - screen.font().width(screen.text("screen.rtsbuilding.ultimine.mode_area"))) / 2;
        g.drawString(screen.font(), screen.text("screen.rtsbuilding.ultimine.mode_area"), areaLabelX, modeBtnY + 3,
                chainActive ? 0xB5C1CE : 0xFF0D1117, false);

        // ---- 形状选择（仅在 AREA 模式下显示）----
        if (this.ultimineMode == UltimineMode.AREA) {
            int shapeBtnY = contentY() + 112;
            int shapeBtnW = (ULTIMINE_PANEL_W - 28) / 3;
            int shapeGap = 4;
            int shapeStartX = x + 8;

            AreaMineShape[] shapes = AreaMineShape.values();
            for (int i = 0; i < shapes.length; i++) {
                int bx = shapeStartX + i * (shapeBtnW + shapeGap);
                boolean active = this.areaMineShape == shapes[i];
                boolean hover = mouseY >= shapeBtnY && mouseY <= shapeBtnY + 14
                        && mouseX >= bx && mouseX <= bx + shapeBtnW;

                int bg = active ? 0xFF5FE36C : (hover ? 0xFF2A3A4A : 0xFF1A2330);
                int border = active ? 0xFF5FE36C : (hover ? 0xFF647B92 : 0xFF313946);
                g.fill(bx, shapeBtnY, bx + shapeBtnW, shapeBtnY + 14, border);
                g.fill(bx + 1, shapeBtnY + 1, bx + shapeBtnW - 1, shapeBtnY + 13, bg);

                String labelKey = "screen.rtsbuilding.ultimine.shape_" + shapes[i].name().toLowerCase(java.util.Locale.ROOT);
                String label = screen.text(labelKey);
                int labelX = bx + (shapeBtnW - screen.font().width(label)) / 2;
                g.drawString(screen.font(), label, labelX, shapeBtnY + 3,
                        active ? 0xFF0D1117 : 0xB5C1CE, false);
            }
        }
    }
    
    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        // ---- 数值输入框点击 ----
        int inputX = this.windowX + (ULTIMINE_PANEL_W - 50) / 2;
        int inputY = contentY() + 52;
        int inputW = 50;
        int inputH = 12;
        if (mouseX >= inputX && mouseX <= inputX + inputW && mouseY >= inputY && mouseY <= inputY + inputH) {
            this.limitInputFocused = true;
            this.limitInputText.setLength(0);
            this.limitInputText.append(this.ultimineLimit);
            return;
        }
        if (this.limitInputFocused) {
            commitLimitInput();
        }
        this.limitInputFocused = false;

        // ---- 滑块点击 ----
        int trackX = this.windowX + 8;
        int trackY = contentY() + 72;
        int trackW = ULTIMINE_PANEL_W - 16;
        if (mouseY >= trackY - 6 && mouseY <= trackY + 10 && mouseX >= trackX && mouseX <= trackX + trackW) {
            this.sliderDragging = true;
            setLimitFromSliderX(mouseX);
            screen.persistUiState();
            return;
        }

        // ---- 模式切换点击 ----
        int modeBtnY = contentY() + 92;
        int modeBtnW = (ULTIMINE_PANEL_W - 20) / 2;
        int chainBtnX = this.windowX + 8;
        int areaBtnX = chainBtnX + modeBtnW + 4;
        if (mouseY >= modeBtnY && mouseY <= modeBtnY + 14) {
            if (mouseX >= chainBtnX && mouseX <= chainBtnX + modeBtnW && this.ultimineMode != UltimineMode.CHAIN) {
                this.ultimineMode = UltimineMode.CHAIN;
                screen.persistUiState();
                return;
            }
            if (mouseX >= areaBtnX && mouseX <= areaBtnX + modeBtnW && this.ultimineMode != UltimineMode.AREA) {
                this.ultimineMode = UltimineMode.AREA;
                screen.persistUiState();
                return;
            }
        }

        // ---- 形状选择点击（仅 AREA 模式下响应）----
        if (this.ultimineMode == UltimineMode.AREA) {
            int shapeBtnY = contentY() + 112;
            int shapeBtnW = (ULTIMINE_PANEL_W - 28) / 3;
            int shapeGap = 4;
            int shapeStartX = this.windowX + 8;
            if (mouseY >= shapeBtnY && mouseY <= shapeBtnY + 14) {
                AreaMineShape[] shapes = AreaMineShape.values();
                for (int i = 0; i < shapes.length; i++) {
                    int bx = shapeStartX + i * (shapeBtnW + shapeGap);
                    if (mouseX >= bx && mouseX <= bx + shapeBtnW && this.areaMineShape != shapes[i]) {
                        this.areaMineShape = shapes[i];
                        this.controller.setAreaMineShape(shapes[i]);
                        screen.persistUiState();
                        return;
                    }
                }
            }
        }
    }

    @Override
    protected Component getTitle() {
        return Component.translatable("screen.rtsbuilding.ultimine.title");
    }

    @Override
    protected int getDefaultWidth() {
        return ULTIMINE_PANEL_W;
    }

    @Override
    protected int getDefaultHeight() {
        return ULTIMINE_PANEL_H;
    }

    @Override
    protected int getMinWindowWidth() {
        return ULTIMINE_PANEL_W;
    }

    @Override
    protected int getMinWindowHeight() {
        return ULTIMINE_PANEL_MIN_H;
    }

    @Override
    protected boolean canShowWindow() {
        return screen.hasProgressionNode(RtsProgressionNodes.ULTIMINE);
    }

    @Override
    protected void computeDefaultPosition() {
        int y = TOP_H + 10;
        PanelLayouts.QuickBuildPanelLayout quickBuildLayout = screen.resolveQuickBuildPanelLayout();
        if (quickBuildLayout != null) {
            y = quickBuildLayout.y() + quickBuildLayout.h() + 8;
        }
        int maxX = Math.max(4, screen.width - ULTIMINE_PANEL_W - 4);
        this.windowX = Mth.clamp(screen.width - ULTIMINE_PANEL_W - 10, 4, maxX);
        this.windowY = y;
    }

    @Override
    protected void onClose() {
        screen.persistUiState();
    }

    @Override
    protected void onBoundsChanged() {
        screen.persistUiState();
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (this.sliderDragging) {
            setLimitFromSliderX(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.sliderDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ======================== 键盘输入 ========================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!this.open) {
            return false;
        }
        if (this.limitInputFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.limitInputFocused = false;
                this.limitInputText.setLength(0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                commitLimitInput();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!this.limitInputText.isEmpty()) {
                    this.limitInputText.setLength(this.limitInputText.length() - 1);
                }
                return true;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean handleWindowCharTyped(char codePoint, int modifiers) {
        if (!this.limitInputFocused) {
            return false;
        }
        if (codePoint >= '0' && codePoint <= '9' && this.limitInputText.length() < 4) {
            this.limitInputText.append(codePoint);
            return true;
        }
        return false;
    }

    private void commitLimitInput() {
        if (!this.limitInputText.isEmpty()) {
            try {
                int value = Integer.parseInt(this.limitInputText.toString());
                setLimit(value);
                screen.persistUiState();
            } catch (NumberFormatException ignored) {
            }
        }
        this.limitInputFocused = false;
        this.limitInputText.setLength(0);
    }

    private void setLimitFromSliderX(double mouseX) {
        int trackX = this.windowX + 8;
        int trackW = ULTIMINE_PANEL_W - 16;
        double fraction = (mouseX - trackX) / (double) trackW;
        fraction = Mth.clamp(fraction, 0.0, 1.0);
        this.ultimineLimit = (int) Math.round(ULTIMINE_MIN_LIMIT + fraction * (ULTIMINE_MAX_LIMIT - ULTIMINE_MIN_LIMIT));
        this.ultimineLimit = clampUltimineLimit(this.ultimineLimit);
    }

    private static int clampUltimineLimit(int value) {
        return Math.max(ULTIMINE_MIN_LIMIT, Math.min(ULTIMINE_MAX_LIMIT, value));
    }

}
