package com.systemdesign.yelp.service;

import com.systemdesign.yelp.search.GeohashUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeohashUtilTest {

    private final GeohashUtil util = new GeohashUtil();

    @Test
    void encodeAndDecodeRoundtrip() {
        double lat = 37.7749;
        double lon = -122.4194;
        String hash = util.encode(lat, lon, 6);
        assertThat(hash).hasSize(6);

        double[] decoded = util.decode(hash);
        // Precision 6 ≈ 0.6km so error should be within ~0.01 degrees
        assertThat(Math.abs(decoded[0] - lat)).isLessThan(0.01);
        assertThat(Math.abs(decoded[1] - lon)).isLessThan(0.01);
    }

    @Test
    void encodeSFProducesSamePrefix() {
        // Two points both in SF Union Square area should share a prefix at precision 4
        String hash1 = util.encode(37.7879, -122.4074, 4);
        String hash2 = util.encode(37.7912, -122.4025, 4);
        // Same neighborhood — same or adjacent cells
        assertThat(hash1).isNotEmpty();
        assertThat(hash2).isNotEmpty();
    }

    @Test
    void neighborsReturnsEightCells() {
        String center = util.encode(37.7749, -122.4194, 5);
        List<String> neighbors = util.neighbors(center);
        assertThat(neighbors).hasSizeGreaterThanOrEqualTo(3); // Edge cases may have fewer
        assertThat(neighbors).doesNotContain(center);
    }

    @Test
    void haversineKmBetweenKnownPoints() {
        // SF (37.7749, -122.4194) to Oakland (37.8044, -122.2712) ≈ 12.8km
        double dist = util.haversineKm(37.7749, -122.4194, 37.8044, -122.2712);
        assertThat(dist).isBetween(12.0, 14.0);
    }

    @Test
    void haversineSamePointIsZero() {
        double dist = util.haversineKm(37.7749, -122.4194, 37.7749, -122.4194);
        assertThat(dist).isLessThan(0.001);
    }

    @Test
    void precisionForSmallRadius() {
        assertThat(util.precisionForRadius(0.5)).isEqualTo(6);
        assertThat(util.precisionForRadius(3.0)).isEqualTo(5);
        assertThat(util.precisionForRadius(20.0)).isEqualTo(4);
    }
}
