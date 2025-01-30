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

package org.qubership.integration.platform.engine.configuration.opensearch;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.engine.IntegrationEngineApplication;
import org.qubership.integration.platform.engine.model.opensearch.OpenSearchFieldType;
import org.qubership.integration.platform.engine.opensearch.DefaultOpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchField;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.opensearch.ism.model.Conditions;
import org.qubership.integration.platform.engine.opensearch.ism.model.FailedIndex;
import org.qubership.integration.platform.engine.opensearch.ism.model.ISMTemplate;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.model.State;
import org.qubership.integration.platform.engine.opensearch.ism.model.Transition;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.DeleteAction;
import org.qubership.integration.platform.engine.opensearch.ism.model.actions.RolloverAction;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.PolicyResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.RequestHelper;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ExpandWildcard;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Async;

import static org.qubership.integration.platform.engine.opensearch.ism.rest.RequestHelper.processHttpResponse;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
@Component
public class OpenSearchInitializer {
    public static final long TEMPLATE_VERSION = 4L;

    @Value("${qip.opensearch.index.elements.shards:3}")
    private int indexShardsAmount;

    @Value("${qip.opensearch.rollover.min_index_age:1d}")
    private TimeValue minIndexAge;

    @Value("${qip.opensearch.rollover.min_index_size:}")
    private String minIndexSize;

    @Value("${qip.opensearch.rollover.min_rollover_age_to_delete:14d}")
    private TimeValue minRolloverAgeToDelete;


    private final Environment environment;
    private final ObjectMapper jsonMapper;
    private final OpenSearchClientSupplier openSearchClientSupplier;


    public OpenSearchInitializer(
        Environment environment,
        @Qualifier("jsonMapper") ObjectMapper jsonMapper,
        OpenSearchClientSupplier openSearchClientSupplier
    ) {
        this.environment = environment;
        this.jsonMapper = jsonMapper;
        this.openSearchClientSupplier = openSearchClientSupplier;
    }

    @PostConstruct
    public void initialize() {
        log.info("Update opensearch template and indexes");
        updateTemplateAndIndexes(openSearchClientSupplier.getClient());
    }

    private void updateTemplateAndIndexes(OpenSearchClient client) {
        String packageRoot = IntegrationEngineApplication.class.getPackage().getName();
        Set<Class<?>> indexClasses = new Reflections(
            new ConfigurationBuilder().forPackages(packageRoot))
            .getTypesAnnotatedWith(OpenSearchDocument.class);
        for (Class<?> indexClass : indexClasses) {
            OpenSearchDocument osd = indexClass.getAnnotation(OpenSearchDocument.class);
            String documentName = environment.getProperty(osd.documentNameProperty());
            if (documentName == null) {
                log.error("Failed to get document name from property {}. Skipping creation of policies, index template, and indices for {}.",
                        osd.documentNameProperty(), indexClass.getName());
                continue;
            }
            log.info("Creating policies, index template, and indices for {} - {}.", indexClass.getName(), documentName);
            try {
                Map<String, Object> mapping = getIndexMapSource(indexClass);
                if (!mapping.isEmpty()) {
                    String prefix = openSearchClientSupplier.normalize(documentName);
                    createOrUpdatePolicy(client, buildRolloverPolicy(prefix));
                    updateTemplate(client, prefix, mapping);
                    updateIndices(client, prefix, mapping);
                }
            } catch (Exception exception) {
                log.error("Failed to create or update index template, policies, and indices for {}.", documentName, exception);
            }
        }
    }

    private void updateTemplate(OpenSearchClient client, String prefix, Map<String, Object> mapping) {
        String templateName = getIndexTemplateName(prefix);
        List<String> indexPatterns = getIndexPatterns(prefix);
        log.info("Updating index template {} for index pattern(s) {}.", templateName, String.join(", ", indexPatterns));
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("index_patterns", indexPatterns);
            request.put("priority", 1);
            request.put("version", TEMPLATE_VERSION);

            Map<String, Object> template = new HashMap<>();
            template.put("settings", getIndexSettings(prefix));
            template.put("mappings", mapping);

            request.put("template", template);

            processHttpResponse(client.generic().execute(RequestHelper.buildPutIndexTemplateRequest(jsonMapper, templateName, request)));
        } catch (Exception e) {
            log.error("Failed to create or update OpenSearch template {} for index pattern(s) {}.",
                    templateName, String.join(", ", indexPatterns), e);
        }
    }

    private void updateIndices(OpenSearchClient client, String prefix, Map<String, Object> mapping) {
        createOrUpdateRolloverIndices(client, prefix, mapping);
        updateOldIndex(client, getOldIndexName(prefix), getAliasName(prefix), mapping);
    }

    private void createOrUpdateRolloverIndices(OpenSearchClient client, String prefix, Map<String, Object> mapping) {
        List<String> indices;
        String mask = getIndexNameMask(prefix);
        try {
            log.info("Requesting indices that match mask {}.", mask);
            GetIndexRequest request = new GetIndexRequest.Builder().index(mask).expandWildcards(ExpandWildcard.Open).build();
            GetIndexResponse response = client.indices().get(request);
            indices = response.result().keySet().stream().filter(name -> !name.equals(getOldIndexName(prefix))).toList();
        } catch (IOException exception) {
            log.error("Failed to get indices by mask {}.", mask, exception);
            return;
        }
        if (indices.isEmpty()) {
            log.info("Indices that match mask {} not found.", mask);
            createRolloverIndex(client, prefix, mapping);
        } else {
            log.info("Found {} indices that match mask: {}.", indices.size(), String.join(", ", indices));
            for (String indexName : indices) {
                updateIndexMapping(client, indexName, mapping);
                tryToAddPolicyToIndex(client, indexName, getRolloverPolicyId(prefix));
            }
        }
    }
    
    private void createRolloverIndex(OpenSearchClient client, String prefix, Map<String, Object> mapping) {
        String indexName = getFirstRolloverIndexName(prefix);
        log.info("Creating index {}.", indexName);
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("settings", getIndexSettings(prefix));
            request.put("mappings", mapping);
            request.put("aliases", Map.of(getAliasName(prefix), Map.of("is_write_index", true)));

            processHttpResponse(client.generic().execute(RequestHelper.buildCreateIndexRequest(jsonMapper, indexName, request)));
        } catch (IOException exception) {
            log.error("Failed to create index {}.", indexName, exception);
        }
    }

    @Deprecated(since = "24.1")
    private void updateOldIndex(
            OpenSearchClient client,
            String indexName,
            String aliasName,
            Map<String, Object> mapping
    ) {

        try {
            if (indexExists(client, indexName)) {
                updateIndexMapping(client, indexName, mapping);
                addIndexToAlias(client, indexName, aliasName);

                Instant creationTimestamp = getIndexCreationTimestamp(client, indexName);
                TimeValue minAge = calculateOldIndexMinAge(creationTimestamp);
                Policy policy = buildOldIndexRolloverPolicy(indexName, minAge);

                boolean created = createOrUpdatePolicy(client, policy);
                if (created) {
                    addPolicyToIndex(client, indexName, policy.getPolicyId());
                } else {
                    tryToAddPolicyToIndex(client, indexName, policy.getPolicyId());
                }
            }
        } catch (Exception exception) {
            log.error("Failed to update and add to alias index {}.", indexName, exception);
        }
    }

    private TimeValue calculateOldIndexMinAge(Instant creationTimestamp) {
        return isNull(minIndexAge) && isNull(minRolloverAgeToDelete)
                ? null
                : TimeValue.timeValueMillis(
                        Instant.now().toEpochMilli() - creationTimestamp.toEpochMilli()
                                + Optional.ofNullable(minRolloverAgeToDelete).map(TimeValue::millis).orElse(0L)
                                + Optional.ofNullable(minIndexAge).map(TimeValue::millis).orElse(0L));
    }

    private void addPolicyToIndex(OpenSearchClient client, String indexName, String policyId) {
        log.info("Adding {} policy to index {}.", policyId, indexName);
        IndexStateManagementClient ismClient = new IndexStateManagementClient(client, jsonMapper);
        try {
            ISMStatusResponse response = ismClient.addPolicy(indexName, policyId);
            handleISMStatusResponse(response);
        } catch (Exception exception) {
            log.error("Failed to add policy to index {}.", indexName, exception);
        }
    }

    private void tryToAddPolicyToIndex(OpenSearchClient client, String indexName, String policyId) {
        log.info("Trying to add {} policy to index {}.", policyId, indexName);
        IndexStateManagementClient ismClient = new IndexStateManagementClient(client, jsonMapper);
        try {
            ismClient.addPolicy(indexName, policyId);
        } catch (Exception ignored) {}
    }

    private void handleISMStatusResponse(ISMStatusResponse response) throws Exception {
        if (response.getFailures()) {
            String message = Optional.ofNullable(response.getFailedIndices())
                    .map(failedIndices -> failedIndices.stream()
                            .map(FailedIndex::getReason)
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining(" "))
                    )
                    .orElse("Unspecified error");
            throw new Exception(message);
        }
    }

    private boolean indexExists(OpenSearchClient client, String indexName) throws IOException {
        return client.indices().exists(builder -> builder.index(indexName)).value();
    }

    private void updateIndexMapping(OpenSearchClient client, String indexName, Map<String, Object> mapping) {
        log.info("Updating index {}.", indexName);
        try {
            processHttpResponse(client.generic().execute(RequestHelper.buildPutIndexMapping(jsonMapper, indexName, mapping)));
        } catch (IOException exception) {
            log.error("Failed to update index {}.", indexName, exception);
        }
    }

    private void addIndexToAlias(OpenSearchClient client, String indexName, String aliasName) {
        log.info("Adding index {} to alias {}.", indexName, aliasName);
        try {
            Action action = new Action.Builder().add(builder -> builder.index(indexName).alias(aliasName)).build();
            UpdateAliasesRequest request = new UpdateAliasesRequest.Builder().actions(action).build();
            client.indices().updateAliases(request);
        } catch (IOException exception) {
            log.error("Failed to add index {} to alias {}.", indexName, aliasName, exception);
        }
    }

    private Map<String, Object> getIndexSettings(String prefix) {
        return Map.of("index.number_of_shards", indexShardsAmount,
                "plugins.index_state_management.rollover_alias", getAliasName(prefix));
    }

    private Map<String, Object> getIndexMapSource(Class<?> indexClass) {
        Map<String, Object> result = new HashMap<>(Map.of(
                "dynamic", false,
                "date_detection", false,
                "numeric_detection", false
        ));
        Map<String, Object> properties = getIndexMap(indexClass);
        if (!properties.isEmpty()) {
            result.put("properties", properties);
        }
        return result;
    }

    private Map<String, Object> getIndexMap(Class<?> indexClass) {
        Map<String, Object> properties = new HashMap<>();
        if (indexClass == null) {
            return properties;
        }

        properties = getIndexMap(indexClass.getSuperclass());
        Field[] fields = indexClass.getDeclaredFields();

        for (Field field : fields) {
            String fieldName = field.getName();
            OpenSearchField annotation = field.getAnnotation(OpenSearchField.class);

            Map<String, Object> attributes = new HashMap<>();
            if (annotation != null) {
                attributes.put("type", annotation.type().toString().toLowerCase(Locale.ROOT));
                switch (annotation.type()) {
                    case Date -> attributes.put("format", "date_optional_time||epoch_millis");
                    case Object -> attributes.put("properties", getIndexMap(field.getType()));
                }
            } else {
                Class<?> fieldClass = field.getType();
                if (fieldClass == String.class) {
                    attributes.put("type", OpenSearchFieldType.Text.toString().toLowerCase(Locale.ROOT));
                } else if (fieldClass == Integer.class || fieldClass == int.class) {
                    attributes.put("type", OpenSearchFieldType.Integer.toString().toLowerCase(Locale.ROOT));
                } else if (fieldClass == Long.class || fieldClass == long.class) {
                    attributes.put("type", OpenSearchFieldType.Long.toString().toLowerCase(Locale.ROOT));
                } else if (fieldClass == Double.class || fieldClass == double.class) {
                    attributes.put("type", OpenSearchFieldType.Double.toString().toLowerCase(Locale.ROOT));
                } else if (fieldClass == Float.class || fieldClass == float.class) {
                    attributes.put("type", OpenSearchFieldType.Float.toString().toLowerCase(Locale.ROOT));
                } else if (fieldClass == Boolean.class || fieldClass == boolean.class) {
                    attributes.put("type", OpenSearchFieldType.Boolean.toString().toLowerCase(Locale.ROOT));
                } else {
                    throw new IllegalArgumentException(String.format(
                            "Unsupported type %s for OpenSearch index field %s. Please annotate this field manually via @OpenSearchField",
                            fieldClass, fieldName));
                }
            }
            properties.put(fieldName, attributes);
        }
        return properties;
    }

    private boolean createOrUpdatePolicy(OpenSearchClient client, Policy policy) {
        IndexStateManagementClient ismClient = new IndexStateManagementClient(client, jsonMapper);
        try {
            Optional<PolicyResponse> responseOptional = ismClient.tryGetPolicy(policy.getPolicyId());
            if (responseOptional.isPresent()) {
                log.info("Updating policy {}.", policy.getPolicyId());
                PolicyResponse response = responseOptional.get();
                ismClient.updatePolicy(policy, response.getSeqNo(), response.getPrimaryTerm());
            } else {
                log.info("Creating policy {}.", policy.getPolicyId());
                ismClient.createPolicy(policy);
            }
            return responseOptional.isEmpty();
        } catch (IOException exception) {
            log.error("Failed to create or update index policy {}.", policy.getPolicyId(), exception);
            return false;
        }
    }

    private Policy buildOldIndexRolloverPolicy(String prefix, TimeValue minAge) {
        String policyId = getOldIndexRolloverPolicyId(prefix);
        List<Transition> transitions = new ArrayList<>();
        if (nonNull(minAge)) {
            transitions.add(Transition.builder()
                    .stateName("delete")
                    .conditions(Conditions.builder()
                            .minIndexAge(minAge)
                            .build())
                    .build());
        }
        if (StringUtils.isNotBlank(minIndexSize)) {
            transitions.add(Transition.builder()
                    .stateName("delete")
                    .conditions(Conditions.builder()
                            .minSize(minIndexSize)
                            .build())
                    .build());
        }
        return Policy.builder()
                .policyId(policyId)
                .description("QIP old index rollover policy.")
                .defaultState("schedule_to_delete")
                .states(List.of(
                        State.builder()
                                .name("schedule_to_delete")
                                .transitions(transitions)
                                .build(),
                        State.builder()
                                .name("delete")
                                .actions(Collections.singletonList(
                                        DeleteAction.builder().build()
                                ))
                                .build()
                ))
                .build();
    }

    private Policy buildRolloverPolicy(String prefix) {
        String policyId = getRolloverPolicyId(prefix);
        String mask = getIndexNameMask(prefix);
        return Policy.builder()
                .policyId(policyId)
                .description("QIP " + mask + " rollover policy.")
                .defaultState("rollover")
                .states(List.of(
                        State.builder()
                                .name("rollover")
                                .actions(Collections.singletonList(
                                        RolloverAction.builder()
                                                .minIndexAge(minIndexAge)
                                                .minSize(StringUtils.isNotBlank(minIndexSize) ? minIndexSize : null)
                                                .build()
                                ))
                                .transitions(Collections.singletonList(
                                        Transition.builder()
                                                .stateName("delete")
                                                .conditions(
                                                        isNull(minRolloverAgeToDelete)
                                                            ? null
                                                            : Conditions.builder()
                                                                    .minRolloverAge(minRolloverAgeToDelete)
                                                                    .build()
                                                )
                                                .build()
                                ))
                                .build(),
                        State.builder()
                                .name("delete")
                                .actions(Collections.singletonList(
                                        DeleteAction.builder().build()
                                ))
                                .build()
                ))
                .ismTemplate(Collections.singletonList(ISMTemplate.builder()
                        .indexPatterns(Collections.singletonList(mask)).build()))
                .build();
    }

    private Instant getIndexCreationTimestamp(OpenSearchClient client, String indexName) throws IOException {
        GetIndexRequest request = new GetIndexRequest.Builder().index(indexName).build();
        GetIndexResponse response = client.indices().get(request);
        IndexSettings indexSettings = response.result().get(indexName).settings();
        return Instant.ofEpochMilli(Long.parseLong(indexSettings.creationDate()));
    }

    private List<String> getIndexPatterns(String prefix) {
        return List.of(getOldIndexNameMask(prefix), getIndexNameMask(prefix));
    }

    private String getOldIndexRolloverPolicyId(String prefix) {
        return prefix + "-old-index-rollover-policy";
    }

    private String getRolloverPolicyId(String prefix) {
        return prefix + "-rollover-policy";
    }
    
    private String getFirstRolloverIndexName(String prefix) {
        return prefix + "-000001";
    }

    private String getIndexNameMask(String prefix) {
        return prefix + "-*";
    }

    private String getOldIndexNameMask(String prefix) {
        return prefix;
    }

    private String getOldIndexName(String prefix) {
        return prefix;
    }

    private String getIndexTemplateName(String prefix) {
        return prefix + "_template";
    }

    private String getAliasName(String prefix) {
        return prefix + "-session-elements";
    }
}
