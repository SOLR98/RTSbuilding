package com.rtsbuilding.rtsbuilding.server.workflow.model;

/**
 * Unified API processor for workflow progress data.
 *
 * <p>This is the single entry point for UI rendering helpers that consume
 * {@link RtsWorkflowStatus} directly.  With the merge of
 * {@code RtsWorkflowProgressData} into {@code RtsWorkflowStatus},
 * the {@code process()} converter is no longer needed — consumers read
 * pre-computed fields from the status record directly.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Client-side: from an already-received status
 * RtsWorkflowStatus status = ...;
 * String label = RtsWorkflowProgressProcessor.formatLabel(status);
 * String progress = RtsWorkflowProgressProcessor.formatProgressText(status);
 * int fillW = RtsWorkflowProgressProcessor.computeFillWidth(status, barWidth);
 * }</pre>
 */
public final class RtsWorkflowProgressProcessor {

    private RtsWorkflowProgressProcessor() {
    }

    // ======================================================================
    //  Panel rendering helpers
    // ======================================================================

    /**
     * Computes the fill width in pixels for a progress bar of the given width.
     *
     * @param status   the workflow status
     * @param barWidth the total width of the progress bar in pixels
     * @return the fill width in pixels (clamped to [0, barWidth])
     */
    public static int computeFillWidth(RtsWorkflowStatus status, int barWidth) {
        if (status == null || !status.isActive() || status.totalBlocks() <= 0 || barWidth <= 0) {
            return 0;
        }
        float fraction = (float) status.completedBlocks() / (float) status.totalBlocks();
        return Math.min(barWidth, Math.round(barWidth * Math.min(1.0F, fraction)));
    }

    /**
     * Returns a display string showing completed / total, e.g. "45/100".
     */
    public static String formatProgressText(RtsWorkflowStatus status) {
        if (status == null || !status.isActive()) return "";
        return status.progressText();
    }

    /**
     * Returns the display label for this workflow entry, optionally
     * appending a "(suspended)" suffix.
     */
    public static String formatLabel(RtsWorkflowStatus status) {
        if (status == null || !status.isActive()) return "";
        String label = status.typeLabel();
        if (status.suspended()) {
            label += " (搁置)";
        }
        return label;
    }
}
