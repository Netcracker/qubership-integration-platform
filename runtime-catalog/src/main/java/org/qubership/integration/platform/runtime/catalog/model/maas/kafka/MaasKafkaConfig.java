package org.qubership.integration.platform.runtime.catalog.model.maas.kafka;

import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.maas.MaasConfig;
import org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq.Spec;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaasKafkaConfig implements MaasConfig {

    @Builder.Default
    private String apiVersion = "nc.maas.kafka/v1";

    @Builder.Default
    private String kind = "topic";

    private Spec spec;
}
