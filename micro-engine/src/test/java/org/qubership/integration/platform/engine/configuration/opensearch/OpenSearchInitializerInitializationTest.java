package org.qubership.integration.platform.engine.configuration.opensearch;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.opensearch.annotation.OpenSearchDocument;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;
import org.reflections.Reflections;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerInitializationTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchProperties properties;

    @Mock
    private OpenSearchProperties.IndexProperties indexProperties;

    @Mock
    private OpenSearchProperties.ElementsProperties elementsProperties;

    @Mock
    private OpenSearchProperties.RolloverProperties rolloverProperties;

    @Mock
    private OpenSearchProperties.KafkaClientProperties kafkaClientProperties;

    @Mock
    private OpenSearchClient client;

    @Mock
    private OpenSearchGenericClient genericClient;

    @Mock
    private OpenSearchIndicesClient indicesClient;

    @Mock
    private Response response;

    @Mock
    private BooleanResponse booleanResponse;

    @Mock
    private OpenSearchClientSupplier clientSupplier;

    @BeforeEach
    void setUp() {
        initializer.jsonMapper = ObjectMappers.getObjectMapper();
        initializer.properties = properties;
    }

    @Test
    void shouldSkipInitializationWhenKafkaClientIsEnabled() {
        when(properties.kafkaClient()).thenReturn(kafkaClientProperties);
        when(kafkaClientProperties.enabled()).thenReturn(true);

        initializer.initialize(clientSupplier);

        verifyNoInteractions(clientSupplier);
    }

    @Test
    void shouldInitializeAndUpdateTemplateAndIndexesWhenKafkaClientIsDisabled() throws Exception {
        when(properties.kafkaClient()).thenReturn(kafkaClientProperties);
        when(kafkaClientProperties.enabled()).thenReturn(false);

        stubIndexAndRolloverProperties();

        when(clientSupplier.getClient()).thenReturn(client);
        when(clientSupplier.normalize("sessions")).thenReturn("normalized-sessions");

        when(client.generic()).thenReturn(genericClient);
        when(client.indices()).thenReturn(indicesClient);

        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        doThrow(new IOException("boom")).when(indicesClient).get((GetIndexRequest) any());
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(false);

        Config config = org.mockito.Mockito.mock(Config.class);

        try (MockedStatic<ConfigProvider> configProviderMock = mockStatic(ConfigProvider.class);
             MockedConstruction<Reflections> reflectionsMock = mockConstruction(
                     Reflections.class,
                     (mock, context) -> when(mock.getTypesAnnotatedWith(OpenSearchDocument.class))
                             .thenReturn(Set.of(OpenSearchTestUtils.InitSuccessDocument.class))
             );
             MockedConstruction<IndexStateManagementClient> ismMock = mockConstruction(
                     IndexStateManagementClient.class,
                     (mock, context) -> when(mock.tryGetPolicy("normalized-sessions-rollover-policy"))
                             .thenReturn(Optional.empty())
             )) {

            configProviderMock.when(ConfigProvider::getConfig).thenReturn(config);
            when(config.getValue("test.opensearch.init.success", String.class)).thenReturn("sessions");

            initializer.initialize(clientSupplier);

            verify(clientSupplier).getClient();
            verify(clientSupplier).normalize("sessions");

            assertEquals(1, reflectionsMock.constructed().size());
            assertEquals(1, ismMock.constructed().size());

            IndexStateManagementClient ismClient = ismMock.constructed().getFirst();
            verify(ismClient).tryGetPolicy("normalized-sessions-rollover-policy");
            verify(ismClient).createPolicy(argThat(policy ->
                    "normalized-sessions-rollover-policy".equals(policy.getPolicyId())
            ));

            verify(genericClient).execute(argThat(request ->
                    "PUT".equals(request.getMethod())
                            && "/_index_template/normalized-sessions_template".equals(request.getEndpoint())
            ));

            verify(indicesClient).get((GetIndexRequest) any());
            verify(indicesClient).exists(
                    org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
            );
        }
    }

    @Test
    void shouldSkipIndexInitializationWhenConfiguredDocumentNameIsBlank() throws Exception {
        when(clientSupplier.getClient()).thenReturn(client);

        Config config = org.mockito.Mockito.mock(Config.class);

        try (MockedStatic<ConfigProvider> configProviderMock = mockStatic(ConfigProvider.class);
             MockedConstruction<Reflections> reflectionsMock = mockConstruction(
                     Reflections.class,
                     (mock, context) -> when(mock.getTypesAnnotatedWith(OpenSearchDocument.class))
                             .thenReturn(Set.of(OpenSearchTestUtils.BlankNameDocument.class))
             )) {

            configProviderMock.when(ConfigProvider::getConfig).thenReturn(config);
            when(config.getValue("test.opensearch.blank.name", String.class)).thenReturn("   ");

            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "updateTemplateAndIndexes",
                    new Class<?>[]{OpenSearchClientSupplier.class},
                    clientSupplier
            );

            verify(clientSupplier).getClient();
            verify(clientSupplier, never()).normalize(anyString());
            verifyNoInteractions(genericClient);
            verifyNoInteractions(indicesClient);

            assertEquals(1, reflectionsMock.constructed().size());
        }
    }

    @Test
    void shouldContinueProcessingOtherDocumentsWhenOneDocumentFailsDuringMappingBuild() throws Exception {
        stubIndexAndRolloverProperties();

        when(clientSupplier.getClient()).thenReturn(client);
        when(clientSupplier.normalize("sessions")).thenReturn("sessions");

        when(client.generic()).thenReturn(genericClient);
        when(client.indices()).thenReturn(indicesClient);

        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        doThrow(new IOException("boom")).when(indicesClient).get((GetIndexRequest) any());
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(false);

        Config config = org.mockito.Mockito.mock(Config.class);

        LinkedHashSet<Class<?>> indexClasses = new LinkedHashSet<>();
        indexClasses.add(OpenSearchTestUtils.UnsupportedInitDocument.class);
        indexClasses.add(OpenSearchTestUtils.InitSuccessDocument.class);

        try (MockedStatic<ConfigProvider> configProviderMock = mockStatic(ConfigProvider.class);
             MockedConstruction<Reflections> reflectionsMock = mockConstruction(
                     Reflections.class,
                     (mock, context) -> when(mock.getTypesAnnotatedWith(OpenSearchDocument.class))
                             .thenReturn(indexClasses)
             );
             MockedConstruction<IndexStateManagementClient> ismMock = mockConstruction(
                     IndexStateManagementClient.class,
                     (mock, context) -> when(mock.tryGetPolicy("sessions-rollover-policy"))
                             .thenReturn(Optional.empty())
             )) {

            configProviderMock.when(ConfigProvider::getConfig).thenReturn(config);
            when(config.getValue("test.opensearch.unsupported.name", String.class)).thenReturn("broken");
            when(config.getValue("test.opensearch.init.success", String.class)).thenReturn("sessions");

            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "updateTemplateAndIndexes",
                    new Class<?>[]{OpenSearchClientSupplier.class},
                    clientSupplier
            );

            verify(clientSupplier).getClient();
            verify(clientSupplier).normalize("sessions");
            verify(clientSupplier, never()).normalize("broken");

            assertEquals(1, reflectionsMock.constructed().size());
            assertEquals(1, ismMock.constructed().size());

            IndexStateManagementClient ismClient = ismMock.constructed().getFirst();
            verify(ismClient).createPolicy(argThat(policy ->
                    "sessions-rollover-policy".equals(policy.getPolicyId())
            ));

            verify(genericClient).execute(argThat(request ->
                    "/_index_template/sessions_template".equals(request.getEndpoint())
            ));
        }
    }

    private void stubIndexAndRolloverProperties() {
        when(properties.index()).thenReturn(indexProperties);
        when(indexProperties.elements()).thenReturn(elementsProperties);
        when(elementsProperties.shards()).thenReturn(3);

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(null);
        when(rolloverProperties.minIndexSize()).thenReturn(Optional.empty());
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(null);
    }
}
