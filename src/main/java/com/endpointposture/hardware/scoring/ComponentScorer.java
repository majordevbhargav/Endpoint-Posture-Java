package com.endpointposture.hardware.scoring;

/**
 * Scores one hardware component from the agent's raw report.
 *
 * @param <T> the shape of the sub-section this scorer reads (a plain
 *            {@code Map<String,Object>} slice of the raw JSON — kept
 *            loosely typed since the agent's report shape may still
 *            evolve; promote to a typed DTO once it's stable).
 */
public interface ComponentScorer<T> {

    /**
     * @param section the relevant slice of the raw report, or {@code null}
     *                if the agent didn't report anything for this component
     * @return a 0–100 score, or {@code null} if this component genuinely
     *         doesn't apply to this endpoint (e.g. battery on a desktop)
     */
    Integer score(T section);
}