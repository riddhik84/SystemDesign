package com.systemdesign.ticketmaster.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HoldRequest {
    @NotBlank
    private String eventId;

    @NotEmpty
    private List<String> seatIds;

    @NotBlank
    private String userId;
}
