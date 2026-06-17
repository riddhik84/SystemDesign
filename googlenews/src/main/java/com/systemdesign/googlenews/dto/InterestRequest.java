package com.systemdesign.googlenews.dto;

import com.systemdesign.googlenews.model.UserInterest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestRequest {
    @NotNull
    private UserInterest.InterestType type;

    @NotBlank
    private String value;
}
