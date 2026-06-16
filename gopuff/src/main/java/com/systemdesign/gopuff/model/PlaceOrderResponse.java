package com.systemdesign.gopuff.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Response body for {@code POST /orders}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlaceOrderResponse {

    private String orderId;
    private OrderStatus status;
    private String fulfillingDcId;
    private List<OrderLineItem> confirmedItems;
}
