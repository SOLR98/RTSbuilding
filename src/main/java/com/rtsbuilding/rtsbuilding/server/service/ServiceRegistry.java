package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.server.service.api.*;
import com.rtsbuilding.rtsbuilding.server.service.impl.*;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferPlayerIntegration;

import javax.annotation.Nullable;

/**
 * 服务注册中心——管理所有 RTS 服务的实例化与生命周期。
 */
public final class ServiceRegistry {

    private static ServiceRegistry INSTANCE;

    private PathfindingService pathfindingService;
    private BindingService bindingService;
    private FunnelService funnelService;
    private PageService pageService;
    private CraftingService craftingService;
    private TransferService transferService;
    private InteractionService interactionService;
    private MiningService miningService;
    private PlacementService placementService;
    private FluidService fluidService;
    private SessionService sessionService;
    private BlueprintService blueprintService;
    private ServiceOperationTemplate serviceOperationTemplate;
    private GlobalStorageService globalStorageService;

    private ServiceRegistry() {
    }

    public static ServiceRegistry init() {
        if (INSTANCE == null) {
            INSTANCE = new ServiceRegistry();
            INSTANCE.initializeServices();
        }
        return INSTANCE;
    }

    private void initializeServices() {
        this.pathfindingService = new RtsPathfindingServiceImpl();
        this.bindingService = new RtsBindingServiceImpl();
        this.funnelService = new RtsFunnelServiceImpl();
        this.pageService = new RtsPageServiceImpl();
        this.craftingService = new RtsCraftingServiceImpl();
        this.transferService = new RtsTransferServiceImpl(this, new RtsTransferPlayerIntegration(this));
        this.interactionService = new RtsInteractionServiceImpl();
        this.miningService = new RtsMiningServiceImpl();
        this.placementService = new RtsPlacementServiceImpl();
        this.fluidService = new RtsFluidServiceImpl();
        this.sessionService = new RtsSessionServiceImpl();
        this.blueprintService = new RtsBlueprintServiceImpl();
        this.serviceOperationTemplate = new ServiceOperationTemplate(this);
        this.globalStorageService = new RtsGlobalStorageServiceImpl();
    }

    public static ServiceRegistry getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("ServiceRegistry not initialized. Call init() first.");
        }
        return INSTANCE;
    }

    // ======================================================================
    // Service 访问器
    // ======================================================================

    public PathfindingService pathfinding() {
        return pathfindingService;
    }

    public BindingService binding() {
        return bindingService;
    }

    public FunnelService funnel() {
        return funnelService;
    }

    public PageService page() {
        return pageService;
    }

    public CraftingService crafting() {
        return craftingService;
    }

    public TransferService transfer() {
        return transferService;
    }

    public InteractionService interaction() {
        return interactionService;
    }

    public MiningService mining() {
        return miningService;
    }

    public PlacementService placement() {
        return placementService;
    }

    public FluidService fluid() {
        return fluidService;
    }

    public SessionService session() {
        return sessionService;
    }

    @Nullable
    public BlueprintService blueprint() {
        return blueprintService;
    }

    public ServiceOperationTemplate serviceOp() {
        return serviceOperationTemplate;
    }

    public GlobalStorageService globalStorage() {
        return globalStorageService;
    }
}
