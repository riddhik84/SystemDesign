package com.systemdesign.yelp.service;

import com.systemdesign.yelp.cache.SearchCacheService;
import com.systemdesign.yelp.dto.BusinessSearchRequest;
import com.systemdesign.yelp.dto.BusinessSearchResponse;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.repository.BusinessRepository;
import com.systemdesign.yelp.search.GeohashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Core proximity + full-text search service.
 *
 * Search pipeline:
 * 1. Cache check: return cached response if key matches (lat/lon rounded + filters)
 * 2. Bounding-box pre-filter on the database (cheap rectangular check)
 * 3. Haversine post-filter to convert rectangle → circle
 * 4. Optional full-text filter (name/category/tags LIKE query)
 * 5. Apply star/price/open filters
 * 6. Sort: distance | rating | review_count
 * 7. Paginate and map to response DTOs
 * 8. Cache result with configured TTL
 *
 * Production upgrade path:
 * - Replace steps 2-4 with an Elasticsearch geo_distance + multi_match query
 * - Elasticsearch geo_point index handles billions of documents efficiently
 * - Spring Data Elasticsearch or raw RestHighLevelClient provides the integration
 */
@Service
public class BusinessSearchService {

    private static final Logger log = LoggerFactory.getLogger(BusinessSearchService.class);

    private final BusinessRepository businessRepository;
    private final SearchCacheService searchCacheService;
    private final GeohashUtil geohashUtil;

    @Value("${app.geo.default-radius-km:5.0}")
    private double defaultRadiusKm;

    @Value("${app.geo.max-radius-km:40.0}")
    private double maxRadiusKm;

    @Value("${app.search.default-page-size:20}")
    private int defaultPageSize;

    public BusinessSearchService(BusinessRepository businessRepository,
                                 SearchCacheService searchCacheService,
                                 GeohashUtil geohashUtil) {
        this.businessRepository = businessRepository;
        this.searchCacheService = searchCacheService;
        this.geohashUtil = geohashUtil;
    }

    public BusinessSearchResponse search(BusinessSearchRequest req) {
        double radius = resolveRadius(req.getRadiusKm());
        int pageSize = resolvePageSize(req.getPageSize());

        String cacheKey = buildCacheKey(req, radius);
        Optional<BusinessSearchResponse> cached = searchCacheService.get(cacheKey);
        if (cached.isPresent()) {
            log.debug("Search cache hit for key={}", cacheKey);
            return cached.get();
        }

        // Bounding-box: 1 degree lat ≈ 111 km; 1 degree lon varies with cos(lat)
        double latDelta = radius / 111.0;
        double lonDelta = radius / (111.0 * Math.cos(Math.toRadians(req.getLatitude())));

        double minLat = req.getLatitude() - latDelta;
        double maxLat = req.getLatitude() + latDelta;
        double minLon = req.getLongitude() - lonDelta;
        double maxLon = req.getLongitude() + lonDelta;

        List<Business> candidates;
        if (req.getQuery() != null && !req.getQuery().isBlank()) {
            candidates = businessRepository.searchByTextAndBoundingBox(
                req.getQuery(), minLat, maxLat, minLon, maxLon);
        } else {
            candidates = businessRepository.findByBoundingBox(
                minLat, maxLat, minLon, maxLon,
                req.getCategory(),
                req.getMinStars(),
                req.getMaxPriceRange(),
                req.getOpenNow() != null && req.getOpenNow()
            );
        }

        // Precise circle filter (Haversine)
        List<ScoredBusiness> scored = candidates.stream()
            .map(b -> {
                double dist = geohashUtil.haversineKm(
                    req.getLatitude(), req.getLongitude(), b.getLatitude(), b.getLongitude());
                return new ScoredBusiness(b, dist);
            })
            .filter(sb -> sb.distanceKm <= radius)
            // Apply text-search filters not captured by bounding-box query
            .filter(sb -> req.getMinStars() == null || sb.business.getStarRating() >= req.getMinStars())
            .filter(sb -> req.getMaxPriceRange() == null || sb.business.getPriceRange() == null
                        || sb.business.getPriceRange() <= req.getMaxPriceRange())
            .filter(sb -> req.getOpenNow() == null || !req.getOpenNow() || sb.business.isOpen())
            .collect(Collectors.toList());

        // Sort
        Comparator<ScoredBusiness> comparator = buildComparator(req.getSortBy());
        scored.sort(comparator);

        int total = scored.size();
        int fromIndex = req.getPage() * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<ScoredBusiness> page = fromIndex < total ? scored.subList(fromIndex, toIndex) : List.of();

        BusinessSearchResponse response = buildResponse(page, total, req, radius);
        searchCacheService.put(cacheKey, response);
        return response;
    }

    private double resolveRadius(Double requested) {
        if (requested == null) return defaultRadiusKm;
        return Math.min(requested, maxRadiusKm);
    }

    private int resolvePageSize(int requested) {
        int max = 100;
        return requested <= 0 ? defaultPageSize : Math.min(requested, max);
    }

    private String buildCacheKey(BusinessSearchRequest req, double radius) {
        // Round coordinates to 3 decimal places ≈ 111m precision — nearby users share cache
        double lat = Math.round(req.getLatitude() * 1000.0) / 1000.0;
        double lon = Math.round(req.getLongitude() * 1000.0) / 1000.0;
        return String.format("search:%s:%s:%.1f:%s:%s:%s:%s:%s:%d:%d",
            lat, lon, radius,
            req.getQuery() != null ? req.getQuery().toLowerCase() : "",
            req.getCategory() != null ? req.getCategory() : "",
            req.getMinStars() != null ? req.getMinStars() : "",
            req.getMaxPriceRange() != null ? req.getMaxPriceRange() : "",
            req.getSortBy() != null ? req.getSortBy() : "distance",
            req.getPage(), resolvePageSize(req.getPageSize())
        );
    }

    private Comparator<ScoredBusiness> buildComparator(String sortBy) {
        if (sortBy == null) return Comparator.comparingDouble(sb -> sb.distanceKm);
        return switch (sortBy.toLowerCase()) {
            case "rating" -> Comparator.comparingDouble((ScoredBusiness sb) -> sb.business.getStarRating()).reversed();
            case "review_count" -> Comparator.comparingInt((ScoredBusiness sb) -> sb.business.getReviewCount()).reversed();
            default -> Comparator.comparingDouble(sb -> sb.distanceKm);
        };
    }

    private BusinessSearchResponse buildResponse(List<ScoredBusiness> page, int total,
                                                  BusinessSearchRequest req, double radius) {
        List<BusinessSearchResponse.BusinessSummary> summaries = page.stream()
            .map(sb -> toSummary(sb.business, sb.distanceKm))
            .toList();

        BusinessSearchResponse response = new BusinessSearchResponse();
        response.setBusinesses(summaries);
        response.setTotal(total);
        response.setPage(req.getPage());
        response.setPageSize(resolvePageSize(req.getPageSize()));
        response.setSearchLatitude(req.getLatitude());
        response.setSearchLongitude(req.getLongitude());
        response.setRadiusKm(radius);
        return response;
    }

    private BusinessSearchResponse.BusinessSummary toSummary(Business b, double distanceKm) {
        BusinessSearchResponse.BusinessSummary s = new BusinessSearchResponse.BusinessSummary();
        s.setId(b.getId());
        s.setName(b.getName());
        s.setAddress(b.getAddress());
        s.setCity(b.getCity());
        s.setState(b.getState());
        s.setCategory(b.getCategory());
        s.setStarRating(b.getStarRating());
        s.setReviewCount(b.getReviewCount());
        s.setPriceRange(b.getPriceRange());
        s.setOpen(b.isOpen());
        s.setDistanceKm(Math.round(distanceKm * 100.0) / 100.0);
        s.setStarDisplay(String.format("%.1f stars", b.getStarRating()));
        s.setPriceDisplay(b.getPriceRange() != null ? "$".repeat(b.getPriceRange()) : "");
        if (!b.getImageUrls().isEmpty()) {
            s.setThumbnailUrl(b.getImageUrls().get(0));
        }
        return s;
    }

    private record ScoredBusiness(Business business, double distanceKm) {}
}
