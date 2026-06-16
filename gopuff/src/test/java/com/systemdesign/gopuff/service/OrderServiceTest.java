package com.systemdesign.gopuff.service;

import com.systemdesign.gopuff.cache.AvailabilityCacheService;
import com.systemdesign.gopuff.exception.ItemUnavailableException;
import com.systemdesign.gopuff.exception.NoDcFoundException;
import com.systemdesign.gopuff.model.DistributionCenter;
import com.systemdesign.gopuff.model.Inventory;
import com.systemdesign.gopuff.model.Item;
import com.systemdesign.gopuff.model.Order;
import com.systemdesign.gopuff.model.OrderItem;
import com.systemdesign.gopuff.model.OrderLineItem;
import com.systemdesign.gopuff.model.OrderStatus;
import com.systemdesign.gopuff.model.PlaceOrderRequest;
import com.systemdesign.gopuff.model.PlaceOrderResponse;
import com.systemdesign.gopuff.repository.InventoryRepository;
import com.systemdesign.gopuff.repository.ItemRepository;
import com.systemdesign.gopuff.repository.OrderItemRepository;
import com.systemdesign.gopuff.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private NearbyDcService nearbyDcService;
    @Mock private InventoryRepository inventoryRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private AvailabilityCacheService cacheService;

    @InjectMocks private OrderService orderService;

    private PlaceOrderRequest request() {
        return PlaceOrderRequest.builder()
                .userId("user-1")
                .latitude(40.7128).longitude(-74.0060)
                .items(List.of(
                        OrderLineItem.builder().itemId("item-a").quantity(2).build(),
                        OrderLineItem.builder().itemId("item-b").quantity(1).build()))
                .build();
    }

    @Test
    void placeOrder_success_reservesInventory_savesOrder_andEvictsCache() {
        DistributionCenter dc = DistributionCenter.builder().dcId("dc-1").build();
        when(nearbyDcService.findNearbyDcs(40.7128, -74.0060)).thenReturn(List.of(dc));

        Inventory invA = Inventory.builder().itemId("item-a").dcId("dc-1").quantity(10).reservedQuantity(0).build();
        Inventory invB = Inventory.builder().itemId("item-b").dcId("dc-1").quantity(5).reservedQuantity(0).build();
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-a", "dc-1")).thenReturn(Optional.of(invA));
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-b", "dc-1")).thenReturn(Optional.of(invB));

        when(itemRepository.findByItemIdIn(any())).thenReturn(List.of(
                Item.builder().itemId("item-a").name("Apple").price(new BigDecimal("1.50")).build(),
                Item.builder().itemId("item-b").name("Bread").price(new BigDecimal("2.00")).build()));

        PlaceOrderResponse response = orderService.placeOrder(request());

        assertThat(response.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.getFulfillingDcId()).isEqualTo("dc-1");
        assertThat(response.getOrderId()).isNotBlank();

        // Reservations applied.
        assertThat(invA.getReservedQuantity()).isEqualTo(2);
        assertThat(invB.getReservedQuantity()).isEqualTo(1);

        // Order persisted as CONFIRMED.
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orderCaptor.getValue().getFulfillingDcId()).isEqualTo("dc-1");

        // Line items persisted with snapshot prices.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderItemRepository).saveAll(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(2);

        // Cache evicted for the fulfilling DC.
        verify(cacheService).evictByDcId("dc-1");
    }

    @Test
    void placeOrder_throwsItemUnavailable_whenItemOutOfStock() {
        DistributionCenter dc = DistributionCenter.builder().dcId("dc-1").build();
        when(nearbyDcService.findNearbyDcs(40.7128, -74.0060)).thenReturn(List.of(dc));

        Inventory invA = Inventory.builder().itemId("item-a").dcId("dc-1").quantity(10).reservedQuantity(0).build();
        // item-b only has 0 available (all reserved).
        Inventory invB = Inventory.builder().itemId("item-b").dcId("dc-1").quantity(3).reservedQuantity(3).build();
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-a", "dc-1")).thenReturn(Optional.of(invA));
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-b", "dc-1")).thenReturn(Optional.of(invB));

        assertThatThrownBy(() -> orderService.placeOrder(request()))
                .isInstanceOf(ItemUnavailableException.class);

        // Nothing committed.
        verify(orderRepository, never()).save(any());
        verify(orderItemRepository, never()).saveAll(any());
        verify(cacheService, never()).evictByDcId(anyString());
        // The available item must NOT have been reserved (all-or-nothing).
        assertThat(invA.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    void placeOrder_throwsNoDcFound_whenNoDcInRange() {
        when(nearbyDcService.findNearbyDcs(anyDouble(), anyDouble())).thenReturn(List.of());

        assertThatThrownBy(() -> orderService.placeOrder(request()))
                .isInstanceOf(NoDcFoundException.class);

        verify(inventoryRepository, never()).findByItemIdAndDcIdWithLock(anyString(), anyString());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void placeOrder_fallsThroughToNextDc_whenFirstCannotFulfillWholeOrder() {
        DistributionCenter dc1 = DistributionCenter.builder().dcId("dc-1").build();
        DistributionCenter dc2 = DistributionCenter.builder().dcId("dc-2").build();
        when(nearbyDcService.findNearbyDcs(40.7128, -74.0060)).thenReturn(List.of(dc1, dc2));

        // dc-1 has item-a but no item-b -> cannot fulfill whole order.
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-a", "dc-1"))
                .thenReturn(Optional.of(Inventory.builder().itemId("item-a").dcId("dc-1").quantity(10).reservedQuantity(0).build()));
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-b", "dc-1"))
                .thenReturn(Optional.empty());

        // dc-2 has both.
        Inventory a2 = Inventory.builder().itemId("item-a").dcId("dc-2").quantity(10).reservedQuantity(0).build();
        Inventory b2 = Inventory.builder().itemId("item-b").dcId("dc-2").quantity(10).reservedQuantity(0).build();
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-a", "dc-2")).thenReturn(Optional.of(a2));
        when(inventoryRepository.findByItemIdAndDcIdWithLock("item-b", "dc-2")).thenReturn(Optional.of(b2));

        when(itemRepository.findByItemIdIn(any())).thenReturn(List.of(
                Item.builder().itemId("item-a").name("Apple").price(BigDecimal.ONE).build(),
                Item.builder().itemId("item-b").name("Bread").price(BigDecimal.ONE).build()));

        PlaceOrderResponse response = orderService.placeOrder(request());

        assertThat(response.getFulfillingDcId()).isEqualTo("dc-2");
        assertThat(a2.getReservedQuantity()).isEqualTo(2);
        assertThat(b2.getReservedQuantity()).isEqualTo(1);
        verify(cacheService).evictByDcId("dc-2");
    }
}
