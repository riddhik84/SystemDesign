package com.systemdesign.strava.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * StatsCalculatorTest: plain unit tests (no Spring context, no mocks) for the geodesic
 * distance helper used to turn consecutive GPS samples into a running distance total.
 *
 * The haversine formula is a pure function of its four coordinate arguments, so the
 * calculator is simply instantiated directly.
 */
class StatsCalculatorTest {

    private final StatsCalculator statsCalculator = new StatsCalculator();

    @Test
    void oneThousandthDegreeLatitudeIsApproximately111Meters() {
        // 0.001 deg of latitude ~= 111 m anywhere on Earth (latitude lines are evenly spaced).
        double distance = statsCalculator.haversineMeters(37.7749, -122.4194, 37.7759, -122.4194);

        assertThat(distance).isCloseTo(111.2, within(1.5));
    }

    @Test
    void identicalPointsHaveZeroDistance() {
        double distance = statsCalculator.haversineMeters(37.7749, -122.4194, 37.7749, -122.4194);

        assertThat(distance).isCloseTo(0.0, within(1e-6));
    }
}
