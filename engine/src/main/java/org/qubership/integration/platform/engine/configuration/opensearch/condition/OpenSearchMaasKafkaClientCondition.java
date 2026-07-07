package org.qubership.integration.platform.engine.configuration.opensearch.condition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class OpenSearchMaasKafkaClientCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean standaloneMode = Boolean.parseBoolean(context.getEnvironment().getProperty("qip.standalone"));
        boolean opensearchKafkaClientEnabled = Boolean.parseBoolean(context.getEnvironment().getProperty("qip.opensearch.kafka-client.enabled"));
        boolean maasKafkaEnabled = Boolean.parseBoolean(context.getEnvironment().getProperty("maas.kafka.enabled"));
        return !standaloneMode && opensearchKafkaClientEnabled && maasKafkaEnabled;
    }
}
