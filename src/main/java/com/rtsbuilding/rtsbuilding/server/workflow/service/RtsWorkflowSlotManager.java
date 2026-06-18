package com.rtsbuilding.rtsbuilding.server.workflow.service;

import com.rtsbuilding.rtsbuilding.server.workflow.core.IWorkflowEngine;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEntry;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowPriority;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

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
     * Lock for all mutations and reads of {@link #entries} and
     * {@link #entryIndex}.  The lock is reentrant so that internal
     * calls (e.g. {@code isFull()} from {@code addEntry()}) work
     * correctly.
     */
    private final Object lock = new Object();

    /**
     * Priority-ordered list of entries.  The list is the single source of
     * truth for ordering and iteration; the {@link #entryIndex} map provides
     * O(1) lookups by immutable entry ID.
     *
     * <p><b>Access must be guarded by {@link #lock}.</b></p>
     */
    private final List<RtsWorkflowEntry> entries = new ArrayList<>(MAX_SLOTS);

    /**
     * O(1) entry lookup by immutable ID, kept in sync with {@link #entries}.
     *
     * <p><b>Access must be guarded by {@link #lock}.</b></p>
     */
    private final Map<Integer, RtsWorkflowEntry> entryIndex = new HashMap<>();

    private int nextId;

    // ──────────────────────────────────────────────────────────────────
    //  Capacity
    // ──────────────────────────────────────────────────────────────────

    /** Returns {@code true} if all slots are occupied. */
    public boolean isFull() {
        synchronized (lock) {
            return entries.size() >= MAX_SLOTS;
        }
    }

    /** Returns the number of occupied slots (active + suspended). */
    public int occupiedCount() {
        synchronized (lock) {
            int count = 0;
            for (RtsWorkflowEntry e : entries) {
                if (e.isOccupied()) count++;
            }
            return count;
        }
    }

    /** Returns the number of active (non-suspended) entries. */
    public int activeCount() {
        synchronized (lock) {
            int count = 0;
            for (RtsWorkflowEntry e : entries) {
                if (e.hasActiveWorkflow()) count++;
            }
            return count;
        }
    }

    /** Returns the total number of entries in the list (including empty slots). */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
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
        synchronized (lock) {
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
    }

    /**
     * Removes the entry at the given index.
     *
     * @param index the 0-based positional index
     */
    public void removeEntry(int index) {
        synchronized (lock) {
            if (index >= 0 && index < entries.size()) {
                RtsWorkflowEntry removed = entries.remove(index);
                entryIndex.remove(removed.id());
            }
        }
    }

    /**
     * Removes the entry identified by its immutable ID.
     *
     * @param entryId the immutable entry ID
     * @return {@code true} if an entry was removed
     */
    public boolean removeEntryById(int entryId) {
        synchronized (lock) {
            RtsWorkflowEntry entry = entryIndex.remove(entryId);
            if (entry == null) return false;
            entries.remove(entry);
            return true;
        }
    }

    /**
     * Returns the entry at the given positional index.
     */
    public @Nullable RtsWorkflowEntry getEntry(int index) {
        synchronized (lock) {
            if (index >= 0 && index < entries.size()) {
                return entries.get(index);
            }
            return null;
        }
    }

    /**
     * Finds the current positional index of an entry by its immutable ID.
     *
     * @return the 0-based index, or -1 if not found
     */
    public int findIndexByEntryId(int entryId) {
        synchronized (lock) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id() == entryId) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Finds an entry by its immutable ID.
     *
     * @return the entry, or {@code null} if not found
     */
    public @Nullable RtsWorkflowEntry findEntryById(int entryId) {
        synchronized (lock) {
            return entryIndex.get(entryId);
        }
    }

    /**
     * Returns the most recent active (non-suspended) entry.
     */
    public @Nullable RtsWorkflowEntry lastActive() {
        synchronized (lock) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                RtsWorkflowEntry e = entries.get(i);
                if (e.hasActiveWorkflow()) return e;
            }
            return null;
        }
    }

    /**
     * Returns the most recent suspended entry.
     */
    public @Nullable RtsWorkflowEntry lastSuspended() {
        synchronized (lock) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                RtsWorkflowEntry e = entries.get(i);
                if (e.isOccupied() && e.suspended()) return e;
            }
            return null;
        }
    }

    /**
     * Returns {@code true} if any entry is active (non-suspended).
     */
    public boolean hasActiveWorkflow() {
        synchronized (lock) {
            for (RtsWorkflowEntry e : entries) {
                if (e.hasActiveWorkflow()) return true;
            }
            return false;
        }
    }

    /**
     * Returns {@code true} if any entry is suspended.
     */
    public boolean hasSuspendedWorkflow() {
        synchronized (lock) {
            for (RtsWorkflowEntry e : entries) {
                if (e.isOccupied() && e.suspended()) return true;
            }
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  NBT serialisation
    // ──────────────────────────────────────────────────────────────────

    private static final String NBT_NEXT_ID = "next_id";
    private static final String NBT_ENTRIES = "entries";

    /**
     * Serialises this slot manager (all entries + nextId) into a {@link CompoundTag}.
     */
    public CompoundTag saveToNbt() {
        synchronized (lock) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(NBT_NEXT_ID, nextId);
            ListTag entriesList = new ListTag();
            for (RtsWorkflowEntry entry : entries) {
                if (entry.isOccupied()) {
                    entriesList.add(entry.toNbt());
                }
            }
            tag.put(NBT_ENTRIES, entriesList);
            return tag;
        }
    }

    /**
     * Restores a slot manager from a previously serialised {@link CompoundTag}.
     *
     * @param tag the NBT tag previously produced by {@link #saveToNbt()}
     * @return a new slot manager with all entries restored
     */
    public static RtsWorkflowSlotManager loadFromNbt(CompoundTag tag) {
        RtsWorkflowSlotManager manager = new RtsWorkflowSlotManager();
        manager.nextId = tag.getInt(NBT_NEXT_ID);
        if (tag.contains(NBT_ENTRIES, Tag.TAG_LIST)) {
            ListTag entriesList = tag.getList(NBT_ENTRIES, Tag.TAG_COMPOUND);
            for (int i = 0; i < entriesList.size(); i++) {
                RtsWorkflowEntry entry = RtsWorkflowEntry.fromNbt(entriesList.getCompound(i));
                if (entry.isOccupied()) {
                    manager.entries.add(entry);
                    manager.entryIndex.put(entry.id(), entry);
                }
            }
        }
        return manager;
    }

    // ──────────────────────────────────────────────────────────────────
    //  Bulk operations
    // ──────────────────────────────────────────────────────────────────

    /** Returns a snapshot list of all occupied entries. */
    public List<RtsWorkflowEntry> occupiedEntries() {
        synchronized (lock) {
            List<RtsWorkflowEntry> result = new ArrayList<>();
            for (RtsWorkflowEntry e : entries) {
                if (e.isOccupied()) result.add(e);
            }
            return result;
        }
    }

    /** Returns an unmodifiable view of all entries (including idle slots). */
    public List<RtsWorkflowEntry> allEntries() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    /** Removes all entries. */
    public void clear() {
        synchronized (lock) {
            entries.clear();
            entryIndex.clear();
        }
    }

    /**
     * Removes entries that have been idle beyond the given timeout.
     *
     * @param maxIdleMillis maximum allowed idle time in milliseconds
     * @return list of removed entry IDs
     */
    public List<Integer> removeStaleEntries(long maxIdleMillis) {
        synchronized (lock) {
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
}
