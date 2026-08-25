package org.qubership.integration.platform.io.model.exportimport.system;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

// Export/import projection of an operation, mirroring api-operation.schema.yaml: flat, discriminated by "type".
// javaPackage (protobuf) and sdl (graphql) are QIP fields outside the shared schema; they carry in the file because
// they are the only inputs that reconstruct path for those two protocols, and dropping them broke the round trip.
// specification is a QIP field too — the async resolvers store the MaaS classifier name in it. All three ride under
// the schema's open additionalProperties.
@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({ "id", "name", "description", "type" })
public class ApiOperationDto {
    private String id;
    private String name;
    private String description;
    private String type;

    private String summary;
    private String path;
    private String method;
    private Boolean isDeprecated;
    private String channel;
    private String protocol;
    private String binding;
    private String operationType;
    private String sdl;

    @JsonProperty("package")
    private String packageName;
    private String service;
    private String rpcMethod;
    private String javaPackage;

    private JsonNode specification;
}
