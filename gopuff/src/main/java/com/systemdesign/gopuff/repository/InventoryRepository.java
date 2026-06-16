package com.systemdesign.gopuff.repository;

import com.systemdesign.gopuff.model.Inventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /**
     * Bulk fetch used by the availability read path. A single query covering all nearby
     * DCs and all requested items, so we never N+1.
     */
    List<Inventory> findByDcIdInAndItemIdIn(List<String> dcIds, List<String> itemIds);

    /** All inventory rows for the items stocked at a DC (used for cache eviction scoping). */
    List<Inventory> findByDcId(String dcId);

    /**
     * Read a single inventory row with a pessimistic write lock. Hibernate emits
     * {@code SELECT ... FOR UPDATE}, so the row is locked for the duration of the
     * surrounding SERIALIZABLE transaction. This is the linchpin that prevents two
     * concurrent orders from reserving the same physical unit (see DESIGN.md §6).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.itemId = :itemId AND i.dcId = :dcId")
    Optional<Inventory> findByItemIdAndDcIdWithLock(@Param("itemId") String itemId,
                                                    @Param("dcId") String dcId);
}
