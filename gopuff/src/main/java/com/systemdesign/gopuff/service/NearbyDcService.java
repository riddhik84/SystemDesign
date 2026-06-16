package com.systemdesign.gopuff.service;

import com.systemdesign.gopuff.config.AppProperties;
import com.systemdesign.gopuff.model.DistributionCenter;
import com.systemdesign.gopuff.repository.DistributionCenterRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds distribution centers that can deliver to a user's coordinates within the configured
 * radius (a proxy for the 1-hour delivery window).
 *
 * <p>Geo strategy: with only ~10K DCs, an in-memory linear Haversine scan is fast (sub-ms)
 * and far simpler than PostGIS or a geo index. The full active-DC list is loaded once at
 * startup and cached in memory. In production this snapshot would be refreshed periodically
 * (e.g. every few minutes) as DCs are opened/closed — DC topology changes slowly.
 */
@Service
public class NearbyDcService {

    private static final Logger log = LoggerFactory.getLogger(NearbyDcService.class);

    /** Earth's mean radius in miles, used by the Haversine formula. */
    private static final double EARTH_RADIUS_MILES = 3958.8;

    private final DistributionCenterRepository dcRepository;
    private final AppProperties appProperties;

    /** Immutable snapshot of active DCs, swapped atomically on refresh. */
    private final AtomicReference<List<DistributionCenter>> dcCache =
            new AtomicReference<>(List.of());

    public NearbyDcService(DistributionCenterRepository dcRepository, AppProperties appProperties) {
        this.dcRepository = dcRepository;
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void loadDcCache() {
        refreshDcCache();
    }

    /** Reload the in-memory active-DC snapshot from the database. */
    public void refreshDcCache() {
        List<DistributionCenter> active = dcRepository.findByActiveTrue();
        dcCache.set(List.copyOf(active));
        log.info("Loaded {} active distribution centers into memory", active.size());
    }

    /**
     * Return active DCs within {@code app.dc.max-distance-miles} of the given coordinates,
     * sorted nearest-first.
     */
    public List<DistributionCenter> findNearbyDcs(double latitude, double longitude) {
        double maxDistance = appProperties.getDc().getMaxDistanceMiles();
        List<DistributionCenter> snapshot = dcCache.get();

        List<DistributionCenter> nearby = new ArrayList<>();
        for (DistributionCenter dc : snapshot) {
            double distance = haversineDistance(latitude, longitude, dc.getLatitude(), dc.getLongitude());
            if (distance <= maxDistance) {
                nearby.add(dc);
            }
        }
        nearby.sort(Comparator.comparingDouble(
                dc -> haversineDistance(latitude, longitude, dc.getLatitude(), dc.getLongitude())));
        return nearby;
    }

    /**
     * Great-circle distance between two points in miles, via the Haversine formula.
     * Pure Java — no external dependency.
     */
    public double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
