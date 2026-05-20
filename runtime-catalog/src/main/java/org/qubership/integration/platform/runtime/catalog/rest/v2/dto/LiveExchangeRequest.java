package org.qubership.integration.platform.runtime.catalog.rest.v2.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;

import java.util.Collections;
import java.util.List;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@Schema(description = "Live exchange request object")
public class LiveExchangeRequest {
    @Builder.Default
    @Schema(description = "List of filters")
    List<FilterRequestDTO> filters = Collections.emptyList();
}
