package com.rtsbuilding.rtsbuilding.client.state;


import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.screen.ScreenShapeController;
import com.rtsbuilding.rtsbuilding.client.screen.quickbuild.QuickBuildPanel;
import com.rtsbuilding.rtsbuilding.client.screen.shape.ShapeBuildTypes;
import com.rtsbuilding.rtsbuilding.client.screen.ultimine.UltiminePanel;
import net.minecraft.util.Mth;

import java.util.Locale;

import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.DEFAULT_RTS_GUI_SCALE;
import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.MAX_RTS_GUI_SCALE;
import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.MIN_RTS_GUI_SCALE;
import static com.rtsbuilding.rtsbuilding.client.screen.BuilderScreenConstants.RTS_GUI_SCALE_STEP;

/**
 * 管理 {@link BuilderScreen} 的持久化 UI 偏好。
 *
 * <p>负责加载/保存客户端 UI 状态：面板可见性、形状设置、相机偏好、
 * 调试开关、固定 GUI 缩放比例。实际 I/O 委托给 {@link RtsClientUiStateStore}。
 *
 * <p>不涉及渲染顺序、输入路由、容器覆层行为或游戏玩法变更。
 */
public final class RtsScreenUiStateManager {
    private final ClientRtsController controller;
    private final ScreenShapeController shapeController;
    private final QuickBuildPanel quickBuildPanel;
    private final UltiminePanel ultiminePanel;

    // 运行时状态（不直接持久化，而是经由 store 同步）
    private boolean debugButtonVisible = false;
    private double fixedRtsGuiScale = DEFAULT_RTS_GUI_SCALE;

    public RtsScreenUiStateManager(
            ClientRtsController controller,
            ScreenShapeController shapeController,
            QuickBuildPanel quickBuildPanel,
            UltiminePanel ultiminePanel) {
        this.controller = controller;
        this.shapeController = shapeController;
        this.quickBuildPanel = quickBuildPanel;
        this.ultiminePanel = ultiminePanel;
    }

    // ====== Debug 按钮 ======

    public boolean isDebugButtonVisible() {
        return this.debugButtonVisible;
    }

    public void toggleDebugButton() {
        this.debugButtonVisible = !this.debugButtonVisible;
    }

    // ====== GUI 缩放 ======

    public double fixedRtsGuiScale() {
        return this.fixedRtsGuiScale;
    }

    public void adjustRtsGuiScale(double delta) {
        this.fixedRtsGuiScale = sanitizeRtsGuiScale(this.fixedRtsGuiScale + delta);
        persistUiState();
    }

    /** 返回格式化的缩放标签，如 "2x" 或 "2.5x"。 */
    public String rtsGuiScaleLabel() {
        double scale = sanitizeRtsGuiScale(this.fixedRtsGuiScale);
        if (Math.abs(scale - Math.rint(scale)) < 0.001D) {
            return String.format(Locale.ROOT, "%.0fx", scale);
        }
        return String.format(Locale.ROOT, "%.1fx", scale);
    }

    // ====== 加载 / 持久化 ======

    /** 从持久化存储读取状态，应用到所有控制器/面板。 */
    public void applyStoredUiState() {
        RtsClientUiStateStore.UiState state = RtsClientUiStateStore.load();
        applyPanelState(state);
        applyCameraState(state);
        applyInputState(state);
        applyShapeState(state);
        applyMiscState(state);
    }

    /** 将当前运行时状态写回持久化存储。 */
    public void persistUiState() {
        RtsClientUiStateStore.UiState state = RtsClientUiStateStore.load();
        state.buildShape = this.controller.getBuildShape().name();
        state.fillMode = this.shapeController.getShapeFillMode().name();
        state.rotationDegrees = this.shapeController.getShapeRotateDegrees();
        state.quickBuildOpen = this.quickBuildPanel.isQuickBuildOpen();
        state.ultimineOpen = this.ultiminePanel.isOpen();
        state.ultimineLimit = this.ultiminePanel.getLimit();
        state.chunkCurtainVisible = this.controller.isChunkCurtainVisible();
        state.rtsGuiScale = sanitizeRtsGuiScale(this.fixedRtsGuiScale);
        state.inputSensitivityIndex = this.controller.getInputSensitivityIndex();
        state.startCameraAtPlayerHead = this.controller.isStartCameraAtPlayerHead();
        state.allowPlacedBlockRecovery = this.controller.isAllowPlacedBlockRecovery();
        state.invertPanDragX = this.controller.isInvertPanDragX();
        state.invertPanDragY = this.controller.isInvertPanDragY();
        state.smoothCamera = this.controller.isSmoothCamera();
        state.damageSoundEnabled = this.controller.isDamageSoundEnabled();
        state.damageAutoReturnEnabled = this.controller.isDamageAutoReturnEnabled();
        state.debugButtonVisible = this.debugButtonVisible;
        RtsClientUiStateStore.save(state);
    }

    // ====== apply* 拆分 ======

    /** 恢复面板打开状态与限制值。 */
    private void applyPanelState(RtsClientUiStateStore.UiState state) {
        this.quickBuildPanel.setQuickBuildOpen(state.quickBuildOpen);
        this.ultiminePanel.applyOpenState(state.ultimineOpen);
        this.ultiminePanel.setLimit(state.ultimineLimit);
    }

    /** 恢复相机、区块帷幕等视觉偏好。 */
    private void applyCameraState(RtsClientUiStateStore.UiState state) {
        this.controller.setStartCameraAtPlayerHead(state.startCameraAtPlayerHead);
        this.controller.setAllowPlacedBlockRecovery(state.allowPlacedBlockRecovery);
        this.controller.setSmoothCamera(state.smoothCamera);
        this.controller.setDamageSoundEnabled(state.damageSoundEnabled);
        this.controller.setDamageAutoReturnEnabled(state.damageAutoReturnEnabled);
        this.controller.setChunkCurtainVisible(state.chunkCurtainVisible);
    }

    /** 恢复输入灵敏度与拖拽反转。 */
    private void applyInputState(RtsClientUiStateStore.UiState state) {
        this.controller.setInvertPanDragX(state.invertPanDragX);
        this.controller.setInvertPanDragY(state.invertPanDragY);
        applyInputSensitivity(state.inputSensitivityIndex);
    }

    /** 将存储的灵敏度索引转换为 0~1 分数后应用到控制器。 */
    private void applyInputSensitivity(int index) {
        int presetCount = Math.max(1, this.controller.getInputSensitivityPresetCount());
        if (presetCount <= 1) {
            this.controller.setInputSensitivityByFraction(0.0D);
            return;
        }
        int clamped = Mth.clamp(index, 0, presetCount - 1);
        double fraction = (double) clamped / (double) (presetCount - 1);
        this.controller.setInputSensitivityByFraction(fraction);
    }

    /** 恢复形状模式、填充模式与旋转角度。 */
    private void applyShapeState(RtsClientUiStateStore.UiState state) {
        parseAndSetBuildShape(state.buildShape);
        parseAndSetFillMode(state.fillMode);
        this.shapeController.rotateToDegrees(Math.floorMod(state.rotationDegrees, 360));
        this.shapeController.ensureFillModeForShape(this.controller.getBuildShape());
    }

    private void parseAndSetBuildShape(String name) {
        try {
            this.controller.setBuildShape(ClientRtsController.BuildShape.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            this.controller.setBuildShape(ClientRtsController.BuildShape.BLOCK);
        }
    }

    private void parseAndSetFillMode(String name) {
        try {
            this.shapeController.setShapeFillMode(ShapeBuildTypes.ShapeFillMode.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            this.shapeController.setShapeFillMode(ShapeBuildTypes.ShapeFillMode.FILL);
        }
    }

    /** 恢复调试按钮与 GUI 缩放。 */
    private void applyMiscState(RtsClientUiStateStore.UiState state) {
        this.debugButtonVisible = state.debugButtonVisible;
        this.fixedRtsGuiScale = sanitizeRtsGuiScale(state.rtsGuiScale);
    }

    // ====== 缩放工具 ======

    /** 将 scale 快照到合法区间并按步长取整。 */
    private static double sanitizeRtsGuiScale(double scale) {
        if (!Double.isFinite(scale)) {
            return DEFAULT_RTS_GUI_SCALE;
        }
        double snapped = Math.round(scale / RTS_GUI_SCALE_STEP) * RTS_GUI_SCALE_STEP;
        return Math.max(MIN_RTS_GUI_SCALE, Math.min(MAX_RTS_GUI_SCALE, snapped));
    }
}
