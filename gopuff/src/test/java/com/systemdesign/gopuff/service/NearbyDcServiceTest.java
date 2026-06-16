package com.systemdesign.gopuff.service;

import com.systemdesign.gopuff.config.AppProperties;
import com.systemdesign.gopuff.model.DistributionCenter;
import com.systemdesign.gopuff.repository.DistributionCenterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NearbyDcServiceTest {

    @Mock
    private DistributionCenterRepository dcRepository;

    private AppProperties appProperties;

    @InjectMocks
    private NearbyDcService nearbyDcService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getDc().setMaxDistanceMiles(60.0);
        nearbyDcService = new NearbyDcService(dcRepository, appProperties);
    }

    @Test
    void haversineDistance_isApproximatelyCorrect_nycToLa() {
        // NYC (40.7128, -74.0060) to LA (34.0522, -118.2437) ~ 2451 miles.
        double miles = nearbyDcService.haversineDistance(40.7128, -74.0060, 34.0522, -118.2437);
        assertThat(miles).isBetween(2400.0, 2500.0);
    }

    @Test
    void haversineDistance_zeroForSamePoint() {
        double miles = nearbyDcService.haversineDistance(40.0, -73.0, 40.0, -73.0);
        assertThat(miles).isEqualTo(0.0);
    }

    @Test
    void haversineDistance_oneDegreeLatitude_isAbout69Miles() {
        // One degree of latitude is ~69 miles anywhere on Earth.
        double miles = nearbyDcService.haversineDistance(40.0, -73.0, 41.0, -73.0);
        assertThat(miles).isBetween(68.0, 70.0);
    }

    @Test
    void findNearbyDcs_returnsOnlyWithinRadius_sortedNearestFirst() {
        DistributionCenter near = DistributionCenter.builder()
                .dcId("dc-near").name("Near").latitude(40.71).longitude(-74.00)
                .active(true).build();
        DistributionCenter mid = DistributionCenter.builder()
                .dcId("dc-mid").name("Mid").latitude(41.20).longitude(-74.00)
                .active(true).build();
        DistributionCenter far = DistributionCenter.builder()
                .dcId("dc-far").name("Far").latitude(34.05).longitude(-118.24)
                .active(true).build();
        when(dcRepository.findByActiveTrue()).thenReturn(List.of(far, mid, near));

        nearbyDcService.loadDcCache();
        List<DistributionCenter> result = nearbyDcService.findNearbyDcs(40.7128, -74.0060);

        // LA is ~2400mi away -> excluded. Near and mid are within 60mi.
        assertThat(result).extracting(DistributionCenter::getDcId)
                .containsExactly("dc-near", "dc-mid");
    }

    @Test
    void findNearbyDcs_returnsEmpty_whenNoneInRange() {
        DistributionCenter far = DistributionCenter.builder()
                .dcId("dc-far").name("Far").latitude(34.05).longitude(-118.24)
                .active(true).build();
        when(dcRepository.findByActiveTrue()).thenReturn(List.of(far));

        nearbyDcService.loadDcCache();
        List<DistributionCenter> result = nearbyDcService.findNearbyDcs(40.7128, -74.0060);

        assertThat(result).isEmpty();
    }
}
