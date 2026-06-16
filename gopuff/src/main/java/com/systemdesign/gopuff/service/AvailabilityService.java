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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Read path for {@code GET /availability}.
 *
 * <p>Flow: cache lookup → nearby DCs → bulk inventory query → aggregate per item across DCs
 * → paginate → cache write. Designed to satisfy the &lt;100ms SLA at 20K QPS: the common
 * case is a single Redis GET, and even a cache miss is one geo scan plus one indexed
 * inventory query.
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    /** Fixed page size for availability results. */
    static final int PAGE_SIZE = 20;

    private final NearbyDcService nearbyDcService;
    private final InventoryRepository inventoryRepository;
    private final ItemRepository itemRepository;
    private final AvailabilityCacheService cacheService;

    public AvailabilityService(NearbyDcService nearbyDcService,
                               InventoryRepository inventoryRepository,
                               ItemRepository itemRepository,
                               AvailabilityCacheService cacheService) {
        this.nearbyDcService = nearbyDcService;
        this.inventoryRepository = inventoryRepository;
        this.itemRepository = itemRepository;
        this.cacheService = cacheService;
    }

    public AvailabilityResponse getAvailability(AvailabilityRequest req) {
        String cacheKey = buildCacheKey(req);

        // 1. Cache lookup (the 20K-QPS happy path).
        Optional<AvailabilityResponse> cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            log.debug("Availability cache HIT for key {}", cacheKey);
            return cached.get();
        }
        log.debug("Availability cache MISS for key {}", cacheKey);

        // 2. Find DCs that can deliver to this location.
        List<DistributionCenter> nearbyDcs =
                nearbyDcService.findNearbyDcs(req.getLatitude(), req.getLongitude());
        if (nearbyDcs.isEmpty()) {
            throw new NoDcFoundException(
                    "No distribution center serves location ("
                            + req.getLatitude() + ", " + req.getLongitude() + ")");
        }

        List<String> dcIds = nearbyDcs.stream()
                .map(DistributionCenter::getDcId)
                .collect(Collectors.toList());
        List<String> itemIds = req.getItemIds();

        // 3. Single bulk query for all (dc, item) inventory rows in scope.
        List<Inventory> inventories = inventoryRepository.findByDcIdInAndItemIdIn(dcIds, itemIds);

        // 4. Item names for the response (single bulk query).
        Map<String, String> itemNames = itemRepository.findByItemIdIn(itemIds).stream()
                .collect(Collectors.toMap(Item::getItemId, Item::getName, (a, b) -> a));

        // 5. Aggregate available quantity per item; track the DC with the most stock.
        Map<String, Integer> totalAvailable = new HashMap<>();
        Map<String, Integer> bestDcQty = new HashMap<>();
        Map<String, String> bestDcId = new HashMap<>();
        for (Inventory inv : inventories) {
            int avail = inv.getAvailableQuantity();
            if (avail <= 0) {
                continue;
            }
            totalAvailable.merge(inv.getItemId(), avail, Integer::sum);
            if (avail > bestDcQty.getOrDefault(inv.getItemId(), 0)) {
                bestDcQty.put(inv.getItemId(), avail);
                bestDcId.put(inv.getItemId(), inv.getDcId());
            }
        }

        // 6. Build a result for every requested item (preserving request order).
        List<ItemAvailability> all = new ArrayList<>(itemIds.size());
        for (String itemId : itemIds) {
            int qty = totalAvailable.getOrDefault(itemId, 0);
            all.add(ItemAvailability.builder()
                    .itemId(itemId)
                    .itemName(itemNames.get(itemId))
                    .available(qty > 0)
                    .quantity(qty)
                    .dcId(qty > 0 ? bestDcId.get(itemId) : null)
                    .build());
        }

        // 7. Paginate.
        int total = all.size();
        int from = Math.max(0, req.getPage()) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        List<ItemAvailability> pageItems = from >= total ? List.of() : all.subList(from, to);

        AvailabilityResponse response = AvailabilityResponse.builder()
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .page(req.getPage())
                .pageSize(PAGE_SIZE)
                .totalItems(total)
                .items(new ArrayList<>(pageItems))
                .build();

        // 8. Populate cache (60s TTL).
        cacheService.put(cacheKey, response);
        return response;
    }

    /**
     * Cache key: {@code {lat2dp}:{lon2dp}:{sorted,distinct itemIds}:{page}}.
     *
     * <p>Coordinates are rounded to 2 decimal places (~1.1km) so that nearby users share
     * cache entries — this dramatically raises hit rate without meaningfully changing which
     * DCs are in range. Item IDs are sorted+deduped so request ordering does not fragment
     * the cache.
     */
    String buildCacheKey(AvailabilityRequest req) {
        String lat = String.format(Locale.ROOT, "%.2f", req.getLatitude());
        String lon = String.format(Locale.ROOT, "%.2f", req.getLongitude());
        String items = req.getItemIds() == null ? "" :
                String.join(",", new TreeSet<>(req.getItemIds()));
        return lat + ":" + lon + ":" + items + ":" + req.getPage();
    }
}
