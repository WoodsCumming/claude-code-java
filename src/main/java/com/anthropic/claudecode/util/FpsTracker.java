package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tracks per-frame render durations and computes average FPS and 1%-low FPS.
 * Translated from src/utils/fpsTracker.ts
 *
 * <p>Usage: call {@link #record(double)} each time a frame is rendered, then call
 * {@link #getMetrics()} to obtain the aggregated statistics.</p>
 */
public class FpsTracker {

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    /**
     * Aggregated FPS statistics.
     * Translated from {@code FpsMetrics} in fpsTracker.ts.
     *
     * @param averageFps  mean frames-per-second over the entire recording window
     * @param low1PctFps  FPS corresponding to the 1-percentile (worst 1 %) frame duration
     */
    public record FpsMetrics(double averageFps, double low1PctFps) {}

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final List<Double> frameDurations = new ArrayList<>();
    private Long firstRenderTimeNs;
    private Long lastRenderTimeNs;

    // -------------------------------------------------------------------------
    // record
    // -------------------------------------------------------------------------

    /**
     * Records a single frame's render duration.
     * Translated from {@code record(durationMs)} in fpsTracker.ts.
     *
     * @param durationMs time taken to render this frame, in milliseconds
     */
    public void record(double durationMs) {
        long now = System.nanoTime();
        if (firstRenderTimeNs == null) {
            firstRenderTimeNs = now;
        }
        lastRenderTimeNs = now;
        frameDurations.add(durationMs);
    }

    // -------------------------------------------------------------------------
    // getMetrics
    // -------------------------------------------------------------------------

    /**
     * Computes and returns FPS metrics, or {@link Optional#empty()} if no frames
     * have been recorded or the recording window is zero-length.
     * Translated from {@code getMetrics()} in fpsTracker.ts.
     *
     * @return an {@link Optional} containing the metrics, or empty if insufficient data
     */
    public Optional<FpsMetrics> getMetrics() {
        if (frameDurations.isEmpty()
                || firstRenderTimeNs == null
                || lastRenderTimeNs  == null) {
            return Optional.empty();
        }

        double totalTimeMs = (lastRenderTimeNs - firstRenderTimeNs) / 1_000_000.0;
        if (totalTimeMs <= 0.0) {
            return Optional.empty();
        }

        int totalFrames = frameDurations.size();
        double averageFps = totalFrames / (totalTimeMs / 1000.0);

        // Sort descending to find worst (longest) frame times — 1 % percentile
        List<Double> sorted = frameDurations.stream()
                .sorted((a, b) -> Double.compare(b, a))   // descending
                .toList();
        int p99Index = Math.max(0, (int) Math.ceil(sorted.size() * 0.01) - 1);
        double p99FrameTimeMs = sorted.get(p99Index);
        double low1PctFps = p99FrameTimeMs > 0.0 ? 1000.0 / p99FrameTimeMs : 0.0;

        // Round to 2 decimal places (mirrors Math.round(x * 100) / 100 in TS)
        double roundedAvg = Math.round(averageFps  * 100.0) / 100.0;
        double roundedLow = Math.round(low1PctFps  * 100.0) / 100.0;

        return Optional.of(new FpsMetrics(roundedAvg, roundedLow));
    }
}
