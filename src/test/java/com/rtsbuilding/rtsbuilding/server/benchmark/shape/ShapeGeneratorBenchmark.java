package com.rtsbuilding.rtsbuilding.server.benchmark.shape;

import com.rtsbuilding.rtsbuilding.common.shape.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Extreme Performance Benchmarks for all area shape generators.
 *
 * <p>Benchmarks each shape type (BOX, SQUARE, CIRCLE, WALL, LINE) at
 * various sizes and fill modes. All position generation is pure math
 * with no Minecraft runtime dependencies beyond BlockPos.</p>
 */
class ShapeGeneratorBenchmark {

    private static final int WARMUP = 3;
    private static final int ITERATIONS = 10;

    private static final BlockPos ORIGIN = BlockPos.ZERO;

    // Shape generators — shared stateless instances
    private static final AreaShapeGenerator BOX_GEN = new BoxShapeGenerator();
    private static final AreaShapeGenerator SQUARE_GEN = new SquareShapeGenerator();
    private static final AreaShapeGenerator CIRCLE_GEN = new CircleShapeGenerator();
    private static final AreaShapeGenerator WALL_GEN = new WallShapeGenerator();
    private static final AreaShapeGenerator LINE_GEN = new LineShapeGenerator();

    @BeforeAll
    static void globalWarmUp() {
        // Warm up BOX at various sizes
        for (int size = 1; size <= 64; size *= 2) {
            BlockPos end = new BlockPos(size, 0, size);
            AreaShapeInput input = AreaShapeInput.of(ORIGIN, end, size, Direction.UP, Direction.UP);
            for (int w = 0; w < WARMUP; w++) {
                BOX_GEN.generatePositions(input, ShapeFillMode.FILL);
                BOX_GEN.generatePositions(input, ShapeFillMode.HOLLOW);
            }
        }

        // Warm up SQUARE
        for (int size = 1; size <= 64; size *= 2) {
            AreaShapeInput input = new AreaShapeInput(ORIGIN, new BlockPos(size, 0, size), 0, Direction.UP, Direction.UP);
            for (int w = 0; w < WARMUP; w++) {
                SQUARE_GEN.generatePositions(input, ShapeFillMode.FILL);
                SQUARE_GEN.generatePositions(input, ShapeFillMode.HOLLOW);
            }
        }

        // Warm up CIRCLE
        for (int r = 1; r <= 32; r *= 2) {
            AreaShapeInput input = AreaShapeInput.of(ORIGIN, new BlockPos(r, 0, r));
            for (int w = 0; w < WARMUP; w++) {
                CIRCLE_GEN.generatePositions(input, ShapeFillMode.FILL);
                CIRCLE_GEN.generatePositions(input, ShapeFillMode.HOLLOW);
            }
        }

        // Warm up WALL
        for (int size = 1; size <= 32; size *= 2) {
            AreaShapeInput input = AreaShapeInput.of(ORIGIN, new BlockPos(size, 0, 0), size, Direction.UP, Direction.UP);
            for (int w = 0; w < WARMUP; w++) {
                WALL_GEN.generatePositions(input, ShapeFillMode.FILL);
                WALL_GEN.generatePositions(input, ShapeFillMode.HOLLOW);
            }
        }

        // Warm up LINE
        for (int len = 1; len <= 64; len *= 2) {
            AreaShapeInput input = AreaShapeInput.of(ORIGIN, new BlockPos(len, len, len));
            for (int w = 0; w < WARMUP; w++) {
                LINE_GEN.generatePositions(input, ShapeFillMode.FILL);
            }
        }
    }

    @BeforeEach
    void setUp() {
        System.gc();
    }

    // ======================================================================
    // BOX shape
    // ======================================================================

    @Test
    void benchmarkBoxSmallFill() {
        runShapeBench("BOX", "FILL", "small(8\u00d78\u00d78)", BOX_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(8, 0, 8), 8, Direction.UP, Direction.UP),
                ShapeFillMode.FILL, ITERATIONS * 1000);
    }

    @Test
    void benchmarkBoxMediumFill() {
        runShapeBench("BOX", "FILL", "medium(32\u00d732\u00d732)", BOX_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(32, 0, 32), 32, Direction.UP, Direction.UP),
                ShapeFillMode.FILL, ITERATIONS * 10);
    }

    @Test
    void benchmarkBoxLargeFill() {
        runShapeBench("BOX", "FILL", "large(64\u00d764\u00d764)", BOX_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(64, 0, 64), 64, Direction.UP, Direction.UP),
                ShapeFillMode.FILL, ITERATIONS);
    }

    @Test
    void benchmarkBoxHollow() {
        runShapeBench("BOX", "HOLLOW", "medium(32\u00d732\u00d732)", BOX_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(32, 0, 32), 32, Direction.UP, Direction.UP),
                ShapeFillMode.HOLLOW, ITERATIONS * 10);
    }

    // ======================================================================
    // SQUARE shape
    // ======================================================================

    @Test
    void benchmarkSquareMediumFill() {
        runShapeBench("SQUARE", "FILL", "32\u00d732", SQUARE_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(32, 0, 32), 0, Direction.UP, Direction.UP),
                ShapeFillMode.FILL, ITERATIONS * 100);
    }

    @Test
    void benchmarkSquareLargeHollow() {
        runShapeBench("SQUARE", "HOLLOW", "64\u00d764", SQUARE_GEN,
                new AreaShapeInput(ORIGIN, new BlockPos(64, 0, 64), 0, Direction.UP, Direction.UP),
                ShapeFillMode.HOLLOW, ITERATIONS * 100);
    }

    // ======================================================================
    // CIRCLE shape
    // ======================================================================

    @Test
    void benchmarkCircleSmallFill() {
        runShapeBench("CIRCLE", "FILL", "r=8", CIRCLE_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(8, 0, 8)),
                ShapeFillMode.FILL, ITERATIONS * 100);
    }

    @Test
    void benchmarkCircleLargeFill() {
        runShapeBench("CIRCLE", "FILL", "r=32", CIRCLE_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(32, 0, 32)),
                ShapeFillMode.FILL, ITERATIONS);
    }

    @Test
    void benchmarkCircleHollow() {
        runShapeBench("CIRCLE", "HOLLOW", "r=32", CIRCLE_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(32, 0, 32)),
                ShapeFillMode.HOLLOW, ITERATIONS);
    }

    // ======================================================================
    // WALL shape
    // ======================================================================

    @Test
    void benchmarkWallMediumFill() {
        runShapeBench("WALL", "FILL", "len=16,h=16", WALL_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(16, 0, 0), 16, Direction.UP, Direction.UP),
                ShapeFillMode.FILL, ITERATIONS * 100);
    }

    @Test
    void benchmarkWallLargeHollow() {
        runShapeBench("WALL", "HOLLOW", "len=32,h=32", WALL_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(32, 0, 0), 32, Direction.UP, Direction.UP),
                ShapeFillMode.HOLLOW, ITERATIONS * 10);
    }

    // ======================================================================
    // LINE shape
    // ======================================================================

    @Test
    void benchmarkLineLong() {
        runShapeBench("LINE", "FILL", "len=64", LINE_GEN,
                AreaShapeInput.of(ORIGIN, new BlockPos(64, 64, 64)),
                ShapeFillMode.FILL, ITERATIONS * 1000);
    }

    // ======================================================================
    // AreaOperationExecutor facade
    // ======================================================================

    @Test
    void benchmarkGeneratePlacementPositions() {
        int CALLS = ITERATIONS * 100;

        long totalNanos = 0;
        for (int i = 0; i < CALLS; i++) {
            BlockPos end = new BlockPos(16, 0, 16);
            long start = System.nanoTime();
            com.rtsbuilding.rtsbuilding.common.AreaOperationExecutor.generatePositions(
                    AreaShape.BOX, ORIGIN, end, 8, Direction.UP, ShapeFillMode.FILL);
            long endNanos = System.nanoTime();
            totalNanos += (endNanos - start);
        }
        long avgNanos = totalNanos / CALLS;
        System.out.println(String.format("[Shape] AreaOperationExecutor.generatePositions(BOX, 16x16x8) × %,d: avg %,d ns/op",
                CALLS, avgNanos));
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private void runShapeBench(String shape, String mode, String label,
                                AreaShapeGenerator gen, AreaShapeInput input,
                                ShapeFillMode fillMode, int calls) {
        long totalNanos = 0;
        for (int i = 0; i < calls; i++) {
            long start = System.nanoTime();
            gen.generatePositions(input, fillMode);
            long end = System.nanoTime();
            totalNanos += (end - start);
        }
        long avgNanos = totalNanos / calls;
        System.out.println(String.format("[Shape] %s(%s, %s) \u00d7 %,d: avg %,d ns/op  (%,d positions/op)",
                shape, label, mode, calls, avgNanos, estimatePositionCount(gen, input, fillMode)));
    }

    private static int estimatePositionCount(AreaShapeGenerator gen, AreaShapeInput input, ShapeFillMode mode) {
        return gen.generatePositions(input, mode).size();
    }
}
