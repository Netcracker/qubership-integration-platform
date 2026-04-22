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
import org.apache.camel.CamelContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors.MaasParametersResolver;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.maas.kafka.AuthType;
import org.qubership.integration.platform.engine.metadata.MaasClassifierInfo;
import org.qubership.integration.platform.engine.service.VariablesService;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.engine.maas.rabbitmq.MaasRabbitMqConstants.DEFAULT_VHOST_CLASSIFIER_NAME;
import static org.qubership.integration.platform.engine.model.ElementOptions.*;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.*;

@Slf4j
@ApplicationScoped
public class MaasService implements MaasParametersResolver {
    private static final String FAILED_TO_RESOLVE_MAAS_PARAMETERS = "Failed to resolve MaaS parameters";

    @Inject
    CamelContext camelContext;

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

    @Override
    public String resolveMaasParameters(String content) {
        Map<String, String> replacementMap = new HashMap<>();
        for (MaasClassifierInfo info
                : camelContext.getRegistry().findByType(MaasClassifierInfo.class)) {
            replacementMap.putAll(updateKeysToPlaceholders(info.getElementId(), resolveMaasParameters(info)));
        }
        return replacePropertiesPlaceholders(content, replacementMap);
    }

    private Map<String, String> resolveMaasParameters(MaasClassifierInfo maasClassifierInfo) {
        String protocol = maasClassifierInfo.getProtocol();
        return switch (protocol) {
            case OPERATION_PROTOCOL_TYPE_KAFKA -> resolveKafkaMaasParameters(maasClassifierInfo);
            case OPERATION_PROTOCOL_TYPE_AMQP -> resolveRabbitmqMaasParameters(maasClassifierInfo);
            default -> throw new MaasException("Unsupported protocol: " + protocol);
        };
    }

    private Map<String, String> updateKeysToPlaceholders(String id, Map<String, String> m) {
        return m.entrySet().stream().collect(Collectors.toMap(
                e -> MaasUtils.getMaasParamPlaceholder(id, e.getKey()),
                Map.Entry::getValue
        ));
    }

    private String replacePropertiesPlaceholders(
            String content,
            Map<String, String> replacementMap
    ) {
        for (Map.Entry<String, String> entry : replacementMap.entrySet()) {
            String replacement = Optional.ofNullable(entry.getValue()).orElse("");
            content = StringUtils.isBlank(replacement)
                ? removePropertyPlaceholder(content, entry.getKey())
                : content.replace(entry.getKey(), replacement);
        }
        return content;
    }

    private String removePropertyPlaceholder(String deploymentXml, String placeholder) {
        String replacementPlaceholderRegexp = placeholder
                .replace("{", "\\{")
                .replace("}", "\\}");
        return deploymentXml
                .replaceAll("(&amp;)?[a-zA-Z0-9_-]+=" + replacementPlaceholderRegexp, "");
    }

    private Map<String, String> resolveKafkaMaasParameters(MaasClassifierInfo maasClassifierInfo) {
        TopicAddress kafkaTopic = getKafkaTopic(
                variablesService.injectVariables(maasClassifierInfo.getClassifier()),
                variablesService.injectVariables(maasClassifierInfo.getNamespace()),
                variablesService.injectVariables(maasClassifierInfo.getTenantId()),
                Boolean.parseBoolean(variablesService.injectVariables(maasClassifierInfo.getTenantEnabled()))
        );

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

        String id = maasClassifierInfo.getElementId();

        return Map.of(
                BROKERS, servers,
                SECURITY_PROTOCOL, protocol,
                SASL_MECHANISM, credType.map(MaasUtils::convertToSaslMechanism).orElse(""),
                SASL_JAAS_CONFIG, credType.map(t -> MaasUtils.buildJaasConfig(auth, t)).orElse(""),
                TOPICS, kafkaTopic.getTopicName(),
                OPERATION_PATH_TOPIC, kafkaTopic.getTopicName()
        );
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

    private Map<String, String> resolveRabbitmqMaasParameters(MaasClassifierInfo maasClassifierInfo) {
        String classifierName = variablesService.injectVariables(maasClassifierInfo.getClassifier());
        if (classifierName == null) {
            classifierName = DEFAULT_VHOST_CLASSIFIER_NAME;
        }
        if (StringUtils.isEmpty(classifierName)) {
            return Collections.emptyMap();
        }

        String classifierNamespace = variablesService.injectVariables(maasClassifierInfo.getNamespace());
        VHost vHost = getRabbitVhost(classifierName, classifierNamespace);
        try {
            URI address = new URI(vHost.getCnn());
            String password = vHost.getPassword();
            String username = vHost.getUsername();
            String vHostName = address.getPath().replaceFirst("/", "");
            String protocol = address.getScheme();
            boolean sslEnabled = Strings.CI.equals("amqps", protocol);
            String id = maasClassifierInfo.getElementId();

            return Map.of(
                    ADDRESSES, address.getHost() + ":" + address.getPort(),
                    VHOST, vHostName,
                    USERNAME, username,
                    PASSWORD, password,
                    SSL, sslEnabled ? Boolean.TRUE.toString() : ""
            );
        } catch (URISyntaxException exception) {
            throw new MaasException("Failed to get classifier" + classifierName + " from MaaS.", exception);
        }
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
