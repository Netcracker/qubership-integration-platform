package org.qubership.integration.platform.runtime.catalog.cloudcore.maas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicCreateOptions;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import jakarta.validation.constraints.NotEmpty;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.runtime.catalog.model.maas.MaasConfig;
import org.qubership.integration.platform.runtime.catalog.model.maas.kafka.MaasKafkaConfig;
import org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq.Entities;
import org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq.MaasRabbitmqConfig;
import org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq.Spec;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.ActionLog;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.EntityType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.actionlog.LogOperation;
import org.qubership.integration.platform.runtime.catalog.service.ActionsLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.runtime.catalog.model.maas.rabbitmq.MaasRabbitmqConstants.*;


@Slf4j
@Component
public class MaasService {

    private static final String MAAS_AGENT_URL = getMaasAgentUrl();
    private static final String MAAS_NAMESPACE_PLACEHOLDER = "${ENV_NAMESPACE}";

    private final KafkaMaaSClient kafkaMaaSClient;
    private final RabbitMaaSClient rabbitMaaSClient;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final RestTemplate restTemplateMS;
    private final ActionsLogService actionLogger;

    @Autowired
    public MaasService(
            KafkaMaaSClient kafkaMaaSClient,
            RabbitMaaSClient rabbitMaaSClient,
            ObjectMapper objectMapper,
            YAMLMapper yamlMapper,
            @Qualifier("restTemplateMS") RestTemplate restTemplate,
            ActionsLogService actionsLogService
    ) {
        this.kafkaMaaSClient = kafkaMaaSClient;
        this.rabbitMaaSClient = rabbitMaaSClient;
        this.jsonMapper = objectMapper;
        this.yamlMapper = yamlMapper;
        this.restTemplateMS = restTemplate;
        this.actionLogger = actionsLogService;
    }

    private static String getMaasAgentUrl() {
        return "http://maas-agent:8080";
    }

    public TopicAddress getOrCreateKafkaTopic(String namespace, String topicClassifierName) {
        try {
            TopicAddress result = kafkaMaaSClient.getOrCreateTopic(new Classifier(topicClassifierName, Classifier.NAMESPACE, namespace),
                    TopicCreateOptions.DEFAULTS);

            actionLogger.logAction(ActionLog.builder()
                    .entityType(EntityType.MAAS_KAFKA)
                    .entityName("Topic classifier: " + topicClassifierName)
                    .parentName(namespace)
                    .operation(LogOperation.CREATE_OR_UPDATE)
                    .build());

            return result;
        } catch (Exception e) {
            log.error("Failed to create kafka topic in MaaS", e);
            throw new MaasException("Failed to create kafka topic in MaaS", e);
        }
    }

    public VHost getRabbitVhost(String vHostName) throws MaasException {
        try {
            return rabbitMaaSClient.getVHost(vHostName);
        } catch (Exception e) {
            log.error("Failed to get rabbitmq vHost from MaaS", e);
            throw new MaasException("Failed to get rabbitmq vHost from MaaS", e);
        }
    }

    /**
     * Function to create RabbitMQ entities in MaaS.<br>
     * Will create exchange if passed.<br>
     * Will create queue if passed.<br>
     * Will NOT create vhost.<br>
     * Will create binding between exchange and queue with routingKey (if any) if both exchange and queue parameters passed.<br>
     */
    public void createRabbitmqEntities(String namespace,
                                       String vhost,
                                       String exchange,
                                       String queue,
                                       String routingKey) {
        applyConfiguration(buildRabbitmqConfig(namespace, vhost, exchange, queue, routingKey));

        actionLogger.logAction(ActionLog.builder()
                .entityType(EntityType.MAAS_RABBITMQ)
                .entityName(getActionLogEntityName(vhost, exchange, queue, routingKey))
                .parentName(namespace)
                .operation(LogOperation.CREATE_OR_UPDATE)
                .build());
    }

    public byte[] getMaasDeclarativeFileKafka(@NotEmpty String topicClassifierName) {
        try {
            return yamlMapper.writeValueAsBytes(buildKafkaConfig(MAAS_NAMESPACE_PLACEHOLDER, topicClassifierName));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Unable to generate declarative file", e);
        }
    }

    public byte[] getMaasDeclarativeFileRabbitMq(@NotEmpty String vhost, String exchange, String queue, String routingKey) {
        try {
            return yamlMapper.writeValueAsBytes(buildRabbitmqConfig(MAAS_NAMESPACE_PLACEHOLDER, vhost, exchange, queue, routingKey));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Unable to generate declarative file", e);
        }
    }

    private MaasRabbitmqConfig buildRabbitmqConfig(@NotEmpty String namespace, @NotEmpty String vhost, String exchange, String queue, String routingKey) {
        if (StringUtils.isEmpty(exchange) && StringUtils.isEmpty(queue)) {
            throw new IllegalArgumentException("Exchange and queue fields can't be empty at the same time.");
        }

        return MaasRabbitmqConfig.builder()
                .spec(Spec.builder()
                        .classifier(new Classifier(vhost, Classifier.NAMESPACE, namespace))
                        .entities(Entities.builder()
                                .exchanges(buildRabbitmqExchangeConfig(exchange))
                                .queues(buildRabbitmqQueueConfig(queue))
                                .bindings(buildRabbitmqBindingConfig(exchange, queue, routingKey))
                                .build())
                        .build())
                .build();
    }

    private MaasKafkaConfig buildKafkaConfig(@NotEmpty String namespace, @NotEmpty String topicClassifierName) {
        return MaasKafkaConfig.builder().spec(
                        Spec.builder().classifier(
                                        new Classifier(topicClassifierName, Classifier.NAMESPACE, namespace))
                                .build())
                .build();
    }

    @NotNull
    private static String getActionLogEntityName(String vhost, String exchange, String queue, String routingKey) {
        StringBuilder entityNameLog = new StringBuilder();

        entityNameLog.append("Vhost: ").append(vhost);
        if (StringUtils.isNotEmpty(exchange)) {
            entityNameLog.append(" Exchange: ").append(exchange);
        }
        if (StringUtils.isNotEmpty(queue)) {
            entityNameLog.append(" Queue: ").append(queue);
        }
        if (StringUtils.isNotEmpty(exchange) && StringUtils.isNotEmpty(queue)) {
            entityNameLog.append(" Binding routing key: ").append(routingKey);
        }
        return entityNameLog.toString();
    }

    private List<Map<String, Object>> buildRabbitmqExchangeConfig(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        HashMap<String, Object> entity = new HashMap<>();
        entity.put(ENTITY_NAME, name);

        // set default values
        entity.put(DURABLE, "true");
        entity.put(AUTO_DELETE, "false");
        entity.put(TYPE, "direct");

        return Collections.singletonList(entity);
    }

    private List<Map<String, Object>> buildRabbitmqQueueConfig(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        HashMap<String, Object> entity = new HashMap<>();
        entity.put(ENTITY_NAME, name);

        // set default values
        entity.put(DURABLE, "true");
        entity.put(AUTO_DELETE, "false");

        return Collections.singletonList(entity);
    }

    private List<Map<String, Object>> buildRabbitmqBindingConfig(String source, String destination,
                                                                 String defaultRoutingKey) {
        if (StringUtils.isBlank(source) || StringUtils.isBlank(destination)) {
            return null;
        }

        HashMap<String, Object> entity = new HashMap<>();
        entity.put(BINDING_SOURCE, source);
        entity.put(BINDING_DESTINATION, destination);

        if (StringUtils.isNotEmpty(defaultRoutingKey)) {
            entity.put(BINDING_ROUTING_KEY, defaultRoutingKey);
        }

        return Collections.singletonList(entity);
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    private String applyConfiguration(MaasConfig configuration) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<MaasConfig> request = new HttpEntity<>(configuration, headers);

        if (log.isDebugEnabled()) {
            try {
                log.debug("Apply MaaSConfiguration: {}", jsonMapper.writeValueAsString(configuration));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize MaaS configuration request", e);
            }
        }

        ResponseEntity<String> response = restTemplateMS
                .exchange(MAAS_AGENT_URL + "/api/v1/config", HttpMethod.POST, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            log.error(
                    "Failed to apply rabbitmq configuration, maas responded with non 2xx code: {}, {}",
                    response.getStatusCode(), response.getBody());

            String body = null;
            try {
                body = jsonMapper.writeValueAsString(request.getBody());
            } catch (Exception ignored) {
            }
            throw new MaasException(
                    "Failed to apply rabbitmq configuration, maas response with non 2xx code."
                            + " Request body: " + body
                            + ". Response body: " + response.getBody());
        }
        return response.getBody();
    }
}
