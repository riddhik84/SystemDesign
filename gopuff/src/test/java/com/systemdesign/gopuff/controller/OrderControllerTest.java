package com.systemdesign.gopuff.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.systemdesign.gopuff.exception.ItemUnavailableException;
import com.systemdesign.gopuff.model.OrderLineItem;
import com.systemdesign.gopuff.model.OrderStatus;
import com.systemdesign.gopuff.model.PlaceOrderRequest;
import com.systemdesign.gopuff.model.PlaceOrderResponse;
import com.systemdesign.gopuff.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private OrderService orderService;

    private PlaceOrderRequest validRequest() {
        return PlaceOrderRequest.builder()
                .userId("user-1")
                .latitude(40.7128).longitude(-74.0060)
                .items(List.of(OrderLineItem.builder().itemId("item-a").quantity(2).build()))
                .build();
    }

    @Test
    void placeOrder_returns201_onSuccess() throws Exception {
        PlaceOrderResponse response = PlaceOrderResponse.builder()
                .orderId("order-123").status(OrderStatus.CONFIRMED).fulfillingDcId("dc-1")
                .confirmedItems(validRequest().getItems())
                .build();
        when(orderService.placeOrder(any())).thenReturn(response);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("order-123"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.fulfillingDcId").value("dc-1"));
    }

    @Test
    void placeOrder_returns409_whenItemUnavailable() throws Exception {
        when(orderService.placeOrder(any()))
                .thenThrow(new ItemUnavailableException("out of stock"));

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ITEM_UNAVAILABLE"));
    }

    @Test
    void placeOrder_returns400_onValidationError() throws Exception {
        PlaceOrderRequest invalid = PlaceOrderRequest.builder()
                .userId("")            // blank
                .latitude(40.0).longitude(-74.0)
                .items(List.of())      // empty
                .build();

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
