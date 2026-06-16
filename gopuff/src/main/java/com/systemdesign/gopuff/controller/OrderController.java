package com.systemdesign.gopuff.controller;

import com.systemdesign.gopuff.model.PlaceOrderRequest;
import com.systemdesign.gopuff.model.PlaceOrderResponse;
import com.systemdesign.gopuff.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /orders} — place an atomic, all-or-nothing order.
 *
 * <p>Status codes: 201 on success, 409 ({@code ITEM_UNAVAILABLE}) if any item is out of
 * stock, 422 ({@code NO_DC_FOUND}) if no DC serves the location, 400 on validation errors.
 */
@RestController
@Tag(name = "Orders", description = "Place atomic multi-item orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    @Operation(summary = "Place an order (rejected entirely if any item is unavailable)")
    public ResponseEntity<PlaceOrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest req) {
        PlaceOrderResponse response = orderService.placeOrder(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
