package org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.netcracker.cloud.maas.client.api.Classifier;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(Include.NON_NULL)
public class Spec {
    private Classifier classifier;
    private Entities entities;
    private Deletions deletions;
}
