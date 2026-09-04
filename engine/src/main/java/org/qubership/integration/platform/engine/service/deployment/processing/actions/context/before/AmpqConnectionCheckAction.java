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
import org.apache.camel.component.springrabbit.SpringRabbitMQHelper;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnBeforeDeploymentContextCreated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

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
        String operationProtocolType = getProp(props, ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);

        // Every AMQP element, whatever its connection came from. A manual connection is the
        // default the element ships with, and it used to be the one nothing checked: the queue or
        // exchange was missing, the deployment reported DEPLOYED, and only the engine log said
        // otherwise. The Kafka check has never made this distinction.
        return ChainElementType.isAmqpAsyncElement(chainElementType)
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

            // What the check itself needs. The exchange is not on the list: a consumer that
            // declares nothing never touches it, and a producer that names none publishes through
            // the default exchange.
            if (StringUtils.isBlank(addresses)
                    || (!isProducerElement && StringUtils.isBlank(queues))) {
                throw new IllegalArgumentException(
                    "AMQP mandatory parameters are missing, check configuration");
            }
            if (!addresses.matches("^[\\w.,:\\-_]+$")) {
                throw new IllegalArgumentException(
                    "AMQP addresses has invalid format, check configuration");
            }

            try (Connection connection = connectionFactory(addresses, username, password, vhost, ssl)
                    .newConnection()) {
                assertTopologyExists(connection.createChannel(), isProducerElement, exchange, queues);
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

    static ConnectionFactory connectionFactory(
        String addresses,
        String username,
        String password,
        String vhost,
        String ssl
    ) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
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
        return factory;
    }

    /**
     * Asks the broker for what the route will use, without creating anything: a producer publishes to
     * the exchange, a consumer reads from each of its queues.
     */
    static void assertTopologyExists(
        Channel channel,
        boolean isProducerElement,
        String exchange,
        String queues
    ) {
        if (isProducerElement) {
            // The broker pre-declares the default exchange and refuses to declare it again, even
            // passively. Camel sends through it whenever the name is empty or "default".
            if (SpringRabbitMQHelper.isDefaultExchange(exchange)) {
                return;
            }
            try {
                channel.exchangeDeclarePassive(exchange);
            } catch (IOException e) {
                throw new DeploymentRetriableException(
                    "AMQP exchange " + exchange + " not found, check configuration");
            }
            return;
        }

        for (String queue : queueNames(queues)) {
            try {
                channel.queueDeclarePassive(queue);
            } catch (IOException e) {
                // Quoted because the name is reproduced exactly as the consumer will ask for it,
                // and a stray space around a comma is otherwise invisible.
                throw new DeploymentRetriableException(
                    "AMQP queue '" + queue + "' not found, check configuration");
            }
        }
    }

    /**
     * The element takes its queues as a comma-separated list, while a passive declare names one
     * queue, so the list has to be walked rather than handed over whole.
     *
     * <p>Split the way Camel splits it, with no trimming: the consumer calls
     * {@code getQueues().split(",")} and consumes from the pieces verbatim, so a name written
     * after a space really is a name with a leading space. Trimming here would let the check pass
     * a configuration the consumer then fails on, which is the fault this check exists to catch.
     */
    static List<String> queueNames(String queues) {
        return isNull(queues) ? List.of() : List.of(queues.split(","));
    }

    private static <E extends Enum<E>> boolean equalsIgnoreCase(E e, String s) {
        return e.name().equalsIgnoreCase(s);
    }

    private String getProp(Map<String, String> properties, String name) {
        return variablesService.injectVariables(properties.get(name));
    }
}
