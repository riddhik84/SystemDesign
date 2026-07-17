package com.systemdesign.yelp.controller;

import com.systemdesign.yelp.dto.AddReviewRequest;
import com.systemdesign.yelp.model.Review;
import com.systemdesign.yelp.service.ReviewService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * Get paginated reviews for a business, sorted newest first.
     */
    @GetMapping("/businesses/{businessId}/reviews")
    public Page<Review> getBusinessReviews(
        @PathVariable String businessId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return reviewService.getReviewsForBusiness(businessId, page, pageSize);
    }

    /**
     * Get all reviews written by a user.
     */
    @GetMapping("/users/{userId}/reviews")
    public Page<Review> getUserReviews(
        @PathVariable String userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return reviewService.getReviewsByUser(userId, page, pageSize);
    }

    /**
     * Add a review for a business.
     * One review per user per business — returns 409 if already reviewed.
     * After persisting, recalculates business star rating and evicts cache.
     */
    @PostMapping("/businesses/{businessId}/reviews")
    public ResponseEntity<Review> addReview(
        @PathVariable String businessId,
        @RequestBody AddReviewRequest req
    ) {
        req.setBusinessId(businessId);
        Review review = reviewService.addReview(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(review);
    }

    /**
     * Vote a review as useful.
     */
    @PostMapping("/reviews/{reviewId}/useful")
    public ResponseEntity<Void> voteUseful(
        @PathVariable String reviewId,
        @RequestParam String voterId
    ) {
        reviewService.voteUseful(reviewId, voterId);
        return ResponseEntity.ok().build();
    }
}
