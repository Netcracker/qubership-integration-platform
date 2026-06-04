package org.qubership.integration.platform.ai.integration.catalog.client;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Mirrors runtime-catalog {@code ImportSpecificationDTO}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImportSpecificationDto(
    String id,
    String description,
    String warningMessage,
    @JsonAlias({"isDone", "done"}) boolean done,
    String specificationGroupId) {}
