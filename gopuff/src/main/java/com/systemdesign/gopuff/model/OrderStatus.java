package com.systemdesign.gopuff.model;

/**
 * Lifecycle states of an {@link Order}.
 *
 * <ul>
 *   <li>{@code PENDING} — order row created, inventory not yet reserved (transient).</li>
 *   <li>{@code CONFIRMED} — inventory successfully reserved; order is accepted.</li>
 *   <li>{@code FAILED} — order could not be fulfilled (e.g. item went out of stock).</li>
 * </ul>
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
