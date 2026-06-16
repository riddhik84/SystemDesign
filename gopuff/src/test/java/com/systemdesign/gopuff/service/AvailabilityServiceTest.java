package com.systemdesign.gopuff.service;

import com.systemdesign.gopuff.cache.AvailabilityCacheService;
import com.systemdesign.gopuff.exception.NoDcFoundException;
import com.systemdesign.gopuff.model.AvailabilityRequest;
import com.systemdesign.gopuff.model.AvailabilityResponse;
import com.systemdesign.gopuff.model.DistributionCenter;
import com.systemdesign.gopuff.model.Inventory;
import com.systemdesign.gopuff.model.Item;
import com.systemdesign.gopuff.model.ItemAvailability;
import com.systemdesign.gopuff.repository.InventoryRepository;
import com.systemdesign.gopuff.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvailabilityServiceTest {

    @Mock private NearbyDcService nearbyDcService;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private AvailabilityCacheService cacheService;

    @InjectMocks private AvailabilityService availabilityService;

    private AvailabilityRequest request() {
        return AvailabilityRequest.builder()
                .latitude(40.7128).longitude(-74.0060)
                .itemIds(List.of("item-a", "item-b"))
                .page(0)
                .build();
    }

    @Test
    void cacheHit_returnsCachedResponse_withoutHittingDb() {
        AvailabilityResponse cached = AvailabilityResponse.builder()
                .latitude(40.71).longitude(-74.01).page(0).pageSize(20)
                .totalItems(1)
                .items(List.of(ItemAvailability.builder()
                        .itemId("item-a").available(true).quantity(5).dcId("dc-1").build()))
                .build();
        when(cacheService.get(anyString())).thenReturn(Optional.of(cached));

        AvailabilityResponse result = availabilityService.getAvailability(request());

        assertThat(result).isSameAs(cached);
        verify(nearbyDcService, never()).findNearbyDcs(anyDouble(), anyDouble());
        verify(inventoryRepository, never()).findByDcIdInAndItemIdIn(any(), any());
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    void cacheMiss_queriesDb_aggregatesAcrossDcs_andCachesResult() {
        when(cacheService.get(anyString())).thenReturn(Optional.empty());

        DistributionCenter dc1 = DistributionCenter.builder().dcId("dc-1").build();
        DistributionCenter dc2 = DistributionCenter.builder().dcId("dc-2").build();
        when(nearbyDcService.findNearbyDcs(40.7128, -74.0060)).thenReturn(List.of(dc1, dc2));

        // item-a: 3 at dc-1, 7 at dc-2 -> total 10, best dc-2.
        // item-b: 0 everywhere -> unavailable.
        Inventory a1 = Inventory.builder().itemId("item-a").dcId("dc-1").quantity(3).reservedQuantity(0).build();
        Inventory a2 = Inventory.builder().itemId("item-a").dcId("dc-2").quantity(7).reservedQuantity(0).build();
        Inventory b1 = Inventory.builder().itemId("item-b").dcId("dc-1").quantity(2).reservedQuantity(2).build();
        when(inventoryRepository.findByDcIdInAndItemIdIn(any(), any()))
                .thenReturn(List.of(a1, a2, b1));

        when(itemRepository.findByItemIdIn(any())).thenReturn(List.of(
                Item.builder().itemId("item-a").name("Apple").price(BigDecimal.ONE).build(),
                Item.builder().itemId("item-b").name("Bread").price(BigDecimal.ONE).build()));

        AvailabilityResponse result = availabilityService.getAvailability(request());

        assertThat(result.getItems()).hasSize(2);
        ItemAvailability itemA = result.getItems().get(0);
        assertThat(itemA.getItemId()).isEqualTo("item-a");
        assertThat(itemA.isAvailable()).isTrue();
        assertThat(itemA.getQuantity()).isEqualTo(10);
        assertThat(itemA.getDcId()).isEqualTo("dc-2");

        ItemAvailability itemB = result.getItems().get(1);
        assertThat(itemB.getItemId()).isEqualTo("item-b");
        assertThat(itemB.isAvailable()).isFalse();
        assertThat(itemB.getQuantity()).isZero();
        assertThat(itemB.getDcId()).isNull();

        verify(cacheService).put(anyString(), any(AvailabilityResponse.class));
    }

    @Test
    void noDcInRange_throwsNoDcFoundException() {
        when(cacheService.get(anyString())).thenReturn(Optional.empty());
        when(nearbyDcService.findNearbyDcs(anyDouble(), anyDouble())).thenReturn(List.of());

        assertThatThrownBy(() -> availabilityService.getAvailability(request()))
                .isInstanceOf(NoDcFoundException.class);
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    void buildCacheKey_roundsCoordinates_andSortsItems() {
        AvailabilityRequest req = AvailabilityRequest.builder()
                .latitude(40.71284).longitude(-74.00601)
                .itemIds(List.of("item-z", "item-a"))
                .page(2)
                .build();
        String key = availabilityService.buildCacheKey(req);
        assertThat(key).isEqualTo("40.71:-74.01:item-a,item-z:2");
    }

    // Helpers to keep mockito matchers readable.
    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }
}
