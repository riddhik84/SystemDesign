package com.systemdesign.yelp.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Geohash encoding utility.
 *
 * Geohash encodes a (lat, lon) pair into a base-32 string where:
 * - Each character adds ~2.5 bits of precision (alternating lon/lat bits)
 * - Prefix sharing = geographic proximity
 * - Adjacent cells share a prefix (with one character removed)
 *
 * Precision → approximate cell dimensions:
 *   4 chars → 39.1 km × 19.5 km
 *   5 chars →  4.9 km ×  4.9 km
 *   6 chars →  1.2 km ×  0.6 km
 *
 * Search strategy:
 *   1. Encode user's location to precision 5 (≈5km cell)
 *   2. Find the 8 neighboring cells at that precision
 *   3. Query businesses in those 9 cells (center + 8 neighbors)
 *   4. Post-filter to exact Haversine distance
 *
 * This eliminates the O(N) full-table scan and reduces candidates
 * from millions to hundreds before Haversine runs.
 */
@Component
public class GeohashUtil {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[] BITS = {16, 8, 4, 2, 1};

    public String encode(double latitude, double longitude, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        StringBuilder geohash = new StringBuilder();
        boolean isEven = true;
        int bit = 0, ch = 0;

        while (geohash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lonRange[0] + lonRange[1]) / 2;
                if (longitude > mid) {
                    ch |= BITS[bit];
                    lonRange[0] = mid;
                } else {
                    lonRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2;
                if (latitude > mid) {
                    ch |= BITS[bit];
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            isEven = !isEven;
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return geohash.toString();
    }

    /**
     * Compute 8 neighboring geohash cells.
     * Used to prevent edge artifacts where the target location is near a cell boundary:
     * a business 10 meters away might be in the adjacent cell.
     */
    public List<String> neighbors(String geohash) {
        List<String> result = new ArrayList<>(8);
        double[] center = decode(geohash);
        double[] err = decodeError(geohash.length());

        double[][] offsets = {
            {err[0] * 2, 0}, {-err[0] * 2, 0},
            {0, err[1] * 2}, {0, -err[1] * 2},
            {err[0] * 2, err[1] * 2}, {err[0] * 2, -err[1] * 2},
            {-err[0] * 2, err[1] * 2}, {-err[0] * 2, -err[1] * 2}
        };

        for (double[] offset : offsets) {
            double lat = Math.max(-90, Math.min(90, center[0] + offset[0]));
            double lon = Math.max(-180, Math.min(180, center[1] + offset[1]));
            String neighbor = encode(lat, lon, geohash.length());
            if (!neighbor.equals(geohash) && !result.contains(neighbor)) {
                result.add(neighbor);
            }
        }
        return result;
    }

    /** Returns [latitude, longitude] of the center of the geohash cell. */
    public double[] decode(String geohash) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        boolean isEven = true;

        for (char c : geohash.toCharArray()) {
            int cd = BASE32.indexOf(c);
            for (int mask : BITS) {
                if (isEven) {
                    refineBounds(lonRange, (cd & mask) != 0);
                } else {
                    refineBounds(latRange, (cd & mask) != 0);
                }
                isEven = !isEven;
            }
        }
        return new double[]{
            (latRange[0] + latRange[1]) / 2,
            (lonRange[0] + lonRange[1]) / 2
        };
    }

    private void refineBounds(double[] range, boolean bit) {
        double mid = (range[0] + range[1]) / 2;
        if (bit) {
            range[0] = mid;
        } else {
            range[1] = mid;
        }
    }

    private double[] decodeError(int precision) {
        double latErr = 90.0;
        double lonErr = 180.0;
        boolean isEven = true;
        for (int i = 0; i < precision * 5; i++) {
            if (isEven) lonErr /= 2;
            else latErr /= 2;
            isEven = !isEven;
        }
        return new double[]{latErr, lonErr};
    }

    public double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Pick geohash precision based on search radius.
     * Coarser precision (shorter hash) = larger search cell = wider radius.
     */
    public int precisionForRadius(double radiusKm) {
        if (radiusKm <= 1.0) return 6;   // ~0.6km² cells
        if (radiusKm <= 5.0) return 5;   // ~4.9km² cells
        return 4;                          // ~39km² cells for larger searches
    }
}
