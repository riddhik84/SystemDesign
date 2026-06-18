package com.systemdesign.tinder.dto;

import com.systemdesign.tinder.model.Swipe;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SwipeRequest {
    @NotNull(message = "Decision is required")
    private Swipe.Direction decision;
}
