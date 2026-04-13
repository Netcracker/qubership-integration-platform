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

package org.qubership.integration.platform.engine.maas;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicUserCredentials;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.StringEscapeUtils;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.maas.kafka.AuthType;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.ConnectionSourceType;
import org.qubership.integration.platform.engine.model.constants.EnvironmentSourceType;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.engine.maas.rabbitmq.MaasRabbitMqConstants.DEFAULT_VHOST_CLASSIFIER_NAME;
import static org.qubership.integration.platform.engine.model.ElementOptions.*;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.*;

@Slf4j
@ApplicationScoped
public class MaasService {
    private static final String FAILED_TO_RESOLVE_MAAS_PARAMETERS = "Failed to resolve MaaS parameters";

    @Inject
    VariablesService variablesService;

    @Inject
    RabbitMaaSClient rabbitMaasClient;

    @Inject
    KafkaMaaSClient kafkaMaasClient;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Inject
    TenantConfiguration tenantConfiguration;

    public String resolveDeploymentMaasParameters(
            DeploymentConfiguration configuration,
            String configurationXml
    ) throws URISyntaxException {
        for (ElementProperties props : configuration.getProperties()) {
            Map<String, String> propMap = props.getProperties();
            String connectionSource = propMap.get(CONNECTION_SOURCE_TYPE_PROP);
            ChainElementType elementType = ChainElementType.fromString(propMap.get(ELEMENT_TYPE));
            Map<String, String> propertiesToReplace = Collections.emptyMap();

            switch (elementType) {
                case ASYNCAPI_TRIGGER, SERVICE_CALL -> {
                    if (!EnvironmentSourceType.MAAS_BY_CLASSIFIER.toString().equalsIgnoreCase(connectionSource)) {
                        continue;
                    }
                    switch (propMap.get(OPERATION_PROTOCOL_TYPE_PROP)) {
                        case (OPERATION_PROTOCOL_TYPE_KAFKA) -> {
                            propertiesToReplace = resolveServiceKafkaMaasParameters(propMap);
                        }
                        case (OPERATION_PROTOCOL_TYPE_AMQP) -> {
                            propertiesToReplace = resolveRabbitmqMaasParameters(propMap);
                        }
                    }
                }
                case KAFKA_TRIGGER_2, KAFKA_SENDER_2 -> {
                    if (!ConnectionSourceType.MAAS.toString().equalsIgnoreCase(connectionSource)) {
                        continue;
                    }
                    propertiesToReplace = resolveKafkaMaasParameters(propMap);
                }
                case RABBITMQ_TRIGGER_2, RABBITMQ_SENDER_2 -> {
                    if (!ConnectionSourceType.MAAS.toString().equalsIgnoreCase(connectionSource)) {
                        continue;
                    }
                    propertiesToReplace = resolveRabbitmqMaasParameters(propMap);
                }
            }
            if (!propertiesToReplace.isEmpty()) {
                configurationXml = replacePropertiesPlaceholders(configurationXml, propMap, propertiesToReplace);
            }
        }

        return configurationXml;
    }

    private String replacePropertiesPlaceholders(
            String deploymentXml,
            Map<String, String> elementProperties,
            Map<String, String> propertiesToReplace
    ) {
        String elementId = elementProperties.get(ELEMENT_ID);
        for (Map.Entry<String, String> entry : propertiesToReplace.entrySet()) {
            String replacementString = MaasUtils.getMaasParamPlaceholder(elementId, entry.getKey());
            String replacement = Optional.ofNullable(entry.getValue()).orElse("");
            if (StringUtils.isBlank(replacement)) {
                deploymentXml = removePropertyPlaceholder(deploymentXml, replacementString);
            } else {
                deploymentXml = deploymentXml.replace(replacementString, StringEscapeUtils.escapeXml10(replacement));
            }
            deploymentXml = deploymentXml.replace(replacementString, StringEscapeUtils.escapeXml10(replacement));
            elementProperties.replaceAll((k, v) -> v == null ? null : v.replace(replacementString, replacement));
        }
        return deploymentXml;
    }

    private String removePropertyPlaceholder(String deploymentXml, String placeholder) {
        String replacementPlaceholderRegexp = placeholder
                .replace("{", "\\{")
                .replace("}", "\\}");
        return deploymentXml
                .replaceAll("(&amp;)?[a-zA-Z0-9_-]+=" + replacementPlaceholderRegexp, "");
    }

    private Map<String, String> resolveKafkaMaasParameters(Map<String, String> propMap) {
        return resolveKafkaMaasMainParameters(propMap, TOPICS);
    }

    private Map<String, String> resolveKafkaMaasMainParameters(Map<String, String> propMap, String topicsVariableName) {
        String topicClassifier = getProperty(propMap, MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (StringUtils.isEmpty(topicClassifier)) {
            return Collections.emptyMap();
        }

        String classifierNamespace = getProperty(propMap, MAAS_CLASSIFIER_NAMESPACE_PROP);
        String classifierTenantId = getProperty(propMap, MAAS_CLASSIFIER_TENANT_ID_PROP);
        String tenantTopicEnabled = getProperty(propMap, MAAS_CLASSIFIER_TENANT_ENABLED_PROP);
        TopicAddress kafkaTopic = getKafkaTopic(
                topicClassifier, classifierNamespace, classifierTenantId,
                StringUtils.isNotEmpty(tenantTopicEnabled) && Boolean.parseBoolean(tenantTopicEnabled));

        String protocol = MaasUtils.selectProtocol(kafkaTopic);
        Optional<AuthType> credType = MaasUtils.selectCredType(kafkaTopic);
        String servers = kafkaTopic.getBoostrapServers(protocol);

        if (servers == null) {
            throw new MaasException(FAILED_TO_RESOLVE_MAAS_PARAMETERS
                    + ": servers for protocol " + protocol + " not found");
        }

        TopicUserCredentials auth = credType
                .map(AuthType::getName)
                .flatMap(kafkaTopic::getCredentials)
                .orElse(null);

        Map<String, String> resolvedProperties = new HashMap<>();
        resolvedProperties.put(BROKERS, servers);
        resolvedProperties.put(SECURITY_PROTOCOL, protocol);
        resolvedProperties.put(SASL_MECHANISM, MaasUtils.convertToSaslMechanism(credType.orElse(null)));
        resolvedProperties.put(SASL_JAAS_CONFIG, MaasUtils.buildJaasConfig(auth, credType.orElse(null)));
        resolvedProperties.put(topicsVariableName, kafkaTopic.getTopicName());

        return resolvedProperties;
    }

    private Map<String, String> resolveServiceKafkaMaasParameters(Map<String, String> propMap) {
        return resolveKafkaMaasMainParameters(propMap, OPERATION_PATH_TOPIC);
    }

    public TopicAddress getKafkaTopic(
            String classifierName,
            String classifierNamespace,
            String classifierTenantId,
            boolean tenantTopicEnabled
    ) throws MaasException {
        try {
            Classifier classifier = new Classifier(classifierName);
            classifier.namespace(StringUtils.isNotEmpty(classifierNamespace)
                    ? classifierNamespace : applicationConfiguration.getNamespace());
            if (tenantTopicEnabled) {
                classifier.tenantId(StringUtils.isNotEmpty(classifierTenantId)
                        ? classifierTenantId : tenantConfiguration.getDefaultTenant());
            }
            return kafkaMaasClient.getTopic(classifier).orElseThrow(() -> new TopicNotFoundException(
                    String.format("Failed to get classifier %s from MaaS. ", classifierName)));
        } catch (Exception e) {
            throw new MaasException("Failed to get classifier " + classifierName + " from MaaS. ", e);
        }
    }

    private String getProperty(Map<String, String> propMap, String propertyName) {
        return variablesService.injectVariables(propMap.get(propertyName));
    }

    private Map<String, String> resolveRabbitmqMaasParameters(Map<String, String> propMap) throws URISyntaxException {
        String classifierName = getProperty(propMap, MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (classifierName == null) {
            classifierName = DEFAULT_VHOST_CLASSIFIER_NAME;
        }
        if (StringUtils.isEmpty(classifierName)) {
            return Collections.emptyMap();
        }

        String classifierNamespace = getProperty(propMap, MAAS_CLASSIFIER_NAMESPACE_PROP);
        VHost vHost = getRabbitVhost(classifierName, classifierNamespace);
        URI address = new URI(vHost.getCnn());
        String password = vHost.getPassword();
        String username = vHost.getUsername();
        String vHostName = address.getPath().replaceFirst("/", "");
        String protocol = address.getScheme();

        Map<String, String> resolvedProperties = new HashMap<>();
        resolvedProperties.put(ADDRESSES, address.getHost() + ":" + address.getPort());
        resolvedProperties.put(VHOST, vHostName);
        resolvedProperties.put(USERNAME, username);
        resolvedProperties.put(PASSWORD, password);
        boolean sslEnabled = Strings.CI.equals("amqps", protocol);
        resolvedProperties.put(SSL, sslEnabled ? Boolean.TRUE.toString() : "");

        // patch element properties for AMQP predeploy check
        if (sslEnabled) {
            propMap.put(SSL, Boolean.TRUE.toString());
        } else {
            propMap.remove(SSL);
        }

        return resolvedProperties;
    }

    public VHost getRabbitVhost(String vHostName, String classifierNamespace) throws MaasException {
        try {
            Classifier classifier = new Classifier(vHostName);
            classifier.namespace(StringUtils.isNotEmpty(classifierNamespace)
                    ? classifierNamespace
                    : applicationConfiguration.getNamespace());
            VHost virtualHost = rabbitMaasClient.getVirtualHost(classifier);
            if (virtualHost == null) {
                throw new RuntimeException("vHost not found");
            }
            return virtualHost;
        } catch (Exception e) {
            log.error("Failed to get rabbitmq vHost from MaaS", e);
            throw new MaasException("Failed to get rabbitmq vHost from MaaS", e);
        }
    }
}
