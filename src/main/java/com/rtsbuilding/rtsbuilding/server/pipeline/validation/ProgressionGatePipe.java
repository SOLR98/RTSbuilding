package com.rtsbuilding.rtsbuilding.server.pipeline.validation;

import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineContext;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelinePipe;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.PipelineResult;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TypedKey;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;

/**
 * Checks that the player has unlocked the required progression feature.
 *
 * <p>The required feature is injected via the constructor; no context args
 * are consulted at runtime.  This constant is provided for any pipe that
 * <em>writes</em> a feature into context args for downstream consumption.</p>
 */
public final class ProgressionGatePipe implements PipelinePipe<PipelineContext> {

    public static final TypedKey<RtsFeature> ARG_FEATURE = new TypedKey<>("feature", RtsFeature.class);

    private final RtsFeature feature;

    /**
     * @param feature the progression feature required to proceed
     */
    public ProgressionGatePipe(RtsFeature feature) {
        this.feature = feature;
    }

    @Override
    public PipelineResult execute(PipelineContext ctx) {
        if (!RtsProgressionManager.canUse(ctx.player(), feature)) {
            return PipelineResult.failure("Feature not unlocked: " + feature.name());
        }
        return PipelineResult.success();
    }
}
