package org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.*;

import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class Spec {
    private Map<String, String> classifier;
    private Entities entities;
    private Deletions deletions;
}
