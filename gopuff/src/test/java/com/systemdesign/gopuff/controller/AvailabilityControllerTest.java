package com.systemdesign.gopuff.controller;

import com.systemdesign.gopuff.exception.NoDcFoundException;
import com.systemdesign.gopuff.model.AvailabilityResponse;
import com.systemdesign.gopuff.model.ItemAvailability;
import com.systemdesign.gopuff.service.AvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AvailabilityController.class)
class AvailabilityControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AvailabilityService availabilityService;

    @Test
    void getAvailability_returns200_withItems() throws Exception {
        AvailabilityResponse response = AvailabilityResponse.builder()
                .latitude(40.71).longitude(-74.01).page(0).pageSize(20).totalItems(1)
                .items(List.of(ItemAvailability.builder()
                        .itemId("item-a").itemName("Apple").available(true).quantity(5).dcId("dc-1").build()))
                .build();
        when(availabilityService.getAvailability(any())).thenReturn(response);

        mockMvc.perform(get("/availability")
                        .param("latitude", "40.7128")
                        .param("longitude", "-74.0060")
                        .param("items", "item-a", "item-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].itemId").value("item-a"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    void getAvailability_returns422_whenNoDcFound() throws Exception {
        when(availabilityService.getAvailability(any()))
                .thenThrow(new NoDcFoundException("no dc"));

        mockMvc.perform(get("/availability")
                        .param("latitude", "0.0")
                        .param("longitude", "0.0")
                        .param("items", "item-a"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NO_DC_FOUND"));
    }
}
