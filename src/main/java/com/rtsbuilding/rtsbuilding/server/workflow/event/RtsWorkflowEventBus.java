package com.rtsbuilding.rtsbuilding.server.workflow.event;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple thread-safe event bus for workflow lifecycle events.
 *
 * <p>Uses {@link CopyOnWriteArrayList} so listeners can be added/removed
 * safely while events are being dispatched.  Listeners are invoked
 * synchronously on the caller's thread (typically the server tick thread).</p>
 *
 * <p>This is intentionally lightweight — no event queue, no async dispatch,
 * no ordering guarantees beyond FIFO insertion order.</p>
 */
public final class RtsWorkflowEventBus {

    private final List<WorkflowEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Registers a listener.  Idempotent (duplicate registrations are ignored).
     */
    public void addListener(WorkflowEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(WorkflowEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Fires an event to all registered listeners.
     * Exceptions from individual listeners are caught and logged so that
     * a faulty listener does not prevent others from receiving the event.
     *
     * @param event the event to fire (must not be null)
     */
    public void fire(WorkflowEvent event) {
        if (event == null) return;
        for (WorkflowEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                RtsbuildingMod.LOGGER.error(
                        "[WorkflowEventBus] Listener {} threw on event {}: {}",
                        listener.getClass().getSimpleName(), event.type(), e.getMessage(), e);
            }
        }
    }

    /** Returns the number of registered listeners. */
    public int listenerCount() {
        return listeners.size();
    }
}
