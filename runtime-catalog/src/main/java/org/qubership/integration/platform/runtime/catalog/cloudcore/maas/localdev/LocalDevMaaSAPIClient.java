package org.qubership.integration.platform.runtime.catalog.cloudcore.maas.localdev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.impl.ApiUrlProvider;
import com.netcracker.cloud.maas.client.impl.apiversion.ServerApiVersion;
import com.netcracker.cloud.maas.client.impl.dto.conf.ConfigureResponse;
import com.netcracker.cloud.maas.client.impl.dto.kafka.v1.conf.TopicCreateInteraction;
import com.netcracker.cloud.maas.client.impl.dto.rabbit.v1.conf.VHostCreateInteraction;
import com.netcracker.cloud.maas.client.impl.http.HttpClient;
import com.netcracker.cloud.maas.client.impl.kafka.KafkaMaaSClientImpl;
import com.netcracker.cloud.maas.client.impl.rabbit.RabbitMaaSClientImpl;
import com.netcracker.cloud.tenantmanager.client.Tenant;
import com.netcracker.cloud.tenantmanager.client.TenantManagerConnector;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public class LocalDevMaaSAPIClient implements MaaSAPIClient {
    private final String agentUrl;
    private final HttpClient restClient;
    private final ApiUrlProvider apiUrlProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantManagerConnector tenantManagerConnector;

    public LocalDevMaaSAPIClient(String agentUrl, HttpClient restClient) {
        this.agentUrl = agentUrl;
        this.restClient = restClient;
        this.tenantManagerConnector = new TenantManagerConnector() {
            @Override
            public List<Tenant> getTenantList() {
                return Collections.emptyList();
            }

            @Override
            public void subscribe(Consumer<List<Tenant>> callback) {
            }

            @Override
            public boolean unsubscribe(Consumer<List<Tenant>> callback) {
                return false;
            }

            @Override
            public void close() throws Exception {

            }
        };
        this.apiUrlProvider = new ApiUrlProvider(new ServerApiVersion(restClient, agentUrl), agentUrl);
    }

    @Override
    public KafkaMaaSClient getKafkaClient() {
        return new KafkaMaaSClientImpl(restClient, () -> tenantManagerConnector, apiUrlProvider);
    }

    @Override
    public RabbitMaaSClient getRabbitClient() {
        return new RabbitMaaSClientImpl(restClient, apiUrlProvider);
    }

    public List<? extends ConfigureResponse> loadConfiguration(String config) throws IOException {
        return restClient.request(agentUrl + "/api/v1/config")
                .post(config, "plain/text")
                .expect(200, 201)
                .sendAndReceive(this::deserializeConfigResponse)
                .get();
    }

    @SneakyThrows
    List<? extends ConfigureResponse> deserializeConfigResponse(String responseBody) {
        final Map<String, Map<String, Class<? extends ConfigureResponse>>> apiVersions = Map.ofEntries(
                Map.entry("nc.maas.kafka/v1", Map.ofEntries(
                        Map.entry("topic", TopicCreateInteraction.class),
                        Map.entry("topic-template", TopicCreateInteraction.class),
                        Map.entry("lazy-topic", TopicCreateInteraction.class)
                )),
                Map.entry("nc.maas.rabbit/v1", Map.ofEntries(
                        Map.entry("vhost", VHostCreateInteraction.class)
                ))
        );

        ArrayNode responseArray = (ArrayNode) mapper.readTree(responseBody.getBytes(StandardCharsets.UTF_8));
        Iterator<JsonNode> it = responseArray.elements();
        List<ConfigureResponse> result = new ArrayList<>(responseArray.size());
        while (it.hasNext()) {
            JsonNode responseNode = it.next();
            JsonNode requestNode = responseNode.get("request");

            String apiVersion = requestNode.get("apiVersion").asText();
            Map<String, Class<? extends ConfigureResponse>> kinds = apiVersions.get(apiVersion);
            if (kinds == null) {
                throw new IllegalArgumentException("Unsupported apiVersion=`" + apiVersion + "'");
            }

            String kind = requestNode.get("kind").asText();
            Class<? extends ConfigureResponse> entityClass = kinds.get(kind);
            if (entityClass == null) {
                throw new IllegalArgumentException("Unsupported kind: apiVersion=`" + apiVersion + "', kind=`" + kind + "'");
            }

            result.add(mapper.treeToValue(responseNode, entityClass));
        }
        return result;
    }
}
