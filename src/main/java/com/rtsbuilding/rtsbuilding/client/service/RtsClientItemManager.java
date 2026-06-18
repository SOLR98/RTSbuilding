package com.rtsbuilding.rtsbuilding.client.service;

import com.rtsbuilding.rtsbuilding.client.cache.RtsClientInventoryCache;
import com.rtsbuilding.rtsbuilding.client.network.RtsClientPacketGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端物品管理器 —— 统一管理 RTS 交互和挖掘操作的物品选择、移动与生命周期。
 *
 * <h3>职责划分</h3>
 * <ul>
 *   <li><b>客户端</b>（本类）：旧手持 → 背包、关联存储拾取、归还关联存储、乐观缓存更新。</li>
 *   <li><b>服务端</b>：校验并执行拾取/归还，推送 delta 纠正客户端的乐观误差。</li>
 * </ul>
 *
 * <h3>乐观更新契约</h3>
 * 拾取/归还调用立即更新本地库存缓存。服务端后续推送 delta
 * （通过 {@link RtsClientInventoryCache}）时缓存自动纠正，误差在一个 tick 内自愈。
 *
 * <h3>挖掘说明</h3>
 * {@link #prepareForMining} 会乐观从关联存储拾取工具（若工具栏中没有）。
 * 挖掘结束后服务端自行调用 {@code returnMiningTool} 并推送 delta——
 * 客户端<b>不再</b>重复发送归还请求，避免高延迟下双份归还的竞态。
 */
public final class RtsClientItemManager {

    public static final RtsClientItemManager INSTANCE = new RtsClientItemManager();

    private RtsClientItemManager() {
    }

    // ======================================================================
    //  值类型
    // ======================================================================

    /** 大头针物品快照 */
    public record PinnedItem(String itemId, String label, ItemStack preview, PinSource source) {
        public boolean isEmpty() { return itemId == null || itemId.isBlank(); }
    }

    /** 大头针来源 */
    public enum PinSource {
        NONE,
        STORAGE_BROWSER,
        RECENT,
        QUICK_SLOT,
        CRAFT_TERMINAL,
        HOTBAR
    }

    /** 手持物品来源 */
    public enum ItemOrigin {
        LINKED,
        PLAYER_INVENTORY,
        HOTBAR,
        UNKNOWN
    }

    // ======================================================================
    //  大头针物品状态
    // ======================================================================

    private String pinnedItemId;
    private String pinnedLabel;
    private ItemStack pinnedPreview = ItemStack.EMPTY;
    private PinSource pinnedSource = PinSource.NONE;

    public PinnedItem getPinnedItem() {
        return new PinnedItem(pinnedItemId, pinnedLabel, pinnedPreview, pinnedSource);
    }

    public void setPinnedItem(String itemId, ItemStack preview, String label, PinSource source) {
        this.pinnedItemId = itemId;
        this.pinnedPreview = preview == null ? ItemStack.EMPTY : preview.copy();
        this.pinnedLabel = label;
        this.pinnedSource = source;
    }

    public void clearPinnedItem() {
        this.pinnedItemId = null;
        this.pinnedLabel = null;
        this.pinnedPreview = ItemStack.EMPTY;
        this.pinnedSource = PinSource.NONE;
    }

    public boolean hasPinnedItem() {
        return pinnedItemId != null && !pinnedItemId.isBlank();
    }

    public String getPinnedItemId() {
        return pinnedItemId;
    }

    public ItemStack getPinnedItemPreview() {
        return pinnedPreview.isEmpty() ? ItemStack.EMPTY : pinnedPreview.copy();
    }

    // ======================================================================
    //  手持物品追踪
    // ======================================================================

    private String expectedCarriedItemId;
    private ItemOrigin carriedOrigin = ItemOrigin.UNKNOWN;
    private long acquiredAtMs;

    public String getExpectedCarriedItemId() {
        return expectedCarriedItemId;
    }

    public ItemOrigin getCarriedOrigin() {
        return carriedOrigin;
    }

    public boolean isCarriedLinkedItem() {
        return carriedOrigin == ItemOrigin.LINKED && expectedCarriedItemId != null;
    }

    public boolean isCarriedLinkedItem(String itemId) {
        return isCarriedLinkedItem() && expectedCarriedItemId.equals(itemId);
    }

    // ======================================================================
    //  直接拾取 / 归还（thin wrapper over gateway）
    // ======================================================================

    /**
     * 从关联存储请求拾取指定数量物品，同时乐观减少本地缓存计数。
     */
    public void pickupFromLinked(ItemStack prototype, int amount) {
        if (prototype == null || prototype.isEmpty()) return;
        int count = Math.max(1, amount);
        RtsClientInventoryCache.INSTANCE.applyDelta(prototype.getItem().toString(), -count);
        RtsClientPacketGateway.sendPickupLinkedToCarried(prototype, count);
    }

    /**
     * 将手持物品归还到关联存储，同时乐观增加本地缓存计数。
     */
    public void returnCarriedToLinked(String itemId, int amount) {
        if (itemId == null || itemId.isBlank() || amount <= 0) return;
        RtsClientInventoryCache.INSTANCE.applyDelta(itemId, amount);
        RtsClientPacketGateway.sendReturnCarriedToLinked(itemId, amount);
    }

    // ======================================================================
    //  交互前生命周期
    // ======================================================================

    /**
     * 将当前手持物品移入玩家背包（如有），然后从关联存储拾取大头针物品。
     * 在发送 SOURCE_PIN_ITEM 类型的交互载荷前调用。
     */
    public void prepareForInteract() {
        storeOldCarriedToBackpack();
        PinnedItem item = getPinnedItem();
        if (item.isEmpty()) return;
        pickupFromLinked(item.preview(), 1);
        this.expectedCarriedItemId = item.itemId();
        this.carriedOrigin = ItemOrigin.LINKED;
        this.acquiredAtMs = System.currentTimeMillis();
    }

    /**
     * 交互完成后，将手上剩余的物品归还到关联存储。
     * 若物品已被消耗，服务端 delta 会在一个 tick 内纠正乐观增量。
     */
    public void cleanupAfterInteract() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(carried.getItem());
        if (id == null) return;
        returnCarriedToLinked(id.toString(), carried.getCount());
    }

    // ======================================================================
    //  挖掘前生命周期
    // ======================================================================

    /**
     * 将旧手持物品移入背包，然后从关联存储拾取挖掘工具（若工具栏中没有）。
     * 在发送 mine/ultimine/areaMine 启动载荷前调用。
     *
     * @param toolItemId  挖掘工具的注册表字符串 ID
     * @param toolPrototype  代表性物品栈（用于匹配和拾取）
     * @param toolSlot  玩家选择的工具栏槽位
     */
    public void prepareForMining(String toolItemId, ItemStack toolPrototype, int toolSlot) {
        storeOldCarriedToBackpack();
        if (toolItemId == null || toolItemId.isBlank()) return;
        ResourceLocation id = ResourceLocation.tryParse(toolItemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;
        Item toolItem = BuiltInRegistries.ITEM.get(id);

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        int clampedSlot = Math.max(0, Math.min(8, toolSlot));
        ItemStack slotStack = player.getInventory().getItem(clampedSlot);
        if (!slotStack.isEmpty() && slotStack.getItem() == toolItem) {
            // 工具已在工具栏中 —— 服务端将直接使用
            this.expectedCarriedItemId = toolItemId;
            this.carriedOrigin = ItemOrigin.HOTBAR;
            return;
        }

        // 工具不在工具栏 —— 尝试关联存储
        long cachedCount = RtsClientInventoryCache.INSTANCE.getCount(toolItemId);
        if (cachedCount <= 0L) return;

        ItemStack prototype = toolPrototype != null ? toolPrototype.copy() : new ItemStack(toolItem);
        prototype.setCount(1);
        pickupFromLinked(prototype, 1);
        this.expectedCarriedItemId = toolItemId;
        this.carriedOrigin = ItemOrigin.LINKED;
        this.acquiredAtMs = System.currentTimeMillis();
    }

    /**
     * 挖掘后无操作。服务端自行调用 {@code returnMiningTool} 并推送 delta，
     * 客户端不得重复发送归还，否则高延迟下可能出现双份归还。
     */
    public void cleanupAfterMining() {
        // 服务端 delta 已处理 —— 此处无操作
    }

    // ======================================================================
    //  内部辅助
    // ======================================================================

    /**
     * 将当前手持物品栈移入玩家背包。背包满时余量归还关联存储。
     */
    private void storeOldCarriedToBackpack() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) return;
        player.getInventory().add(carried);
        if (!player.containerMenu.getCarried().isEmpty()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(player.containerMenu.getCarried().getItem());
            if (id != null) {
                returnCarriedToLinked(id.toString(), player.containerMenu.getCarried().getCount());
            }
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }
}
