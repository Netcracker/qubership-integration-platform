package org.qubership.integration.platform.engine.consul.updates;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.BlockingQueryOptions;
import io.vertx.ext.consul.KeyValue;
import io.vertx.ext.consul.KeyValueList;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.consul.updates.parsers.ChainRuntimePropertiesUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.CommonVariablesUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.DeploymentUpdateParser;
import org.qubership.integration.platform.engine.consul.updates.parsers.LibrariesUpdateParser;
import org.qubership.integration.platform.engine.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.engine.model.kafka.systemmodel.CompiledLibraryUpdate;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class UpdateGetterProducerTest {

    private UpdateGetterProducer producer;

    @Mock
    ConsulClient consulClient;
    @Mock
    DeploymentUpdateParser deploymentUpdateParser;
    @Mock
    LibrariesUpdateParser librariesUpdateParser;
    @Mock
    ChainRuntimePropertiesUpdateParser chainRuntimePropertiesUpdateParser;
    @Mock
    CommonVariablesUpdateParser commonVariablesUpdateParser;

    @BeforeEach
    void setUp() {
        producer = new UpdateGetterProducer();
        producer.keyPrefix = "config/test";
        producer.keyEngineConfigRoot = "/qip-engine-configurations";
        producer.keyDeploymentsUpdate = "/deployments-update";
        producer.keyLibrariesUpdate = "/libraries-update";
        producer.keyRuntimeConfigurations = "/runtime-configurations";
        producer.keyChains = "/chains";
        producer.keyCommonVariablesV2 = "/variables/common";
    }

    @Test
    void shouldCreateDeploymentUpdateGetterWithConfiguredKeyAndParser() {
        UpdateGetterHelper<Long> getter =
                producer.deploymentUpdateGetter(consulClient, deploymentUpdateParser);

        List<KeyValue> entries = List.of(mock(KeyValue.class));
        KeyValueList kvList = changedKvList(entries);

        when(consulClient.getValuesWithOptions(
                eq("config/test/qip-engine-configurations/deployments-update"),
                any(BlockingQueryOptions.class)
        )).thenReturn(Uni.createFrom().item(kvList));
        when(deploymentUpdateParser.apply(entries)).thenReturn(42L);

        AtomicReference<Long> result = new AtomicReference<>();

        getter.checkForUpdates(result::set);

        assertEquals(42L, result.get());
    }

    @Test
    void shouldCreateLibrariesUpdateGetterWithConfiguredKeyAndParser() {
        UpdateGetterHelper<List<CompiledLibraryUpdate>> getter =
                producer.librariesUpdateGetter(consulClient, librariesUpdateParser);

        List<KeyValue> entries = List.of(mock(KeyValue.class));
        KeyValueList kvList = changedKvList(entries);
        List<CompiledLibraryUpdate> updates = List.of(
                mock(CompiledLibraryUpdate.class),
                mock(CompiledLibraryUpdate.class)
        );

        when(consulClient.getValuesWithOptions(
                eq("config/test/qip-engine-configurations/libraries-update"),
                any(BlockingQueryOptions.class)
        )).thenReturn(Uni.createFrom().item(kvList));
        when(librariesUpdateParser.apply(entries)).thenReturn(updates);

        AtomicReference<List<CompiledLibraryUpdate>> result = new AtomicReference<>();

        getter.checkForUpdates(result::set);

        assertSame(updates, result.get());
    }

    @Test
    void shouldCreateChainRuntimePropertiesUpdateGetterWithConfiguredKeyAndParser() {
        UpdateGetterHelper<Map<String, DeploymentRuntimeProperties>> getter =
                producer.chainRuntimePropertiesUpdateGetter(consulClient, chainRuntimePropertiesUpdateParser);

        List<KeyValue> entries = List.of(mock(KeyValue.class));
        KeyValueList kvList = changedKvList(entries);
        Map<String, DeploymentRuntimeProperties> properties = Map.of(
                "chain-1",
                DeploymentRuntimeProperties.builder()
                        .maskingEnabled(true)
                        .build()
        );

        when(consulClient.getValuesWithOptions(
                eq("config/test/qip-engine-configurations/runtime-configurations/chains"),
                any(BlockingQueryOptions.class)
        )).thenReturn(Uni.createFrom().item(kvList));
        when(chainRuntimePropertiesUpdateParser.apply(entries)).thenReturn(properties);

        AtomicReference<Map<String, DeploymentRuntimeProperties>> result = new AtomicReference<>();

        getter.checkForUpdates(result::set);

        assertSame(properties, result.get());
    }

    @Test
    void shouldCreateCommonVariablesUpdateGetterWithConfiguredKeyAndParser() {
        UpdateGetterHelper<Map<String, String>> getter =
                producer.commonVariablesUpdateGetter(consulClient, commonVariablesUpdateParser);

        List<KeyValue> entries = List.of(mock(KeyValue.class));
        KeyValueList kvList = changedKvList(entries);
        Map<String, String> variables = Map.of(
                "customerId", "123",
                "orderId", "456"
        );

        when(consulClient.getValuesWithOptions(
                eq("config/test/qip-engine-configurations/variables/common"),
                any(BlockingQueryOptions.class)
        )).thenReturn(Uni.createFrom().item(kvList));
        when(commonVariablesUpdateParser.apply(entries)).thenReturn(variables);

        AtomicReference<Map<String, String>> result = new AtomicReference<>();

        getter.checkForUpdates(result::set);

        assertSame(variables, result.get());
    }

    private static KeyValueList changedKvList(List<KeyValue> entries) {
        KeyValueList kvList = mock(KeyValueList.class);
        when(kvList.isPresent()).thenReturn(true);
        when(kvList.getIndex()).thenReturn(1L);
        when(kvList.getList()).thenReturn(entries);
        return kvList;
    }
}
