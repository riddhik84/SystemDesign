package com.systemdesign.yelp.service;

import com.systemdesign.yelp.cache.SearchCacheService;
import com.systemdesign.yelp.dto.AddBusinessRequest;
import com.systemdesign.yelp.dto.AddReviewRequest;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.model.Review;
import com.systemdesign.yelp.repository.BusinessRepository;
import com.systemdesign.yelp.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private BusinessService businessService;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @MockBean
    private SearchCacheService searchCacheService;

    private Business testBusiness;

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        businessRepository.deleteAll();

        when(searchCacheService.get(anyString())).thenReturn(Optional.empty());
        when(searchCacheService.getBusinessDetail(anyString())).thenReturn(Optional.empty());

        AddBusinessRequest req = new AddBusinessRequest();
        req.setName("Test Restaurant");
        req.setAddress("100 Market St");
        req.setCity("San Francisco");
        req.setState("CA");
        req.setZipCode("94105");
        req.setCountry("USA");
        req.setLatitude(37.7935);
        req.setLongitude(-122.3964);
        req.setCategory("Restaurant");
        req.setPriceRange(2);
        req.setOwnerId("owner1");
        testBusiness = businessService.addBusiness(req);
    }

    @Test
    void addReviewUpdatesBusinessRating() {
        addReview("user1", 5, "Excellent!");
        addReview("user2", 3, "Decent.");

        Business updated = businessRepository.findById(testBusiness.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isEqualTo(2);
        // (5 + 3) / 2 = 4.0
        assertThat(updated.getStarRating()).isEqualTo(4.0);
    }

    @Test
    void duplicateReviewThrowsConflict() {
        addReview("user1", 4, "Good food.");

        assertThatThrownBy(() -> addReview("user1", 5, "Changed mind!"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already reviewed");
    }

    @Test
    void reviewForNonExistentBusinessThrows() {
        AddReviewRequest req = new AddReviewRequest();
        req.setBusinessId("nonexistent-id");
        req.setUserId("user1");
        req.setStars(5);
        req.setText("Ghost review");

        assertThatThrownBy(() -> reviewService.addReview(req))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void ratingRecalculatesCorrectlyAfterMultipleReviews() {
        addReview("user1", 5, "Five stars");
        addReview("user2", 5, "Five stars");
        addReview("user3", 1, "One star");

        Business updated = businessRepository.findById(testBusiness.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isEqualTo(3);
        // (5 + 5 + 1) / 3 = 3.7
        assertThat(updated.getStarRating()).isEqualTo(3.7);
    }

    @Test
    void getReviewsForBusinessReturnsPaginated() {
        for (int i = 1; i <= 5; i++) {
            addReview("user" + i, i, "Review " + i);
        }

        Page<Review> page0 = reviewService.getReviewsForBusiness(testBusiness.getId(), 0, 3);
        assertThat(page0.getContent()).hasSize(3);
        assertThat(page0.getTotalElements()).isEqualTo(5);

        Page<Review> page1 = reviewService.getReviewsForBusiness(testBusiness.getId(), 1, 3);
        assertThat(page1.getContent()).hasSize(2);
    }

    @Test
    void getReviewsOrderedNewestFirst() {
        addReview("user1", 4, "Earlier review");
        addReview("user2", 5, "Later review");

        Page<Review> reviews = reviewService.getReviewsForBusiness(testBusiness.getId(), 0, 10);
        assertThat(reviews.getContent().get(0).getUserId()).isEqualTo("user2");
    }

    private Review addReview(String userId, int stars, String text) {
        AddReviewRequest req = new AddReviewRequest();
        req.setBusinessId(testBusiness.getId());
        req.setUserId(userId);
        req.setStars(stars);
        req.setText(text);
        return reviewService.addReview(req);
    }
}
