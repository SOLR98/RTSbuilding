package com.rtsbuilding.rtsbuilding.client.render.layer;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 渲染层级管理器，管理所有 {@link RenderLayer} 实例并按 z 顺序绘制。
 *
 * <p>使用方式：
 * <pre>{@code
 * // 注册层
 * manager.addLayer(myLayer);
 *
 * // 每帧调用
 * manager.renderAll(g, mouseX, mouseY, partialTick);
 *
 * // 动态调整 z 顺序
 * manager.setZIndex(myLayer, 100);
 * }</pre>
 */
public final class RenderLayerManager {

    private final List<RenderLayer> layers = new ArrayList<>();
    private boolean needsSort;

    /** 添加一个渲染层。如果已有相同 zIndex 的层，后添加的排在上面。 */
    public void addLayer(RenderLayer layer) {
        if (layer != null && !layers.contains(layer)) {
            layers.add(layer);
            needsSort = true;
        }
    }

    /** 移除一个渲染层。 */
    public void removeLayer(RenderLayer layer) {
        layers.remove(layer);
    }

    /** 按 z 顺序渲染所有可见层。每个层自动管理其裁剪和刷新。 */
    public void renderAll(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (needsSort) {
            Collections.sort(layers);
            needsSort = false;
        }
        for (RenderLayer layer : layers) {
            if (!layer.isVisible()) continue;
            layer.begin(g);
            layer.renderContent(g, mouseX, mouseY, partialTick);
            layer.end(g);
        }
    }

    /** 返回所有可见层中在给定坐标上命中（有裁剪区域包含该点）的顶层（最大 zIndex）。 */
    public RenderLayer hitTest(int mouseX, int mouseY) {
        RenderLayer top = null;
        for (RenderLayer layer : layers) {
            if (!layer.isVisible()) continue;
            int cx = layer.getClipX(), cy = layer.getClipY();
            int cw = layer.getClipWidth(), ch = layer.getClipHeight();
            if (mouseX >= cx && mouseX < cx + cw
                    && mouseY >= cy && mouseY < cy + ch) {
                top = layer;
            }
        }
        return top;
    }

    /** 清除所有层。 */
    public void clear() {
        layers.clear();
        needsSort = true;
    }

    public int size() {
        return layers.size();
    }
}
