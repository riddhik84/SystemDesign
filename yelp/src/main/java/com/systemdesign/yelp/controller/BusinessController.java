package com.systemdesign.yelp.controller;

import com.systemdesign.yelp.dto.*;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.service.BusinessSearchService;
import com.systemdesign.yelp.service.BusinessService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessService businessService;
    private final BusinessSearchService businessSearchService;

    public BusinessController(BusinessService businessService,
                               BusinessSearchService businessSearchService) {
        this.businessService = businessService;
        this.businessSearchService = businessSearchService;
    }

    /**
     * Search for businesses near a location.
     *
     * GET /api/businesses/search?latitude=37.7749&longitude=-122.4194&query=pizza&radiusKm=5
     *
     * This is the primary read path. The response is cached in Redis for 5 minutes.
     * Coordinates are rounded to 3 decimal places (~111m) so nearby users share the cache.
     */
    @GetMapping("/search")
    public BusinessSearchResponse search(
        @RequestParam double latitude,
        @RequestParam double longitude,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) Double radiusKm,
        @RequestParam(required = false) Integer minStars,
        @RequestParam(required = false) Integer maxPriceRange,
        @RequestParam(required = false) Boolean openNow,
        @RequestParam(defaultValue = "distance") String sortBy,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(latitude);
        req.setLongitude(longitude);
        req.setQuery(query);
        req.setCategory(category);
        req.setRadiusKm(radiusKm);
        req.setMinStars(minStars);
        req.setMaxPriceRange(maxPriceRange);
        req.setOpenNow(openNow);
        req.setSortBy(sortBy);
        req.setPage(page);
        req.setPageSize(pageSize);
        return businessSearchService.search(req);
    }

    /**
     * Get full business detail including star distribution histogram.
     */
    @GetMapping("/{businessId}")
    public BusinessDetailResponse getDetail(@PathVariable String businessId) {
        return businessService.getBusinessDetail(businessId);
    }

    /**
     * Add a new business.
     */
    @PostMapping
    public ResponseEntity<Business> addBusiness(@RequestBody AddBusinessRequest req) {
        Business b = businessService.addBusiness(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(b);
    }

    /**
     * Delete a business.
     */
    @DeleteMapping("/{businessId}")
    public ResponseEntity<Void> deleteBusiness(@PathVariable String businessId) {
        businessService.deleteBusiness(businessId);
        return ResponseEntity.noContent().build();
    }
}
