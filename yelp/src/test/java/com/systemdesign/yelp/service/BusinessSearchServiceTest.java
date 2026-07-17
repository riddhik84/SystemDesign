package com.systemdesign.yelp.service;

import com.systemdesign.yelp.cache.SearchCacheService;
import com.systemdesign.yelp.dto.AddBusinessRequest;
import com.systemdesign.yelp.dto.BusinessSearchRequest;
import com.systemdesign.yelp.dto.BusinessSearchResponse;
import com.systemdesign.yelp.model.Business;
import com.systemdesign.yelp.repository.BusinessRepository;
import com.systemdesign.yelp.repository.GeoCellRepository;
import com.systemdesign.yelp.repository.ReviewRepository;
import com.systemdesign.yelp.search.GeohashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class BusinessSearchServiceTest {

    @Autowired
    private BusinessSearchService searchService;

    @Autowired
    private BusinessService businessService;

    @Autowired
    private BusinessRepository businessRepository;

    @MockBean
    private SearchCacheService searchCacheService;

    @BeforeEach
    void setUp() {
        businessRepository.deleteAll();
        // Cache always misses in tests
        when(searchCacheService.get(anyString())).thenReturn(Optional.empty());
        when(searchCacheService.getBusinessDetail(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void searchReturnsBusinessesWithinRadius() {
        // Add a business at Union Square SF (37.7879, -122.4074)
        addBusiness("Pizza Palace", 37.7879, -122.4074, "Pizza");
        // Add a business in Oakland (far away - ~12km)
        addBusiness("Oakland Eats", 37.8044, -122.2712, "American");

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);    // SoMa SF
        req.setLongitude(-122.4194);
        req.setRadiusKm(5.0);
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);

        assertThat(response.getBusinesses()).hasSize(1);
        assertThat(response.getBusinesses().get(0).getName()).isEqualTo("Pizza Palace");
        assertThat(response.getBusinesses().get(0).getDistanceKm()).isLessThan(5.0);
    }

    @Test
    void searchFiltersExcludeFarBusinesses() {
        addBusiness("Close Business", 37.7760, -122.4180, "Coffee");
        addBusiness("Far Business", 37.9000, -122.5000, "Coffee");

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);
        req.setLongitude(-122.4194);
        req.setRadiusKm(2.0);
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);

        assertThat(response.getBusinesses()).hasSize(1);
        assertThat(response.getTotal()).isEqualTo(1);
    }

    @Test
    void searchByQueryMatchesName() {
        addBusiness("Blue Bottle Coffee", 37.7760, -122.4180, "Coffee");
        addBusiness("Stumptown Coffee", 37.7770, -122.4160, "Coffee");
        addBusiness("Tony's Pizza", 37.7780, -122.4170, "Pizza");

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);
        req.setLongitude(-122.4194);
        req.setRadiusKm(5.0);
        req.setQuery("coffee");
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);

        assertThat(response.getBusinesses()).hasSize(2);
        assertThat(response.getBusinesses())
            .extracting(BusinessSearchResponse.BusinessSummary::getName)
            .containsExactlyInAnyOrder("Blue Bottle Coffee", "Stumptown Coffee");
    }

    @Test
    void searchSortsByDistance() {
        // Near: 0.3km away
        addBusiness("Near Business", 37.7753, -122.4200, "Food");
        // Far: ~1.5km away
        addBusiness("Far Business", 37.7884, -122.4200, "Food");

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);
        req.setLongitude(-122.4194);
        req.setRadiusKm(5.0);
        req.setSortBy("distance");
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);

        assertThat(response.getBusinesses()).hasSize(2);
        assertThat(response.getBusinesses().get(0).getName()).isEqualTo("Near Business");
        assertThat(response.getBusinesses().get(0).getDistanceKm())
            .isLessThan(response.getBusinesses().get(1).getDistanceKm());
    }

    @Test
    void searchPaginationWorks() {
        for (int i = 0; i < 5; i++) {
            addBusiness("Business " + i, 37.7750 + i * 0.001, -122.4194, "Food");
        }

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);
        req.setLongitude(-122.4194);
        req.setRadiusKm(5.0);
        req.setPage(0);
        req.setPageSize(3);

        BusinessSearchResponse page0 = searchService.search(req);
        assertThat(page0.getBusinesses()).hasSize(3);
        assertThat(page0.getTotal()).isEqualTo(5);

        req.setPage(1);
        BusinessSearchResponse page1 = searchService.search(req);
        assertThat(page1.getBusinesses()).hasSize(2);
    }

    @Test
    void searchFiltersOpenNow() {
        Business open = addBusiness("Open Place", 37.7760, -122.4180, "Coffee");
        open.setOpen(true);
        businessRepository.save(open);

        Business closed = addBusiness("Closed Place", 37.7765, -122.4175, "Coffee");
        closed.setOpen(false);
        businessRepository.save(closed);

        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(37.7750);
        req.setLongitude(-122.4194);
        req.setRadiusKm(5.0);
        req.setOpenNow(true);
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);
        assertThat(response.getBusinesses()).hasSize(1);
        assertThat(response.getBusinesses().get(0).getName()).isEqualTo("Open Place");
    }

    @Test
    void emptySearchReturnsNoResults() {
        BusinessSearchRequest req = new BusinessSearchRequest();
        req.setLatitude(0.0);  // middle of ocean
        req.setLongitude(0.0);
        req.setRadiusKm(1.0);
        req.setPage(0);
        req.setPageSize(20);

        BusinessSearchResponse response = searchService.search(req);
        assertThat(response.getBusinesses()).isEmpty();
        assertThat(response.getTotal()).isEqualTo(0);
    }

    private Business addBusiness(String name, double lat, double lon, String category) {
        AddBusinessRequest req = new AddBusinessRequest();
        req.setName(name);
        req.setAddress("123 Main St");
        req.setCity("San Francisco");
        req.setState("CA");
        req.setZipCode("94102");
        req.setCountry("USA");
        req.setLatitude(lat);
        req.setLongitude(lon);
        req.setCategory(category);
        req.setPriceRange(2);
        req.setOwnerId("owner1");
        return businessService.addBusiness(req);
    }
}
