package org.qubership.integration.platform.runtime.catalog.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Schema(description = "Result object of used system report")
public class SystemUsageResponse {
    @Schema(description = "Name of the service")
    private String service;

    @Schema(description = "Version of the specified service specification ")
    private String version;

    @Schema(description = "HTTP method of the REST API operation")
    private String method;

    @Schema(description = "Endpoint of the API operation")
    private String path;

    @Schema(description = "Id of the chain, which uses that service")
    private String chainId;

    @Schema(description = "Name of the chain, which uses that service")
    private String chainName;

    @Schema(description = "Id of the chain element, which uses that service")
    private String elementId;

    @Schema(description = "Name of the chain element, which uses that service")
    private String elementName;
}
