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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Write path for {@code POST /orders} — the consistency-critical part of the system.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Atomic, all-or-nothing.</b> The whole order is reserved inside one transaction;
 *       if any item is short, the transaction rolls back and nothing is reserved.</li>
 *   <li><b>No double-booking.</b> Two concurrent orders can never reserve the same physical
 *       unit. We use SERIALIZABLE isolation plus {@code SELECT ... FOR UPDATE} per inventory
 *       row (belt-and-suspenders — see DESIGN.md §6).</li>
 *   <li><b>Single-DC fulfillment.</b> The entire order ships from one nearby DC. We never
 *       split across DCs.</li>
 * </ul>
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final NearbyDcService nearbyDcService;
    private final InventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final AvailabilityCacheService cacheService;

    public OrderService(NearbyDcService nearbyDcService,
                        InventoryRepository inventoryRepository,
                        ItemRepository itemRepository,
                        OrderRepository orderRepository,
                        OrderItemRepository orderItemRepository,
                        AvailabilityCacheService cacheService) {
        this.nearbyDcService = nearbyDcService;
        this.inventoryRepository = inventoryRepository;
        this.itemRepository = itemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.cacheService = cacheService;
    }

    /**
     * Place an order atomically. Runs in a SERIALIZABLE transaction so the per-row locks
     * plus serializable conflict detection together rule out any double-sell, even under
     * concurrent buyers competing for the last unit.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PlaceOrderResponse placeOrder(PlaceOrderRequest req) {
        // 1. Which DCs can deliver to this location?
        List<DistributionCenter> nearbyDcs =
                nearbyDcService.findNearbyDcs(req.getLatitude(), req.getLongitude());
        if (nearbyDcs.isEmpty()) {
            throw new NoDcFoundException(
                    "No distribution center serves location ("
                            + req.getLatitude() + ", " + req.getLongitude() + ")");
        }

        // 2. Pick the nearest DC that can fulfill the ENTIRE order, then lock + reserve
        //    its rows within this same transaction.
        String fulfillingDcId = reserveAtFirstCapableDc(nearbyDcs, req.getItems());
        if (fulfillingDcId == null) {
            throw new ItemUnavailableException(
                    "No single nearby distribution center can fulfill the entire order");
        }

        // 3. Price snapshot for line items.
        Map<String, BigDecimal> prices = loadPrices(req.getItems());

        // 4. Persist the order header + line items.
        String orderId = UUID.randomUUID().toString();
        Order order = Order.builder()
                .orderId(orderId)
                .userId(req.getUserId())
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .fulfillingDcId(fulfillingDcId)
                .status(OrderStatus.CONFIRMED)
                .build();
        orderRepository.save(order);

        List<OrderItem> lineItems = new ArrayList<>(req.getItems().size());
        for (OrderLineItem li : req.getItems()) {
            lineItems.add(OrderItem.builder()
                    .orderId(orderId)
                    .itemId(li.getItemId())
                    .quantity(li.getQuantity())
                    .priceAtOrder(prices.getOrDefault(li.getItemId(), BigDecimal.ZERO))
                    .build());
        }
        orderItemRepository.saveAll(lineItems);

        // 5. Stock at the fulfilling DC just changed — invalidate cached availability.
        cacheService.evictByDcId(fulfillingDcId);

        log.info("Order {} CONFIRMED for user {} from DC {} ({} line items)",
                orderId, req.getUserId(), fulfillingDcId, lineItems.size());

        return PlaceOrderResponse.builder()
                .orderId(orderId)
                .status(OrderStatus.CONFIRMED)
                .fulfillingDcId(fulfillingDcId)
                .confirmedItems(req.getItems())
                .build();
    }

    /**
     * Walk DCs nearest-first. For the first DC that can satisfy every line, lock its rows
     * ({@code FOR UPDATE}), re-check availability under lock, and reserve. Returns that DC's
     * id, or {@code null} if no single DC can fulfill the whole order.
     *
     * <p>Because the locks are held to commit, a competing transaction that wants the same
     * rows blocks here and then re-reads our committed reservation — it cannot oversell.
     */
    private String reserveAtFirstCapableDc(List<DistributionCenter> nearbyDcs,
                                           List<OrderLineItem> items) {
        for (DistributionCenter dc : nearbyDcs) {
            String dcId = dc.getDcId();

            // Lock every required row for this DC up front, re-checking availability.
            Map<String, Inventory> locked = new HashMap<>();
            boolean canFulfill = true;
            for (OrderLineItem li : items) {
                Inventory inv = inventoryRepository
                        .findByItemIdAndDcIdWithLock(li.getItemId(), dcId)
                        .orElse(null);
                if (inv == null || inv.getAvailableQuantity() < li.getQuantity()) {
                    canFulfill = false;
                    break;
                }
                locked.put(li.getItemId(), inv);
            }
            if (!canFulfill) {
                continue; // try the next-nearest DC
            }

            // Reserve under lock.
            for (OrderLineItem li : items) {
                Inventory inv = locked.get(li.getItemId());
                inv.setReservedQuantity(inv.getReservedQuantity() + li.getQuantity());
                inventoryRepository.save(inv);
            }
            return dcId;
        }
        return null;
    }

    private Map<String, BigDecimal> loadPrices(List<OrderLineItem> items) {
        List<String> itemIds = items.stream().map(OrderLineItem::getItemId).toList();
        Map<String, BigDecimal> prices = new HashMap<>();
        for (Item item : itemRepository.findByItemIdIn(itemIds)) {
            prices.put(item.getItemId(), item.getPrice());
        }
        return prices;
    }
}
