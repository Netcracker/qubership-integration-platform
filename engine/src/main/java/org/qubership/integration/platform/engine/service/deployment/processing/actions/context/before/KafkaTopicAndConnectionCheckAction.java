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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.apache.camel.spring.SpringCamelContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.qubership.integration.platform.engine.configuration.PredeployCheckKafkaConfiguration;
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

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "qip.camel.component.kafka.predeploy-check-enabled",
    havingValue = "true",
    matchIfMissing = true
)
@OnBeforeDeploymentContextCreated
public class KafkaTopicAndConnectionCheckAction extends ElementProcessingAction {
    private final VariablesService variablesService;
    private final PredeployCheckKafkaConfiguration predeployCheckKafkaConfiguration;

    @Autowired
    public KafkaTopicAndConnectionCheckAction(
        VariablesService variablesService,
        PredeployCheckKafkaConfiguration predeployCheckKafkaConfiguration
    ) {
        this.variablesService = variablesService;
        this.predeployCheckKafkaConfiguration = predeployCheckKafkaConfiguration;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        ChainElementType chainElementType = ChainElementType.fromString(
                properties.getProperties().get(ChainProperties.ELEMENT_TYPE));
        return ChainElementType.isKafkaAsyncElement(chainElementType);
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        Map<String, String> props = elementProperties.getProperties();
        try {
            String brokers = getProp(props, ElementOptions.BROKERS);
            String securityProtocol = getProp(props, ElementOptions.SECURITY_PROTOCOL);
            String saslMechanism = getProp(props, ElementOptions.SASL_MECHANISM);
            String saslJaasConfig = getProp(props, ElementOptions.SASL_JAAS_CONFIG);
            String topicsString = getProp(props, ElementOptions.TOPICS);

            if (brokers == null) {
                log.debug(
                    "Element with id {} not contains kafka connection params, skipping",
                    elementProperties.getElementId());
                return;
            }

            Map<String, Object> validationKafkaAdminConfig =
                predeployCheckKafkaConfiguration.createValidationKafkaAdminConfig(brokers,
                    securityProtocol, saslMechanism, saslJaasConfig);

            Set<String> topics;
            try (AdminClient client = AdminClient.create(validationKafkaAdminConfig)) {
                Set<String> kafkaTopics = client.listTopics().names().get();
                String[] topicsArray = topicsString.split(",");
                topics = new HashSet<>();
                if (topicsArray.length == 0) {
                    throw new KafkaException("Topic property can't be empty");
                }
                topics.add(topicsArray[0]); // take only first topic from string
                topics.removeAll(kafkaTopics);
            }

            if (!topics.isEmpty()) {
                String topicString = String.join(", ", topics);
                throw new DeploymentRetriableException(
                    "Kafka topics (" + topicString
                        + ") not found, check if this topics exists in kafka");
            }
        } catch (ExecutionException | KafkaException e) {
            // skip check if permissions denied
            if (e instanceof AuthorizationException
                || e.getCause() instanceof AuthorizationException) {
                log.warn(
                    "Kafka predeploy check is failed with AuthorizationException. Exception not thrown",
                    e);
            } else {
                log.warn("Kafka predeploy check is failed. " +
                        "Connection configuration is invalid, topics not found or broker is unavailable",
                    e);
                throw new DeploymentRetriableException(
                    "Kafka predeploy check is failed. " +
                        "Connection configuration is invalid, topics not found or broker is unavailable",
                    e);
            }
        } catch (DeploymentRetriableException e) {
            log.warn("Kafka predeploy check is failed with retriable exception", e);
            throw e;
        } catch (Exception e) {
            log.warn(
                "Failed to check kafka topic(s) or connection for deployment: {}, element: {}",
                deploymentInfo.getDeploymentId(),
                elementProperties.getElementId(),
                e
            );
        }
    }

    private String getProp(Map<String, String> properties, String name) {
        return variablesService.injectVariables(properties.get(name));
    }
}
