/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.before;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.ConnectionSourceType;
import org.qubership.integration.platform.engine.model.constants.EnvironmentSourceType;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "qip.camel.component.rabbitmq.predeploy-check-enabled",
    havingValue = "true",
    matchIfMissing = true
)
@OnBeforeDeploymentContextCreated
public class AmpqConnectionCheckAction extends ElementProcessingAction {
    private final VariablesService variablesService;

    @Autowired
    public AmpqConnectionCheckAction(
        VariablesService variablesService
    ) {
        this.variablesService = variablesService;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        Map<String, String> props = properties.getProperties();
        ChainElementType chainElementType = ChainElementType
                .fromString(props.get(ChainProperties.ELEMENT_TYPE));
        String connectionSourceType = props.get(ElementOptions.CONNECTION_SOURCE_TYPE_PROP);
        String operationProtocolType = getProp(props, ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);

        return ChainElementType.isAmqpAsyncElement(chainElementType)
            && (equalsIgnoreCase(ConnectionSourceType.MAAS, connectionSourceType)
                    || equalsIgnoreCase(EnvironmentSourceType.MAAS_BY_CLASSIFIER, connectionSourceType))
            && (!(
                (equalsIgnoreCase(ChainElementType.ASYNCAPI_TRIGGER, chainElementType.name())
                        || equalsIgnoreCase(ChainElementType.SERVICE_CALL, chainElementType.name()))
                && !ChainProperties.OPERATION_PROTOCOL_TYPE_AMQP.equals(operationProtocolType)
            ));
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        ChainElementType chainElementType = ChainElementType.fromString(
                elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE));
        try {
            Map<String, String> props = elementProperties.getProperties();

            boolean isProducerElement = ChainElementType.isAmqpProducerElement(
                chainElementType);

            String exchange = getProp(props, ElementOptions.EXCHANGE);
            String queues = getProp(props, ElementOptions.QUEUES);
            String addresses = getProp(props, ElementOptions.ADDRESSES);
            String username = getProp(props, ElementOptions.USERNAME);
            String password = getProp(props, ElementOptions.PASSWORD);
            String vhost = getProp(props, ElementOptions.VHOST);
            String ssl = getProp(props, ElementOptions.SSL);

            if (StringUtils.isBlank(exchange) || StringUtils.isBlank(addresses)) {
                throw new IllegalArgumentException(
                    "AMQP mandatory parameters are missing, check configuration");
            }
            if (!addresses.matches("^[\\w.,:\\-_]+$")) {
                throw new IllegalArgumentException(
                    "AMQP addresses has invalid format, check configuration");
            }

            ConnectionFactory factory = new ConnectionFactory();

            factory.setUri((StringUtils.isNotBlank(ssl) && ssl.equals("true") ? "amqps://" : "amqp://") + addresses);

            if (StringUtils.isNotBlank(username)) {
                factory.setUsername(username);
            }

            if (StringUtils.isNotBlank(password)) {
                factory.setPassword(password);
            }

            if (StringUtils.isNotBlank(vhost)) {
                factory.setVirtualHost(vhost);
            }

            try (Connection connection = factory.newConnection()) {
                Channel channel = connection.createChannel();

                try {
                    if (isProducerElement) {
                        channel.exchangeDeclarePassive(exchange);
                    } else {
                        channel.queueDeclarePassive(queues);
                    }
                } catch (IOException e) {
                    throw new DeploymentRetriableException(
                        "AMQP " + (isProducerElement ? ("exchange " + exchange) : ("queue(s) " + queues))
                                + " not found, check configuration");
                }
            } catch (IOException e) {
                throw new DeploymentRetriableException(
                    "Connection configuration is invalid or broker is unavailable", e);
            }
        } catch (IllegalArgumentException e) {
            log.error("AMQP predeploy check is failed", e);
            throw e;
        } catch (DeploymentRetriableException e) {
            log.warn("AMQP predeploy check is failed with retriable exception", e);
            throw e;
        } catch (Exception e) {
            log.warn(
                "Failed to check amqp connection for deployment: {}, element: {}",
                deploymentInfo.getDeploymentId(),
                elementProperties.getElementId(),
                e
            );
        }
    }

    private static <E extends Enum<E>> boolean equalsIgnoreCase(E e, String s) {
        return e.name().equalsIgnoreCase(s);
    }

    private String getProp(Map<String, String> properties, String name) {
        return variablesService.injectVariables(properties.get(name));
    }
}
