package com.systemdesign.yelp.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "businesses", indexes = {
    @Index(name = "idx_business_owner", columnList = "owner_id"),
    @Index(name = "idx_business_category", columnList = "category")
})
@Data
@NoArgsConstructor
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String zipCode;

    @Column(nullable = false)
    private String country;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private String category;

    @Column(length = 2000)
    private String description;

    private String phone;
    private String website;

    @Column(name = "owner_id")
    private String ownerId;

    // Denormalized for read performance — updated by ReviewAggregationService
    @Column(name = "star_rating", nullable = false)
    private double starRating = 0.0;

    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "price_range")
    private Integer priceRange; // 1-4 ($, $$, $$$, $$$$)

    @Column(name = "is_open", nullable = false)
    private boolean isOpen = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "business_images", joinColumns = @JoinColumn(name = "business_id"))
    @Column(name = "image_url")
    private List<String> imageUrls = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "business_tags", joinColumns = @JoinColumn(name = "business_id"))
    @Column(name = "tag")
    private List<String> tags = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
