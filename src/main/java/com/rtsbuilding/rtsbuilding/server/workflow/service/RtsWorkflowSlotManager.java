package com.rtsbuilding.rtsbuilding.server.workflow.service;

import com.rtsbuilding.rtsbuilding.server.workflow.core.IWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEntry;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowPriority;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Manages the fixed-size workflow slot pool for a single player.
 *
 * <p>Each player has up to {@link #MAX_SLOTS} workflow slots.  Entries are
 * stored as a priority-ordered list: higher-priority entries appear before
 * lower-priority ones.  Within the same priority level, insertion order
 * (FIFO) is preserved.  When an entry is removed, later entries shift
 * forward — but the immutable {@link RtsWorkflowEntry#id()} survives
 * these shifts.</p>
 *
 * <p>This class is intentionally a simple container; all coordination logic
 * lives in {@link IWorkflowEngine}.</p>
 */
public final class RtsWorkflowSlotManager {

    /** Maximum number of concurrent workflow entries per player. */
    public static final int MAX_SLOTS = 8;

    /**
     * Priority-ordered list of entries.  The list is the single source of
     * truth for ordering and iteration; the {@link #entryIndex} map provides
     * O(1) lookups by immutable entry ID.
     */
    private final List<RtsWorkflowEntry> entries = new ArrayList<>(MAX_SLOTS);

    /** O(1) entry lookup by immutable ID, kept in sync with {@link #entries}. */
    private final Map<Integer, RtsWorkflowEntry> entryIndex = new HashMap<>();

    private int nextId;

    // ──────────────────────────────────────────────────────────────────
    //  Capacity
    // ──────────────────────────────────────────────────────────────────

    /** Returns {@code true} if all slots are occupied. */
    public boolean isFull() {
        return entries.size() >= MAX_SLOTS;
    }

    /** Returns the number of occupied slots (active + suspended). */
    public int occupiedCount() {
        int count = 0;
        for (RtsWorkflowEntry e : entries) {
            if (e.isOccupied()) count++;
        }
        return count;
    }

    /** Returns the number of active (non-suspended) entries. */
    public int activeCount() {
        int count = 0;
        for (RtsWorkflowEntry e : entries) {
            if (e.hasActiveWorkflow()) count++;
        }
        return count;
    }

    /** Returns the total number of entries in the list (including empty slots). */
    public int size() {
        return entries.size();
    }

    // ──────────────────────────────────────────────────────────────────
    //  Entry management
    // ──────────────────────────────────────────────────────────────────

    /**
     * Creates and adds a new workflow entry, inserting it in priority order.
     * <p>Higher-priority entries are placed before lower-priority ones.
     * Within the same priority, FIFO order is preserved.</p>
     *
     * @param priority the priority level for the new entry
     * @return the newly created entry, or {@code null} if at capacity
     */
    public @Nullable RtsWorkflowEntry addEntry(RtsWorkflowPriority priority) {
        if (isFull()) return null;
        RtsWorkflowEntry entry = new RtsWorkflowEntry(nextId++);
        entry.setPriority(priority);
        // Insert in priority order: find the first entry with strictly lower priority
        int insertIndex = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).priority().rank() < priority.rank()) {
                insertIndex = i;
                break;
            }
        }
        entries.add(insertIndex, entry);
        entryIndex.put(entry.id(), entry);
        return entry;
    }

    /**
     * Removes the entry at the given index.
     *
     * @param index the 0-based positional index
     */
    public void removeEntry(int index) {
        if (index >= 0 && index < entries.size()) {
            RtsWorkflowEntry removed = entries.remove(index);
            entryIndex.remove(removed.id());
        }
    }

    /**
     * Removes the entry identified by its immutable ID.
     *
     * @param entryId the immutable entry ID
     * @return {@code true} if an entry was removed
     */
    public boolean removeEntryById(int entryId) {
        RtsWorkflowEntry entry = entryIndex.remove(entryId);
        if (entry == null) return false;
        entries.remove(entry);
        return true;
    }

    /**
     * Returns the entry at the given positional index.
     */
    public @Nullable RtsWorkflowEntry getEntry(int index) {
        if (index >= 0 && index < entries.size()) {
            return entries.get(index);
        }
        return null;
    }

    /**
     * Finds the current positional index of an entry by its immutable ID.
     *
     * @return the 0-based index, or -1 if not found
     */
    public int findIndexByEntryId(int entryId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id() == entryId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds an entry by its immutable ID.
     *
     * @return the entry, or {@code null} if not found
     */
    public @Nullable RtsWorkflowEntry findEntryById(int entryId) {
        return entryIndex.get(entryId);
    }

    /**
     * Returns the most recent active (non-suspended) entry.
     */
    public @Nullable RtsWorkflowEntry lastActive() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            RtsWorkflowEntry e = entries.get(i);
            if (e.hasActiveWorkflow()) return e;
        }
        return null;
    }

    /**
     * Returns the most recent suspended entry.
     */
    public @Nullable RtsWorkflowEntry lastSuspended() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            RtsWorkflowEntry e = entries.get(i);
            if (e.isOccupied() && e.suspended()) return e;
        }
        return null;
    }

    /**
     * Returns {@code true} if any entry is active (non-suspended).
     */
    public boolean hasActiveWorkflow() {
        for (RtsWorkflowEntry e : entries) {
            if (e.hasActiveWorkflow()) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if any entry is suspended.
     */
    public boolean hasSuspendedWorkflow() {
        for (RtsWorkflowEntry e : entries) {
            if (e.isOccupied() && e.suspended()) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Bulk operations
    // ──────────────────────────────────────────────────────────────────

    /** Returns a snapshot list of all occupied entries. */
    public List<RtsWorkflowEntry> occupiedEntries() {
        List<RtsWorkflowEntry> result = new ArrayList<>();
        for (RtsWorkflowEntry e : entries) {
            if (e.isOccupied()) result.add(e);
        }
        return result;
    }

    /** Returns an unmodifiable view of all entries (including idle slots). */
    public List<RtsWorkflowEntry> allEntries() {
        return List.copyOf(entries);
    }

    /** Removes all entries. */
    public void clear() {
        entries.clear();
        entryIndex.clear();
    }

    /**
     * Removes entries that have been idle beyond the given timeout.
     *
     * @param maxIdleMillis maximum allowed idle time in milliseconds
     * @return list of removed entry IDs
     */
    public List<Integer> removeStaleEntries(long maxIdleMillis) {
        List<Integer> removed = new ArrayList<>();
        long now = System.currentTimeMillis();
        Iterator<RtsWorkflowEntry> it = entries.iterator();
        while (it.hasNext()) {
            RtsWorkflowEntry e = it.next();
            if (e.isOccupied() && (now - e.lastUpdatedAt() > maxIdleMillis)) {
                removed.add(e.id());
                entryIndex.remove(e.id());
                it.remove();
            }
        }
        return removed;
    }
}
