package org.qubership.integration.platform.engine.configuration.opensearch;

import org.qubership.integration.platform.engine.configuration.opensearch.condition.OpenSearchMaasKafkaClientCondition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Conditional;

@AutoConfiguration
@Conditional(OpenSearchMaasKafkaClientCondition.class)
public class OpenSearchMaasKafkaAutoConfiguration {
}
