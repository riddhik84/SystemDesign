package com.systemdesign.yelp.service;

import com.systemdesign.yelp.cache.SearchCacheService;
import com.systemdesign.yelp.dto.AddBusinessRequest;
import com.systemdesign.yelp.dto.BusinessDetailResponse;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.model.GeoCell;
import com.systemdesign.yelp.repository.BusinessRepository;
import com.systemdesign.yelp.repository.GeoCellRepository;
import com.systemdesign.yelp.repository.ReviewRepository;
import com.systemdesign.yelp.search.GeohashUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class BusinessService {

    private static final Logger log = LoggerFactory.getLogger(BusinessService.class);

    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;
    private final GeoCellRepository geoCellRepository;
    private final SearchCacheService searchCacheService;
    private final GeohashUtil geohashUtil;

    public BusinessService(BusinessRepository businessRepository,
                           ReviewRepository reviewRepository,
                           GeoCellRepository geoCellRepository,
                           SearchCacheService searchCacheService,
                           GeohashUtil geohashUtil) {
        this.businessRepository = businessRepository;
        this.reviewRepository = reviewRepository;
        this.geoCellRepository = geoCellRepository;
        this.searchCacheService = searchCacheService;
        this.geohashUtil = geohashUtil;
    }

    @Transactional
    public Business addBusiness(AddBusinessRequest req) {
        Business b = new Business();
        b.setName(req.getName());
        b.setAddress(req.getAddress());
        b.setCity(req.getCity());
        b.setState(req.getState());
        b.setZipCode(req.getZipCode());
        b.setCountry(req.getCountry());
        b.setLatitude(req.getLatitude());
        b.setLongitude(req.getLongitude());
        b.setCategory(req.getCategory());
        b.setDescription(req.getDescription());
        b.setPhone(req.getPhone());
        b.setWebsite(req.getWebsite());
        b.setOwnerId(req.getOwnerId());
        b.setPriceRange(req.getPriceRange());
        if (req.getImageUrls() != null) b.setImageUrls(req.getImageUrls());
        if (req.getTags() != null) b.setTags(req.getTags());

        Business saved = businessRepository.save(b);
        indexGeoCell(saved);
        log.info("Created business id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    public BusinessDetailResponse getBusinessDetail(String businessId) {
        Optional<BusinessDetailResponse> cached = searchCacheService.getBusinessDetail(businessId);
        if (cached.isPresent()) {
            return cached.get();
        }

        Business b = businessRepository.findById(businessId)
            .orElseThrow(() -> new NoSuchElementException("Business not found: " + businessId));

        // Fetch star distribution histogram
        List<Object[]> starCounts = reviewRepository.countStarsByBusinessId(businessId);
        Map<String, Integer> distribution = buildStarDistribution(starCounts);

        BusinessDetailResponse detail = toDetail(b, distribution);
        searchCacheService.putBusinessDetail(businessId, detail);
        return detail;
    }

    @Transactional
    public void deleteBusiness(String businessId) {
        businessRepository.deleteById(businessId);
        geoCellRepository.deleteByBusinessId(businessId);
        searchCacheService.evictBusinessDetail(businessId);
        log.info("Deleted business id={}", businessId);
    }

    /**
     * Index the business into the geo_index table at multiple precision levels.
     * Called on create/update so proximity search can use prefix lookups.
     */
    private void indexGeoCell(Business b) {
        for (int precision : new int[]{4, 5, 6}) {
            String hash = geohashUtil.encode(b.getLatitude(), b.getLongitude(), precision);
            GeoCell cell = new GeoCell();
            cell.setBusinessId(b.getId());
            cell.setGeohashCell(hash);
            cell.setPrecision(precision);
            cell.setLatitude(b.getLatitude());
            cell.setLongitude(b.getLongitude());
            geoCellRepository.save(cell);
        }
    }

    private Map<String, Integer> buildStarDistribution(List<Object[]> rows) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) dist.put(String.valueOf(i), 0);
        for (Object[] row : rows) {
            dist.put(String.valueOf(row[0]), ((Long) row[1]).intValue());
        }
        return dist;
    }

    private BusinessDetailResponse toDetail(Business b, Map<String, Integer> distribution) {
        BusinessDetailResponse d = new BusinessDetailResponse();
        d.setId(b.getId());
        d.setName(b.getName());
        d.setAddress(b.getAddress());
        d.setCity(b.getCity());
        d.setState(b.getState());
        d.setZipCode(b.getZipCode());
        d.setCountry(b.getCountry());
        d.setLatitude(b.getLatitude());
        d.setLongitude(b.getLongitude());
        d.setCategory(b.getCategory());
        d.setDescription(b.getDescription());
        d.setPhone(b.getPhone());
        d.setWebsite(b.getWebsite());
        d.setStarRating(b.getStarRating());
        d.setReviewCount(b.getReviewCount());
        d.setPriceRange(b.getPriceRange());
        d.setOpen(b.isOpen());
        d.setImageUrls(b.getImageUrls());
        d.setTags(b.getTags());
        d.setStarDistribution(distribution);
        return d;
    }
}
