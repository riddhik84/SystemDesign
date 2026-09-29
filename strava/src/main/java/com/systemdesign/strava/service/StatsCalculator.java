package com.systemdesign.strava.service;

import org.springframework.stereotype.Component;

/**
 * Stateless helper for the geodesic math used to turn raw GPS samples into activity stats.
 *
 * The client (phone) is the source of truth while recording and computes these same values
 * locally for live on-device stats; the server re-derives the running aggregates from the
 * synced GPS batches using the identical formula so the two stay consistent.
 */
@Component
public class StatsCalculator {

    /** Mean Earth radius in meters (spherical approximation used by the haversine formula). */
    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    /**
     * Great-circle (geodesic) distance in meters between two GPS samples using the haversine
     * formula. Accurate enough for consecutive route points a few meters to a few hundred
     * meters apart, which is the granularity at which phones sample GPS while recording.
     *
     * @param lat1 latitude of the first sample in decimal degrees
     * @param lon1 longitude of the first sample in decimal degrees
     * @param lat2 latitude of the second sample in decimal degrees
     * @param lon2 longitude of the second sample in decimal degrees
     * @return the distance between the two points in meters
     */
    public double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }
}
