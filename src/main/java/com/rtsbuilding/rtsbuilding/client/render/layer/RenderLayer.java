package com.rtsbuilding.rtsbuilding.client.render.layer;

import com.rtsbuilding.rtsbuilding.client.util.RtsClientUiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * 一个独立的渲染层，封装了渲染上下文（裁剪区域、z 顺序）。
 *
 * <p>每个层拥有自己的裁剪矩形和可见性控制。调用 {@link #begin} 启用裁剪，
 * 调用 {@link #end} 刷新并恢复。子类只需实现 {@link #renderContent}，
 * 层框架会确保裁剪和刷新的正确性。
 *
 * <h3>物品图标管理</h3>
 * 层提供统一的物品图标渲染方法 {@link #renderSlotItem} 和
 * {@link #renderItemRaw}。所有在该层中渲染的物品图标都应通过这些方法绘制，
 * 以保证深度、着色和计数叠加的一致性。
 *
 * <h3>定制层行为</h3>
 * 子类可覆盖 {@link #begin} 和 {@link #end} 以添加自定义渲染状态。
 * 对于悬浮窗口，{@link com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel}
 * 已通过 {@code createRenderLayer()} 提供了屏幕缩放感知的裁剪实现。
 */
public abstract class RenderLayer implements Comparable<RenderLayer> {

    private int zIndex;
    private boolean visible = true;
    private int clipX, clipY, clipWidth, clipHeight;
    private boolean clippingEnabled;

    /** ARGB 着色应用于该层中渲染的物品图标。默认不透明白色（无着色）。 */
    private int itemTint = 0xFFFFFFFF;

    /** 该层在渲染物品时是否写入深度缓冲。默认 true。 */
    private boolean itemDepthEnabled = true;

    protected RenderLayer(int zIndex) {
        this.zIndex = zIndex;
    }

    // ========================================================================
    //  生命周期
    // ========================================================================

    /** 进入该层：子类可在此设置裁剪/渲染状态。默认启用裁剪。 */
    public void begin(GuiGraphics g) {
        if (clippingEnabled) {
            g.enableScissor(clipX, clipY, clipX + clipWidth, clipY + clipHeight);
        }
    }

    /** 退出该层：刷新绘制缓冲并恢复裁剪。 */
    public void end(GuiGraphics g) {
        g.flush();
        if (clippingEnabled) {
            g.disableScissor();
        }
    }

    /** 子类实现此方法以绘制层内容。{@link #begin} 和 {@link #end} 已自动处理裁剪和刷新。 */
    protected abstract void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick);

    // ========================================================================
    //  物品图标渲染（层统一管理）
    // ========================================================================

    /**
     * 在指定坐标绘制物品图标（不含数量叠加和耐久条）。
     * 适用于需要精确控制渲染位置的场景。
     */
    public void renderItemRaw(GuiGraphics g, ItemStack stack, int x, int y) {
        if (stack.isEmpty()) return;
        g.renderItem(stack, x, y);
    }

    /**
     * 在槽位盒中绘制物品图标 + 数量叠加 + 耐久条。
     *
     * @param g        绘制上下文
     * @param font     字体渲染器（显示数量用）
     * @param stack    物品堆叠
     * @param slotX    槽位左上角 X
     * @param slotY    槽位左上角 Y
     * @param slotSize 槽位边长（图标自动居中）
     * @param count    物品数量（≤1 时不显示叠加）
     */
    public void renderSlotItem(GuiGraphics g, Font font, ItemStack stack,
            int slotX, int slotY, int slotSize, long count) {
        if (stack.isEmpty()) return;

        int itemSize = Math.min(16, slotSize - 2);
        int offset = (slotSize - itemSize) / 2;
        g.renderItem(stack, slotX + offset, slotY + offset);
        g.renderItemDecorations(font, stack, slotX + offset, slotY + offset);

        if (count > 1) {
            RtsClientUiUtil.drawSlotCountOverlay(g, font, slotX, slotY, slotSize,
                    RtsClientUiUtil.compactCount(count), 0xFFF7E6A8);
        }
    }

    /**
     * 便捷方法：在槽位盒中绘制物品图标 + 数量叠加 + 耐久条 + 自定义数量颜色。
     */
    public void renderSlotItem(GuiGraphics g, Font font, ItemStack stack,
            int slotX, int slotY, int slotSize, long count, int countColor) {
        if (stack.isEmpty()) return;

        int itemSize = Math.min(16, slotSize - 2);
        int offset = (slotSize - itemSize) / 2;
        g.renderItem(stack, slotX + offset, slotY + offset);
        g.renderItemDecorations(font, stack, slotX + offset, slotY + offset);

        if (count > 1) {
            RtsClientUiUtil.drawSlotCountOverlay(g, font, slotX, slotY, slotSize,
                    RtsClientUiUtil.compactCount(count), countColor);
        }
    }

    // ========================================================================
    //  链式配置
    // ========================================================================

    public RenderLayer withClip(int x, int y, int width, int height) {
        this.clipX = x;
        this.clipY = y;
        this.clipWidth = width;
        this.clipHeight = height;
        this.clippingEnabled = true;
        return this;
    }

    public RenderLayer withZIndex(int zIndex) {
        this.zIndex = zIndex;
        return this;
    }

    /** 为该层的物品图标设置 ARGB 着色。 */
    public RenderLayer withItemTint(int argb) {
        this.itemTint = argb;
        return this;
    }

    /** 控制该层是否在渲染物品时写入深度缓冲。 */
    public RenderLayer withItemDepth(boolean enabled) {
        this.itemDepthEnabled = enabled;
        return this;
    }

    public RenderLayer setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    // ========================================================================
    //  访问器
    // ========================================================================

    public int getZIndex() {
        return zIndex;
    }

    public boolean isVisible() {
        return visible;
    }

    public int getClipX() { return clipX; }
    public int getClipY() { return clipY; }
    public int getClipWidth() { return clipWidth; }
    public int getClipHeight() { return clipHeight; }

    @Override
    public int compareTo(RenderLayer other) {
        return Integer.compare(this.zIndex, other.zIndex);
    }
}
