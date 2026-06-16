package com.systemdesign.gopuff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Availability of one requested item, aggregated across all nearby distribution centers.
 *
 * <ul>
 *   <li>{@code available} — true if the total available quantity across nearby DCs &gt; 0.</li>
 *   <li>{@code quantity} — the total available quantity summed across nearby DCs.</li>
 *   <li>{@code dcId} — the single DC with the most stock for this item (the preferred
 *       fulfiller). Null when the item is unavailable.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemAvailability {

    private String itemId;
    private String itemName;
    private boolean available;
    private int quantity;
    private String dcId;
}
