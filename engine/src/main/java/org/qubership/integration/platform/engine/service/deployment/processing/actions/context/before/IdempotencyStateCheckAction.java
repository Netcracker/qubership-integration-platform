package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(value = "qip.idempotency.enabled", havingValue = "false", matchIfMissing = false)
@OnBeforeDeploymentContextCreated
public class IdempotencyStateCheckAction extends ElementProcessingAction {
    @Override
    public boolean applicableTo(ElementProperties properties) {
                String elementType = properties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return (
            ChainElementType.HTTP_TRIGGER.equals(chainElementType)
            || ChainElementType.KAFKA_TRIGGER_2.equals(chainElementType)
            || ChainElementType.RABBITMQ_TRIGGER_2.equals(chainElementType)
            || ChainElementType.ASYNCAPI_TRIGGER.equals(chainElementType)
        ) && Boolean.valueOf(properties.getProperties().get(ChainProperties.IDEMPOTENCY_ENABLED));
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties properties,
        DeploymentInfo deploymentInfo
    ) {
        throw new RuntimeException("Idempotency support is disabled on environment");
    }
}
