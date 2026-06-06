package com.rtsbuilding.rtsbuilding.blueprint.client;

import com.rtsbuilding.rtsbuilding.Config;
import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.util.RtsClientUiUtil;
import com.rtsbuilding.rtsbuilding.client.widget.WindowButton;
import com.rtsbuilding.rtsbuilding.client.widget.WindowTextBox;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.TOP_H;

/**
 * Windowed control surface for blueprint creation and blueprint preview placement.
 *
 * <p>This panel deliberately owns only presentation and input routing. The
 * blueprint state machine, file import/export, capture scanner, material
 * analysis, and server placement request all remain in {@link BlueprintPanel}.
 * Keeping that boundary explicit lets us replace the old hand-drawn capture HUD
 * and placement strip with a movable Windows-style panel without duplicating the
 * sensitive blueprint logic.</p>
 *
 * <p>Player-facing intent: after choosing "New Blueprint", the two old floating
 * fragments (top capture status plus side adjustment box) become one larger
 * window. After selecting a saved blueprint and pinning it in the world, the
 * same window family exposes rotation, previous/next blueprint selection,
 * position nudging, material details, and build confirmation. Keyboard nudging
 * continues to use the mature existing blueprint placement path instead of a
 * second implementation.</p>
 */
public final class BlueprintWindowPanel extends RtsWindowPanel {
    private static final int PANEL_W = 376;
    private static final int PANEL_H = 232;
    private static final int MIN_W = 330;
    private static final int MIN_H = 206;
    private static final int PAD = 10;
    private static final int BUTTON_H = 16;
    private static final int SMALL_BUTTON_W = 18;
    private static final int AXIS_W = 46;
    private static final int TEXTBOX_H = 16;
    private static final int ACTION_W = 58;
    private static final int COMPACT_ACTION_W = 48;

    private WindowTextBox captureNameInput;
    private WindowTextBox sizeXInput;
    private WindowTextBox sizeYInput;
    private WindowTextBox sizeZInput;
    private WindowTextBox posXInput;
    private WindowTextBox posYInput;
    private WindowTextBox posZInput;
    private boolean captureNameInitialized;

    private WindowButton saveCaptureButton;
    private WindowButton cancelButton;
    private WindowButton moveUpButton;
    private WindowButton moveDownButton;
    private WindowButton previewButton;
    private WindowButton previousButton;
    private WindowButton nextButton;
    private WindowButton rotateButton;
    private WindowButton resetRotationButton;
    private WindowButton detailsButton;
    private WindowButton buildButton;
    private WindowButton clearButton;
    private WindowButton applyPositionButton;
    private WindowButton[] sizePlusButtons;
    private WindowButton[] sizeMinusButtons;
    private WindowButton[] posPlusButtons;
    private WindowButton[] posMinusButtons;
    private WindowButton nudgeForwardButton;
    private WindowButton nudgeBackButton;
    private WindowButton nudgeLeftButton;
    private WindowButton nudgeRightButton;
    private WindowButton nudgeUpButton;
    private WindowButton nudgeDownButton;

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.resizable = true;
        this.draggable = true;
        createTextBoxes();
        createButtons();
    }

    /**
     * Opens the panel when blueprint state needs a window. Closing the window is
     * handled through {@link #onClose()} so that a user close also clears the
     * corresponding capture or preview state.
     */
    public void syncWithBlueprintState() {
        if (shouldRepresentBlueprintState() && !isOpen()) {
            setOpen(true);
            markBroughtToFront();
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (BlueprintPanel.isCaptureModeActive()) {
            renderCaptureContent(g, mouseX, mouseY, partialTick);
        } else {
            renderPlacementContent(g, mouseX, mouseY, partialTick);
        }
        renderStatusLine(g);
    }

    private void renderCaptureContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        syncCaptureInputs();
        int x = contentX() + PAD;
        int y = contentY() + 8;
        int w = contentWidth() - PAD * 2;
        boolean complete = BlueprintPanel.isCaptureSelectionComplete();
        boolean saving = BlueprintPanel.isCaptureSaving();

        drawLabel(g, Component.translatable("screen.rtsbuilding.blueprints.capture_window_hint"), x, y,
                complete ? 0xFF8EEA9B : 0xFFFFC06C, w);
        y += 16;
        drawPointRow(g, x, y, w);
        y += 28;

        drawSectionTitle(g, Component.translatable("screen.rtsbuilding.blueprints.window_size"), x, y);
        renderAxisSpinnerRow(g, mouseX, mouseY, partialTick,
                x + 54, y - 8, this.sizeXInput, this.sizeYInput, this.sizeZInput,
                this.sizePlusButtons, this.sizeMinusButtons, complete && !saving);

        int moveX = x;
        int moveY = y + 52;
        renderButton(g, this.moveUpButton, moveX, moveY, complete && !saving, mouseX, mouseY, partialTick);
        renderButton(g, this.moveDownButton, moveX + ACTION_W + 6, moveY, complete && !saving, mouseX, mouseY, partialTick);
        renderButton(g, this.previewButton, moveX + (ACTION_W + 6) * 2, moveY, complete && !saving, mouseX, mouseY, partialTick);
        y += 76;

        drawSectionTitle(g, Component.translatable("screen.rtsbuilding.blueprints.name_dialog_label"), x, y + 2);
        this.captureNameInput.setX(x + 54);
        this.captureNameInput.setY(y);
        this.captureNameInput.setWidth(Math.max(80, w - 54));
        this.captureNameInput.renderWidget(g, mouseX, mouseY, partialTick);
        y += 24;

        if (saving) {
            drawLabel(g, Component.literal(BlueprintPanel.captureSaveProgressLine()), x, y, 0xFFB7CDE2, w);
        } else {
            String count = Long.toString(BlueprintPanel.countCaptureBlocks());
            drawLabel(g, Component.translatable("screen.rtsbuilding.blueprints.capture_blocks", count),
                    x, y, complete ? 0xFFB7CDE2 : 0xFF8190A0, w);
        }

        int actionY = contentY() + contentHeight() - 28;
        renderButton(g, this.saveCaptureButton, x, actionY, complete && !saving, mouseX, mouseY, partialTick);
        renderButton(g, this.cancelButton, x + ACTION_W + 8, actionY, !saving, mouseX, mouseY, partialTick);
    }

    private void renderPlacementContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        syncPlacementInputs();
        int x = contentX() + PAD;
        int y = contentY() + 8;
        int w = contentWidth() - PAD * 2;
        boolean pinned = BlueprintPanel.hasPinnedPreview();

        drawLabel(g, Component.literal(BlueprintPanel.selectedBlueprintName()), x, y, 0xFFEAF2FF, w);
        drawLabel(g, Component.literal(BlueprintPanel.selectedBlueprintSizeText()), x, y + 12, 0xFF9FB3C8, w);
        renderButton(g, this.previousButton, x + w - COMPACT_ACTION_W * 2 - 6, y, true, mouseX, mouseY, partialTick);
        renderButton(g, this.nextButton, x + w - COMPACT_ACTION_W, y, true, mouseX, mouseY, partialTick);
        y += 34;

        drawSectionTitle(g, Component.translatable("screen.rtsbuilding.blueprints.window_position"), x, y);
        renderAxisSpinnerRow(g, mouseX, mouseY, partialTick,
                x + 54, y - 8, this.posXInput, this.posYInput, this.posZInput,
                this.posPlusButtons, this.posMinusButtons, pinned);
        renderButton(g, this.applyPositionButton, x + w - COMPACT_ACTION_W, y + 8,
                pinned, mouseX, mouseY, partialTick);
        y += 50;

        renderButton(g, this.rotateButton, x, y, true, mouseX, mouseY, partialTick);
        renderButton(g, this.resetRotationButton, x + ACTION_W + 6, y, true, mouseX, mouseY, partialTick);
        renderButton(g, this.detailsButton, x + (ACTION_W + 6) * 2, y, true, mouseX, mouseY, partialTick);
        y += 24;

        renderNudgeGrid(g, mouseX, mouseY, partialTick, x, y, pinned);
        if (!pinned) {
            drawLabel(g, Component.translatable("screen.rtsbuilding.blueprints.placement_window_hint"),
                    x + 142, y + 4, 0xFFFFC06C, Math.max(80, w - 142));
        }

        int actionY = contentY() + contentHeight() - 28;
        renderButton(g, this.buildButton, x, actionY, pinned, mouseX, mouseY, partialTick);
        renderButton(g, this.clearButton, x + ACTION_W + 8, actionY, true, mouseX, mouseY, partialTick);
    }

    private void renderNudgeGrid(GuiGraphics g, int mouseX, int mouseY, float partialTick, int x, int y, boolean enabled) {
        renderButtonAt(g, this.nudgeForwardButton, x + 48, y, enabled, mouseX, mouseY, partialTick);
        renderButtonAt(g, this.nudgeLeftButton, x, y + 18, enabled, mouseX, mouseY, partialTick);
        renderButtonAt(g, this.nudgeRightButton, x + 96, y + 18, enabled, mouseX, mouseY, partialTick);
        renderButtonAt(g, this.nudgeUpButton, x + 48, y + 18, enabled, mouseX, mouseY, partialTick);
        renderButtonAt(g, this.nudgeDownButton, x + 48, y + 36, enabled, mouseX, mouseY, partialTick);
        renderButtonAt(g, this.nudgeBackButton, x + 96, y + 36, enabled, mouseX, mouseY, partialTick);
    }

    private void renderStatusLine(GuiGraphics g) {
        Component status = BlueprintPanel.statusText();
        if (status == null) {
            return;
        }
        int x = contentX() + PAD;
        int y = contentY() + contentHeight() - 10;
        int w = contentWidth() - PAD * 2;
        g.drawString(this.screen.font(), RtsClientUiUtil.trimToWidth(this.screen.font(), status.getString(), w),
                x, y, BlueprintPanel.statusColor(), false);
    }

    private void drawPointRow(GuiGraphics g, int x, int y, int w) {
        String a = Component.translatable("screen.rtsbuilding.blueprints.capture_point_a",
                BlueprintPanel.capturePointAText()).getString();
        String b = Component.translatable("screen.rtsbuilding.blueprints.capture_point_b",
                BlueprintPanel.capturePointBText()).getString();
        g.drawString(this.screen.font(), RtsClientUiUtil.trimToWidth(this.screen.font(), a, w / 2 - 4),
                x, y, 0xFFB7CDE2, false);
        g.drawString(this.screen.font(), RtsClientUiUtil.trimToWidth(this.screen.font(), b, w / 2 - 4),
                x + w / 2, y, 0xFFB7CDE2, false);
        String size = Component.translatable("screen.rtsbuilding.blueprints.capture_size",
                BlueprintPanel.captureSizeText()).getString();
        g.drawString(this.screen.font(), RtsClientUiUtil.trimToWidth(this.screen.font(), size, w),
                x, y + 12, 0xFF9FB3C8, false);
    }

    private void renderAxisSpinnerRow(GuiGraphics g, int mouseX, int mouseY, float partialTick,
            int x, int y, WindowTextBox xBox, WindowTextBox yBox, WindowTextBox zBox,
            WindowButton[] plusButtons, WindowButton[] minusButtons, boolean enabled) {
        WindowTextBox[] boxes = {xBox, yBox, zBox};
        String[] labels = {"X", "Y", "Z"};
        for (int i = 0; i < boxes.length; i++) {
            int axisX = x + i * (AXIS_W + 12);
            g.drawString(this.screen.font(), labels[i], axisX + AXIS_W / 2 - 3, y - 9, 0xFF9FB3C8, false);
            renderButtonAt(g, plusButtons[i], axisX + (AXIS_W - SMALL_BUTTON_W) / 2, y, enabled, mouseX, mouseY, partialTick);
            boxes[i].setX(axisX);
            boxes[i].setY(y + BUTTON_H + 2);
            boxes[i].setWidth(AXIS_W);
            boxes[i].setEditable(enabled);
            boxes[i].renderWidget(g, mouseX, mouseY, partialTick);
            renderButtonAt(g, minusButtons[i], axisX + (AXIS_W - SMALL_BUTTON_W) / 2,
                    y + BUTTON_H + TEXTBOX_H + 4, enabled, mouseX, mouseY, partialTick);
        }
    }

    private void drawSectionTitle(GuiGraphics g, Component text, int x, int y) {
        g.drawString(this.screen.font(), text, x, y, 0xFFD8E3EE, false);
    }

    private void drawLabel(GuiGraphics g, Component text, int x, int y, int color, int maxWidth) {
        g.drawString(this.screen.font(), RtsClientUiUtil.trimToWidth(this.screen.font(), text.getString(), maxWidth),
                x, y, color, false);
    }

    private void renderButton(GuiGraphics g, WindowButton button, int x, int y, boolean active,
            int mouseX, int mouseY, float partialTick) {
        renderButtonAt(g, button, x, y, active, mouseX, mouseY, partialTick);
    }

    private void renderButtonAt(GuiGraphics g, WindowButton button, int x, int y, boolean active,
            int mouseX, int mouseY, float partialTick) {
        button.setX(x);
        button.setY(y);
        button.active = active;
        button.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (BlueprintPanel.isCaptureModeActive()) {
            handleCaptureClick(mouseX, mouseY, button);
        } else {
            handlePlacementClick(mouseX, mouseY, button);
        }
    }

    private void handleCaptureClick(double mouseX, double mouseY, int button) {
        if (clickTextBox(this.captureNameInput, mouseX, mouseY, button)) {
            return;
        }
        if (clickTextBox(this.sizeXInput, mouseX, mouseY, button)
                || clickTextBox(this.sizeYInput, mouseX, mouseY, button)
                || clickTextBox(this.sizeZInput, mouseX, mouseY, button)) {
            return;
        }
        clearFocus();
        clickButtons(mouseX, mouseY, button,
                this.saveCaptureButton, this.cancelButton, this.moveUpButton, this.moveDownButton, this.previewButton);
        clickButtons(mouseX, mouseY, button, this.sizePlusButtons);
        clickButtons(mouseX, mouseY, button, this.sizeMinusButtons);
    }

    private void handlePlacementClick(double mouseX, double mouseY, int button) {
        if (clickTextBox(this.posXInput, mouseX, mouseY, button)
                || clickTextBox(this.posYInput, mouseX, mouseY, button)
                || clickTextBox(this.posZInput, mouseX, mouseY, button)) {
            return;
        }
        clearFocus();
        clickButtons(mouseX, mouseY, button,
                this.previousButton, this.nextButton, this.rotateButton, this.resetRotationButton,
                this.detailsButton, this.buildButton, this.clearButton, this.applyPositionButton,
                this.nudgeForwardButton, this.nudgeBackButton, this.nudgeLeftButton,
                this.nudgeRightButton, this.nudgeUpButton, this.nudgeDownButton);
        clickButtons(mouseX, mouseY, button, this.posPlusButtons);
        clickButtons(mouseX, mouseY, button, this.posMinusButtons);
    }

    private boolean clickTextBox(WindowTextBox box, double mouseX, double mouseY, int button) {
        if (box == null) {
            return false;
        }
        boolean clicked = box.mouseClicked(mouseX, mouseY, button);
        if (clicked) {
            if (box != this.captureNameInput) {
                this.captureNameInput.setFocused(false);
            }
            if (box != this.sizeXInput) this.sizeXInput.setFocused(false);
            if (box != this.sizeYInput) this.sizeYInput.setFocused(false);
            if (box != this.sizeZInput) this.sizeZInput.setFocused(false);
            if (box != this.posXInput) this.posXInput.setFocused(false);
            if (box != this.posYInput) this.posYInput.setFocused(false);
            if (box != this.posZInput) this.posZInput.setFocused(false);
        }
        return clicked;
    }

    private void clickButtons(double mouseX, double mouseY, int button, WindowButton... buttons) {
        for (WindowButton windowButton : buttons) {
            if (windowButton != null && windowButton.mouseClicked(mouseX, mouseY, button)) {
                return;
            }
        }
    }

    @Override
    protected boolean handleWindowKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (focusedTextBox() != null) {
            WindowTextBox focused = focusedTextBox();
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitFocusedTextBox(focused);
                focused.setFocused(false);
                return true;
            }
            return focused.keyPressed(keyCode, scanCode, modifiers);
        }
        if (BlueprintPanel.isCaptureModeActive()) {
            return handleCaptureKey(keyCode);
        }
        return handlePlacementKey(keyCode);
    }

    @Override
    protected boolean handleWindowCharTyped(char codePoint, int modifiers) {
        WindowTextBox focused = focusedTextBox();
        return focused != null && focused.charTyped(codePoint, modifiers);
    }

    @Override
    protected boolean handleContentScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (BlueprintPanel.isCaptureModeActive()) {
            int step = scrollY > 0.0D ? 1 : -1;
            BlueprintPanel.adjustCaptureSize(0, step, 0);
            syncCaptureSizeInputs(true);
            return true;
        }
        if (BlueprintPanel.hasPinnedPreview()) {
            int step = scrollY > 0.0D ? 1 : -1;
            BlueprintPanel.nudgePinnedAnchor(0, step, 0, this.controller);
            syncPinnedPositionInputs(true);
        }
        return true;
    }

    private boolean handleCaptureKey(int keyCode) {
        int step = isAltDown() ? 4 : 1;
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            BlueprintPanel.adjustCaptureSize(0, step, 0);
            syncCaptureSizeInputs(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            BlueprintPanel.adjustCaptureSize(0, -step, 0);
            syncCaptureSizeInputs(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            BlueprintPanel.moveCaptureSelection(0, 1, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            BlueprintPanel.moveCaptureSelection(0, -1, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT) {
            BlueprintPanel.moveCaptureSelection(-1, 0, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT) {
            BlueprintPanel.moveCaptureSelection(1, 0, 0);
            return true;
        }
        return false;
    }

    private boolean handlePlacementKey(int keyCode) {
        if (!BlueprintPanel.hasPinnedPreview()) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_KP_4) {
            return BlueprintPanel.nudgePinnedAnchorRelative(-1, 0, 0, this.controller);
        }
        if (keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_KP_6) {
            return BlueprintPanel.nudgePinnedAnchorRelative(1, 0, 0, this.controller);
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_KP_8) {
            return BlueprintPanel.nudgePinnedAnchorRelative(0, 1, 0, this.controller);
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_KP_2) {
            return BlueprintPanel.nudgePinnedAnchorRelative(0, -1, 0, this.controller);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            return BlueprintPanel.nudgePinnedAnchor(0, 1, 0, this.controller);
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            return BlueprintPanel.nudgePinnedAnchor(0, -1, 0, this.controller);
        }
        return false;
    }

    private void commitFocusedTextBox(WindowTextBox focused) {
        if (focused == this.sizeXInput || focused == this.sizeYInput || focused == this.sizeZInput) {
            commitCaptureSizeDraft();
        } else if (focused == this.posXInput || focused == this.posYInput || focused == this.posZInput) {
            commitPinnedPositionDraft();
        } else if (focused == this.captureNameInput && BlueprintPanel.isCaptureSelectionComplete()) {
            saveCaptureDraft();
        }
    }

    private void saveCaptureDraft() {
        BlueprintPanel.saveCapturedAreaAs(this.captureNameInput.getValue());
    }

    private void commitCaptureSizeDraft() {
        int x = parsePositive(this.sizeXInput.getValue(), BlueprintPanel.captureSizeX());
        int y = parseNonNegative(this.sizeYInput.getValue(), BlueprintPanel.captureSizeY());
        int z = parsePositive(this.sizeZInput.getValue(), BlueprintPanel.captureSizeZ());
        BlueprintPanel.setCaptureSize(x, y, z);
        syncCaptureSizeInputs(true);
    }

    private void commitPinnedPositionDraft() {
        BlockPos anchor = BlueprintPanel.getPinnedAnchor();
        if (anchor == null) {
            return;
        }
        int x = parseAnyInt(this.posXInput.getValue(), anchor.getX());
        int y = parseAnyInt(this.posYInput.getValue(), anchor.getY());
        int z = parseAnyInt(this.posZInput.getValue(), anchor.getZ());
        BlueprintPanel.setPinnedAnchor(new BlockPos(x, y, z), this.controller);
        syncPinnedPositionInputs(true);
    }

    private WindowTextBox focusedTextBox() {
        WindowTextBox[] boxes = {
                this.captureNameInput,
                this.sizeXInput, this.sizeYInput, this.sizeZInput,
                this.posXInput, this.posYInput, this.posZInput
        };
        for (WindowTextBox box : boxes) {
            if (box != null && box.isFocused()) {
                return box;
            }
        }
        return null;
    }

    private void clearFocus() {
        this.captureNameInput.setFocused(false);
        this.sizeXInput.setFocused(false);
        this.sizeYInput.setFocused(false);
        this.sizeZInput.setFocused(false);
        this.posXInput.setFocused(false);
        this.posYInput.setFocused(false);
        this.posZInput.setFocused(false);
    }

    @Override
    protected Component getTitle() {
        return Component.translatable(BlueprintPanel.isCaptureModeActive()
                ? "screen.rtsbuilding.blueprints.window_title_capture"
                : "screen.rtsbuilding.blueprints.window_title_placement");
    }

    @Override
    protected int getDefaultWidth() {
        return PANEL_W;
    }

    @Override
    protected int getDefaultHeight() {
        return PANEL_H;
    }

    @Override
    protected int getMinWindowWidth() {
        return MIN_W;
    }

    @Override
    protected int getMinWindowHeight() {
        return MIN_H;
    }

    @Override
    protected void computeDefaultPosition() {
        this.windowX = Math.max(4, this.screen.width - PANEL_W - 8);
        this.windowY = TOP_H + 8;
    }

    @Override
    protected boolean canShowWindow() {
        return shouldRepresentBlueprintState();
    }

    @Override
    protected void onClose() {
        if (BlueprintPanel.isCaptureModeActive()) {
            BlueprintPanel.cancelCaptureMode();
        } else if (BlueprintPanel.hasSelectedBlueprint()) {
            BlueprintPanel.clearSelectedBlueprint();
        }
        clearFocus();
        this.captureNameInitialized = false;
    }

    private boolean shouldRepresentBlueprintState() {
        return Config.areBlueprintsEnabled()
                && (BlueprintPanel.isCaptureModeActive() || BlueprintPanel.hasSelectedBlueprint());
    }

    private void createTextBoxes() {
        this.captureNameInput = new WindowTextBox(this.screen.font(), 0, 0, 160, TEXTBOX_H);
        this.captureNameInput.setMaxLength(80);
        this.captureNameInput.setPlaceholder(Component.translatable("screen.rtsbuilding.blueprints.name_dialog_label").getString());

        this.sizeXInput = createSizeInput();
        this.sizeYInput = createSizeInput();
        this.sizeZInput = createSizeInput();
        this.posXInput = createPositionInput();
        this.posYInput = createPositionInput();
        this.posZInput = createPositionInput();
    }

    private WindowTextBox createSizeInput() {
        WindowTextBox box = new WindowTextBox(this.screen.font(), 0, 0, AXIS_W, TEXTBOX_H);
        box.setMaxLength(4);
        box.setInputFilter(value -> value != null && value.matches("\\d*"));
        return box;
    }

    private WindowTextBox createPositionInput() {
        WindowTextBox box = new WindowTextBox(this.screen.font(), 0, 0, AXIS_W, TEXTBOX_H);
        box.setMaxLength(8);
        box.setInputFilter(value -> value != null && value.matches("-?\\d*"));
        return box;
    }

    private void createButtons() {
        this.saveCaptureButton = actionButton("screen.rtsbuilding.blueprints.save_area", ACTION_W,
                button -> saveCaptureDraft());
        this.cancelButton = actionButton("screen.rtsbuilding.blueprints.capture_cancel", ACTION_W,
                button -> {
                    if (BlueprintPanel.isCaptureModeActive()) {
                        BlueprintPanel.cancelCaptureMode();
                    } else {
                        BlueprintPanel.clearSelectedBlueprint();
                    }
                });
        this.moveUpButton = actionButton("screen.rtsbuilding.blueprints.capture_move_up", ACTION_W,
                button -> BlueprintPanel.moveCaptureSelection(0, 1, 0));
        this.moveDownButton = actionButton("screen.rtsbuilding.blueprints.capture_move_down", ACTION_W,
                button -> BlueprintPanel.moveCaptureSelection(0, -1, 0));
        this.previewButton = actionButton("screen.rtsbuilding.blueprints.capture_preview", ACTION_W,
                button -> BlueprintPanel.toggleCapturePreview());
        this.previousButton = actionButton("screen.rtsbuilding.blueprints.previous_short", COMPACT_ACTION_W,
                button -> BlueprintPanel.selectRelativeBlueprint(-1));
        this.nextButton = actionButton("screen.rtsbuilding.blueprints.next_short", COMPACT_ACTION_W,
                button -> BlueprintPanel.selectRelativeBlueprint(1));
        this.rotateButton = actionButton("screen.rtsbuilding.blueprints.y_rotate_short", ACTION_W,
                button -> BlueprintPanel.rotateSelectedBlueprintY(1));
        this.resetRotationButton = actionButton("screen.rtsbuilding.blueprints.reset_rotation_short", ACTION_W,
                button -> BlueprintPanel.resetSelectedBlueprintRotation());
        this.detailsButton = actionButton("screen.rtsbuilding.blueprints.details", ACTION_W,
                button -> BlueprintPanel.openMaterialDialog());
        this.buildButton = actionButton("screen.rtsbuilding.blueprints.build_preview", ACTION_W,
                button -> BlueprintPanel.confirmPinnedPreview());
        this.clearButton = actionButton("screen.rtsbuilding.blueprints.capture_cancel", ACTION_W,
                button -> BlueprintPanel.clearSelectedBlueprint());
        this.applyPositionButton = actionButton("screen.rtsbuilding.blueprints.apply_short", COMPACT_ACTION_W,
                button -> commitPinnedPositionDraft());
        this.sizePlusButtons = axisButtons(true, true);
        this.sizeMinusButtons = axisButtons(false, true);
        this.posPlusButtons = axisButtons(true, false);
        this.posMinusButtons = axisButtons(false, false);
        this.nudgeForwardButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_forward_short", 0, 1, 0);
        this.nudgeBackButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_back_short", 0, -1, 0);
        this.nudgeLeftButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_left_short", -1, 0, 0);
        this.nudgeRightButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_right_short", 1, 0, 0);
        this.nudgeUpButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_y_plus_short", 0, 0, 1);
        this.nudgeDownButton = nudgeButton("screen.rtsbuilding.blueprints.nudge_y_minus_short", 0, 0, -1);
    }

    private WindowButton actionButton(String key, int width, WindowButton.OnPress onPress) {
        return new WindowButton(0, 0, width, BUTTON_H, Component.translatable(key), onPress);
    }

    private WindowButton[] axisButtons(boolean plus, boolean sizeButtons) {
        WindowButton[] buttons = new WindowButton[3];
        for (int i = 0; i < buttons.length; i++) {
            int axis = i;
            buttons[i] = new WindowButton(0, 0, SMALL_BUTTON_W, BUTTON_H,
                    Component.literal(plus ? "+" : "-"),
                    button -> {
                        if (sizeButtons) {
                            int delta = plus ? 1 : -1;
                            if (axis == 0) BlueprintPanel.adjustCaptureSize(delta, 0, 0);
                            if (axis == 1) BlueprintPanel.adjustCaptureSize(0, delta, 0);
                            if (axis == 2) BlueprintPanel.adjustCaptureSize(0, 0, delta);
                            syncCaptureSizeInputs(true);
                        } else {
                            commitPinnedPositionDraft();
                            int delta = plus ? 1 : -1;
                            if (axis == 0) BlueprintPanel.nudgePinnedAnchor(delta, 0, 0, this.controller);
                            if (axis == 1) BlueprintPanel.nudgePinnedAnchor(0, delta, 0, this.controller);
                            if (axis == 2) BlueprintPanel.nudgePinnedAnchor(0, 0, delta, this.controller);
                            syncPinnedPositionInputs(true);
                        }
                    });
        }
        return buttons;
    }

    private WindowButton nudgeButton(String key, int rightSteps, int forwardSteps, int upSteps) {
        return new WindowButton(0, 0, COMPACT_ACTION_W, BUTTON_H, Component.translatable(key),
                button -> {
                    BlueprintPanel.nudgePinnedAnchorRelative(rightSteps, forwardSteps, upSteps, this.controller);
                    syncPinnedPositionInputs(true);
                });
    }

    private void syncCaptureInputs() {
        if (!BlueprintPanel.isCaptureModeActive()) {
            this.captureNameInitialized = false;
            return;
        }
        if (!this.captureNameInitialized) {
            this.captureNameInput.setValue("captured_" + Util.getMillis());
            this.captureNameInitialized = true;
        }
        syncCaptureSizeInputs(false);
    }

    private void syncCaptureSizeInputs(boolean force) {
        if (!BlueprintPanel.isCaptureSelectionComplete()) {
            if (force || !this.sizeXInput.isFocused()) this.sizeXInput.setValue("");
            if (force || !this.sizeYInput.isFocused()) this.sizeYInput.setValue("");
            if (force || !this.sizeZInput.isFocused()) this.sizeZInput.setValue("");
            return;
        }
        if (force || !this.sizeXInput.isFocused()) this.sizeXInput.setValue(Integer.toString(BlueprintPanel.captureSizeX()));
        if (force || !this.sizeYInput.isFocused()) this.sizeYInput.setValue(Integer.toString(BlueprintPanel.captureSizeY()));
        if (force || !this.sizeZInput.isFocused()) this.sizeZInput.setValue(Integer.toString(BlueprintPanel.captureSizeZ()));
    }

    private void syncPlacementInputs() {
        syncPinnedPositionInputs(false);
    }

    private void syncPinnedPositionInputs(boolean force) {
        BlockPos anchor = BlueprintPanel.getPinnedAnchor();
        if (anchor == null) {
            if (force || !this.posXInput.isFocused()) this.posXInput.setValue("");
            if (force || !this.posYInput.isFocused()) this.posYInput.setValue("");
            if (force || !this.posZInput.isFocused()) this.posZInput.setValue("");
            return;
        }
        if (force || !this.posXInput.isFocused()) this.posXInput.setValue(Integer.toString(anchor.getX()));
        if (force || !this.posYInput.isFocused()) this.posYInput.setValue(Integer.toString(anchor.getY()));
        if (force || !this.posZInput.isFocused()) this.posZInput.setValue(Integer.toString(anchor.getZ()));
    }

    private int parsePositive(String value, int fallback) {
        return Math.max(1, parseAnyInt(value, Math.max(1, fallback)));
    }

    private int parseNonNegative(String value, int fallback) {
        return Math.max(0, parseAnyInt(value, Math.max(0, fallback)));
    }

    private int parseAnyInt(String value, int fallback) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return fallback;
        }
        try {
            return Mth.clamp(Integer.parseInt(value), -30000000, 30000000);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean isAltDown() {
        long window = this.screen.getMinecraft().getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }
}
