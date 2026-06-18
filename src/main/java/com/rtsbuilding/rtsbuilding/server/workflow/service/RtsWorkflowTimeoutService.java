package com.rtsbuilding.rtsbuilding.server.workflow.service;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.server.workflow.core.IWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.service.RtsWorkflowSlotManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically scans all players' workflow slots and removes entries
 * that have been idle beyond a configurable threshold.
 *
 * <p>This prevents "zombie" workflows — entries that were suspended or
 * left behind by disconnected players — from permanently occupying slots.
 * The service is opt-in; call {@link #start(Duration, Duration)} after
 * the engine is initialised.</p>
 *
 * <p>Uses a single daemon background thread for the scan timer.  The
 * actual cleanup logic runs on the server tick thread via the engine.</p>
 */
public final class RtsWorkflowTimeoutService {

    private final IWorkflowEngine engine;
    private final Map<UUID, Map<ResourceKey<Level>, RtsWorkflowSlotManager>> slotManagers;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    /**
     * @param engine       the workflow engine to clean up
     * @param slotManagers the slot managers to scan (same map the engine uses)
     */
    public RtsWorkflowTimeoutService(IWorkflowEngine engine,
                                     Map<UUID, Map<ResourceKey<Level>, RtsWorkflowSlotManager>> slotManagers) {
        this.engine = engine;
        this.slotManagers = slotManagers;
    }

    /**
     * Starts the periodic timeout scan.
     *
     * @param checkInterval how often to scan for stale workflows
     * @param maxIdleTime   maximum allowed time without any progress update
     */
    public void start(Duration checkInterval, Duration maxIdleTime) {
        if (scheduler != null && !scheduler.isShutdown()) {
            return; // already running
        }
        long intervalMs = checkInterval.toMillis();
        long maxIdleMs = maxIdleTime.toMillis();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RTS-Workflow-Timeout");
            t.setDaemon(true);
            return t;
        });

        task = scheduler.scheduleWithFixedDelay(
                () -> scanAndCleanup(maxIdleMs),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        RtsbuildingMod.LOGGER.info("[WorkflowTimeout] Started (interval={}, maxIdle={})",
                checkInterval, maxIdleTime);
    }

    /**
     * Stops the periodic scan.  Idempotent.
     */
    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    /**
     * Performs a single cleanup pass and fires TIMEOUT events.
     *
     * <p>{@code slotManagers} is a {@link ConcurrentHashMap} whose
     * {@code keySet().toArray()} already provides a safe snapshot without
     * external synchronization.  The actual cleanup is delegated to
     * {@link IWorkflowEngine#cleanupStaleWorkflows(Duration)} which iterates
     * all slot managers internally.</p>
     */
    private void scanAndCleanup(long maxIdleMs) {
        int totalRemoved = engine.cleanupStaleWorkflows(Duration.ofMillis(maxIdleMs));

        if (totalRemoved > 0) {
            RtsbuildingMod.LOGGER.info("[WorkflowTimeout] Cleaned up {} stale workflow(s)", totalRemoved);
        }
    }
}
