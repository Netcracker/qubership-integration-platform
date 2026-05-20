package org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq;

import lombok.*;
import org.qubership.integration.platform.runtime.catalog.model.maas.MaasConfig;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaasRabbitmqConfig implements MaasConfig {

    @Builder.Default
    private String apiVersion = "nc.maas.rabbit/v1";

    @Builder.Default
    private String kind = "vhost";

    private Spec spec;
}
