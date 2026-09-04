package com.flashseats.flashseats.hold.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
/*could be change in the future based on the information we need represented here */

public record CreateHoldRequestDTO(
    @NotNull
    Long eventId,

    @NotNull
    Long tierId,

    @Min(1)
    Integer quantity,

    @NotBlank
    String userSessionId

) {}
