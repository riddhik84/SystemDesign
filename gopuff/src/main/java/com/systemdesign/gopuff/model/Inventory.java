package com.systemdesign.gopuff.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Stock of a single {@link Item} at a single {@link DistributionCenter}.
 *
 * <p>Invariant: {@code 0 <= reservedQuantity <= quantity}. The amount a customer can
 * actually buy right now is the computed {@link #getAvailableQuantity() availableQuantity}
 * {@code = quantity - reservedQuantity}.
 *
 * <p>We model reservation rather than directly decrementing {@code quantity} so that:
 * <ul>
 *   <li>orders can be placed (reserve) and later cancelled (un-reserve) without losing the
 *       physical-on-hand count, and</li>
 *   <li>two concurrent buyers can never reserve the same unit — the row is locked
 *       {@code FOR UPDATE} during the reservation step.</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inventory_item_dc",
                columnNames = {"item_id", "dc_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "dc_id", nullable = false, length = 36)
    private String dcId;

    /** Total physical stock on hand at this DC. */
    @Column(nullable = false)
    private Integer quantity;

    /** Units held by orders that are PENDING/CONFIRMED but not yet shipped. */
    @Column(name = "reserved_quantity", nullable = false)
    private Integer reservedQuantity;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Units a new order may still claim. Not persisted; derived on read. */
    @Transient
    public int getAvailableQuantity() {
        int q = quantity == null ? 0 : quantity;
        int r = reservedQuantity == null ? 0 : reservedQuantity;
        return q - r;
    }

    @PrePersist
    void onCreate() {
        if (reservedQuantity == null) {
            reservedQuantity = 0;
        }
        if (quantity == null) {
            quantity = 0;
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
