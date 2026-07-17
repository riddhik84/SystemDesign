package com.systemdesign.yelp.service;

import com.systemdesign.yelp.cache.SearchCacheService;
import com.systemdesign.yelp.dto.AddReviewRequest;
import com.systemdesign.yelp.model.Review;
import com.systemdesign.yelp.repository.BusinessRepository;
import com.systemdesign.yelp.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * Handles review write path and rating aggregation.
 *
 * Write path:
 * 1. Validate: one review per user per business
 * 2. Persist Review row
 * 3. Recalculate average from DB (accurate even under concurrent writes)
 * 4. UPDATE Business.starRating and reviewCount in place
 * 5. Evict cached business detail (stale after rating change)
 *
 * Why recalculate from DB instead of incremental update?
 * - Simple and correct under concurrent submissions
 * - Avoids floating-point drift from incremental (avg = ((avg * n) + new) / (n+1))
 * - Count(*) + AVG(*) are cheap SQL aggregations indexed by businessId
 *
 * Production scale note: at >1K reviews/sec per popular business, this recalc
 * becomes a hot spot. The production pattern is:
 * - Write review to Kafka topic
 * - Async aggregation service consumes and batch-updates rating
 * - Accept ~1 min stale rating for extreme write spikes
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final BusinessRepository businessRepository;
    private final SearchCacheService searchCacheService;

    public ReviewService(ReviewRepository reviewRepository,
                         BusinessRepository businessRepository,
                         SearchCacheService searchCacheService) {
        this.reviewRepository = reviewRepository;
        this.businessRepository = businessRepository;
        this.searchCacheService = searchCacheService;
    }

    @Transactional
    public Review addReview(AddReviewRequest req) {
        if (!businessRepository.existsById(req.getBusinessId())) {
            throw new NoSuchElementException("Business not found: " + req.getBusinessId());
        }
        if (reviewRepository.existsByBusinessIdAndUserId(req.getBusinessId(), req.getUserId())) {
            throw new IllegalStateException("User already reviewed this business");
        }

        Review review = new Review();
        review.setBusinessId(req.getBusinessId());
        review.setUserId(req.getUserId());
        review.setStars(req.getStars());
        review.setText(req.getText());
        if (req.getImageUrls() != null) review.setImageUrls(req.getImageUrls());

        Review saved = reviewRepository.save(review);

        // Recalculate and persist aggregate rating
        recalculateRating(req.getBusinessId());

        // Evict cached business detail — star rating changed
        searchCacheService.evictBusinessDetail(req.getBusinessId());

        log.info("Review added: business={} user={} stars={}", req.getBusinessId(), req.getUserId(), req.getStars());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Review> getReviewsForBusiness(String businessId, int page, int pageSize) {
        if (!businessRepository.existsById(businessId)) {
            throw new NoSuchElementException("Business not found: " + businessId);
        }
        return reviewRepository.findByBusinessIdOrderByCreatedAtDesc(
            businessId, PageRequest.of(page, Math.min(pageSize, 50)));
    }

    @Transactional(readOnly = true)
    public Page<Review> getReviewsByUser(String userId, int page, int pageSize) {
        return reviewRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(page, Math.min(pageSize, 50)));
    }

    @Transactional
    public void voteUseful(String reviewId, String voterId) {
        Review review = reviewRepository.findById(reviewId)
            .orElseThrow(() -> new NoSuchElementException("Review not found: " + reviewId));
        review.setUsefulCount(review.getUsefulCount() + 1);
        reviewRepository.save(review);
    }

    private void recalculateRating(String businessId) {
        Double avg = reviewRepository.averageStarsByBusinessId(businessId);
        long count = reviewRepository.countByBusinessId(businessId);
        double rounded = avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0;
        businessRepository.updateRating(businessId, rounded, (int) count);
        log.debug("Recalculated rating for business={}: avg={} count={}", businessId, rounded, count);
    }
}
