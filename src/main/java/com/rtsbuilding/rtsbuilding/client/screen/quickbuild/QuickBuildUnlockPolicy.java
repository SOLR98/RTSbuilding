package com.rtsbuilding.rtsbuilding.client.screen.quickbuild;

import com.rtsbuilding.rtsbuilding.client.screen.ultimine.AreaMineShape;

/**
 * 快速破坏形状的客户端可见性策略。
 *
 * <p>这里只根据服务端已同步的生存平衡状态和插件列表决定按钮是否可用；
 * 最终权限仍由服务端管道校验。把规则集中在这里可以避免连锁挖掘与范围挖掘
 * 再次因为面板默认状态而互相依赖。</p>
 */
final class QuickBuildUnlockPolicy {
    private QuickBuildUnlockPolicy() {
    }

    static boolean canUseDestroyShape(boolean progressionEnabled, boolean chainInstalled,
            boolean areaInstalled, boolean harvestTierInstalled, AreaMineShape shape) {
        if (!progressionEnabled) {
            return true;
        }
        return shape == AreaMineShape.CHAIN
                ? chainInstalled
                : areaInstalled && harvestTierInstalled;
    }

    static boolean canUseAnyDestroyShape(boolean progressionEnabled, boolean chainInstalled,
            boolean areaInstalled, boolean harvestTierInstalled) {
        return !progressionEnabled || chainInstalled || (areaInstalled && harvestTierInstalled);
    }

    static AreaMineShape firstAvailableDestroyShape(boolean progressionEnabled, boolean chainInstalled,
            boolean areaInstalled, boolean harvestTierInstalled) {
        if (!progressionEnabled || chainInstalled) {
            return AreaMineShape.CHAIN;
        }
        return areaInstalled && harvestTierInstalled ? AreaMineShape.BLOCK : null;
    }
}
