package com.rtsbuilding.rtsbuilding.client.record;

/**
 * 范围挖掘的三维边界计算结果。
 * 客户端预览和服务端确认共用此结构，消除重复计算。
 */
public record AreaMineBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
}
