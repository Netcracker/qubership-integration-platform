package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.generic.OpenSearchGenericClient;
import org.opensearch.client.opensearch.generic.Request;
import org.opensearch.client.opensearch.generic.Response;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.GetIndexRequest;
import org.opensearch.client.opensearch.indices.GetIndexResponse;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.IndexState;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.opensearch.client.opensearch.indices.UpdateAliasesRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.opensearch.ism.model.FailedIndex;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.PolicyResponse;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerOldIndexTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchProperties properties;

    @Mock
    private OpenSearchProperties.RolloverProperties rolloverProperties;

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

    @BeforeEach
    void setUp() {
        initializer.jsonMapper = ObjectMappers.getObjectMapper();
        initializer.properties = properties;
    }

    @Test
    void shouldReturnNullWhenOldIndexAgePropertiesAreNotConfigured() throws Exception {
        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(null);
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(null);

        TimeValue result = OpenSearchTestUtils.invoke(
                initializer,
                "calculateOldIndexMinAge",
                new Class<?>[]{Instant.class},
                Instant.ofEpochMilli(1_000L)
        );

        assertNull(result);
    }

    @Test
    void shouldCalculateOldIndexMinAgeFromCreationTimestampAndConfiguredThresholds() throws Exception {
        TimeValue minIndexAge = TimeValue.timeValueDays(1);
        TimeValue minDeleteAge = TimeValue.timeValueDays(14);
        Instant creationTimestamp = Instant.ofEpochMilli(1_000L);
        Instant before = Instant.now();

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(minIndexAge);
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(minDeleteAge);

        TimeValue result = OpenSearchTestUtils.invoke(
                initializer,
                "calculateOldIndexMinAge",
                new Class<?>[]{Instant.class},
                creationTimestamp
        );

        Instant after = Instant.now();

        long lowerBound = before.toEpochMilli() - creationTimestamp.toEpochMilli()
                + minIndexAge.millis()
                + minDeleteAge.millis();
        long upperBound = after.toEpochMilli() - creationTimestamp.toEpochMilli()
                + minIndexAge.millis()
                + minDeleteAge.millis();

        assertTrue(result.millis() >= lowerBound);
        assertTrue(result.millis() <= upperBound);
    }

    @Test
    void shouldNotThrowWhenIsmStatusResponseHasNoFailures() {
        ISMStatusResponse ismStatusResponse = ISMStatusResponse.builder()
                .failures(false)
                .build();

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "handleISMStatusResponse",
                new Class<?>[]{ISMStatusResponse.class},
                ismStatusResponse
        ));
    }

    @Test
    void shouldThrowExceptionWithConcatenatedReasonsWhenIsmStatusResponseHasFailures() {
        ISMStatusResponse ismStatusResponse = ISMStatusResponse.builder()
                .failures(true)
                .failedIndices(List.of(
                        FailedIndex.builder().reason("first reason").build(),
                        FailedIndex.builder().reason(null).build(),
                        FailedIndex.builder().reason("second reason").build()
                ))
                .build();

        Exception exception = assertThrows(
                Exception.class,
                () -> OpenSearchTestUtils.invokeVoid(
                        initializer,
                        "handleISMStatusResponse",
                        new Class<?>[]{ISMStatusResponse.class},
                        ismStatusResponse
                )
        );

        assertEquals("first reason second reason", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWithUnspecifiedErrorWhenIsmStatusResponseHasFailuresWithoutFailedIndices() {
        ISMStatusResponse ismStatusResponse = ISMStatusResponse.builder()
                .failures(true)
                .failedIndices(null)
                .build();

        Exception exception = assertThrows(
                Exception.class,
                () -> OpenSearchTestUtils.invokeVoid(
                        initializer,
                        "handleISMStatusResponse",
                        new Class<?>[]{ISMStatusResponse.class},
                        ismStatusResponse
                )
        );

        assertEquals("Unspecified error", exception.getMessage());
    }

    @Test
    void shouldDoNothingWhenUpdatingOldIndexAndIndexDoesNotExist() throws Exception {
        when(client.indices()).thenReturn(indicesClient);
        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(false);

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(IndexStateManagementClient.class)) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "updateOldIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class, Map.class},
                    client,
                    "sessions",
                    "sessions-session-elements",
                    OpenSearchTestUtils.getMapping()
            );

            verify(indicesClient).exists(
                    org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
            );
            verify(indicesClient, never()).updateAliases(any(UpdateAliasesRequest.class));
            verify(indicesClient, never()).get(any(GetIndexRequest.class));
            assertTrue(mocked.constructed().isEmpty());
        }
    }

    @Test
    void shouldCreatePolicyAndAddPolicyWhenUpdatingOldIndexAndPolicyDoesNotExist() throws Exception {
        stubOldIndexRolloverDefaults();

        when(client.indices()).thenReturn(indicesClient);
        when(client.generic()).thenReturn(genericClient);

        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(true);

        GetIndexResponse getIndexResponse = org.mockito.Mockito.mock(GetIndexResponse.class);
        when(indicesClient.get(any(GetIndexRequest.class))).thenReturn(getIndexResponse);
        when(getIndexResponse.result()).thenReturn(Map.of(
                "sessions",
                IndexState.of(builder -> builder.settings(
                        IndexSettings.of(settings -> settings.creationDate("1710000000000"))
                ))
        ));

        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> {
                    when(mock.tryGetPolicy("sessions-old-index-rollover-policy")).thenReturn(Optional.empty());
                    when(mock.addPolicy("sessions", "sessions-old-index-rollover-policy"))
                            .thenReturn(ISMStatusResponse.builder().failures(false).build());
                }
        )) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "updateOldIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class, Map.class},
                    client,
                    "sessions",
                    "sessions-session-elements",
                    OpenSearchTestUtils.getMapping()
            );

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(genericClient).execute(requestCaptor.capture());

            Request request = requestCaptor.getValue();
            assertEquals("PUT", request.getMethod());
            assertEquals("/sessions/_mapping", request.getEndpoint());

            verify(indicesClient).updateAliases(any(UpdateAliasesRequest.class));
            verify(indicesClient, atLeastOnce()).get(any(GetIndexRequest.class));

            assertEquals(2, mocked.constructed().size());

            IndexStateManagementClient createPolicyClient = mocked.constructed().getFirst();
            verify(createPolicyClient).tryGetPolicy("sessions-old-index-rollover-policy");
            verify(createPolicyClient).createPolicy(argThat(policy ->
                    "sessions-old-index-rollover-policy".equals(policy.getPolicyId())
            ));

            IndexStateManagementClient addPolicyClient = mocked.constructed().get(1);
            verify(addPolicyClient).addPolicy("sessions", "sessions-old-index-rollover-policy");
        }
    }

    @Test
    void shouldUpdatePolicyAndTryToAddPolicyWhenUpdatingOldIndexAndPolicyExists() throws Exception {
        stubOldIndexRolloverDefaults();

        when(client.indices()).thenReturn(indicesClient);
        when(client.generic()).thenReturn(genericClient);

        doReturn(booleanResponse).when(indicesClient).exists(
                org.mockito.ArgumentMatchers.<Function<ExistsRequest.Builder, ObjectBuilder<ExistsRequest>>>any()
        );
        when(booleanResponse.value()).thenReturn(true);

        GetIndexResponse getIndexResponse = org.mockito.Mockito.mock(GetIndexResponse.class);
        when(indicesClient.get(any(GetIndexRequest.class))).thenReturn(getIndexResponse);
        when(getIndexResponse.result()).thenReturn(Map.of(
                "sessions",
                IndexState.of(builder -> builder.settings(
                        IndexSettings.of(settings -> settings.creationDate("1710000000000"))
                ))
        ));

        when(genericClient.execute(any(Request.class))).thenReturn(response);
        when(response.getStatus()).thenReturn(200);

        PolicyResponse existingPolicy = PolicyResponse.builder()
                .seqNo(7L)
                .primaryTerm(9L)
                .build();

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> {
                    when(mock.tryGetPolicy("sessions-old-index-rollover-policy")).thenReturn(Optional.of(existingPolicy));
                    when(mock.addPolicy("sessions", "sessions-old-index-rollover-policy"))
                            .thenReturn(ISMStatusResponse.builder().failures(false).build());
                }
        )) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "updateOldIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class, Map.class},
                    client,
                    "sessions",
                    "sessions-session-elements",
                    OpenSearchTestUtils.getMapping()
            );

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(genericClient).execute(requestCaptor.capture());

            Request request = requestCaptor.getValue();
            assertEquals("PUT", request.getMethod());
            assertEquals("/sessions/_mapping", request.getEndpoint());

            verify(indicesClient).updateAliases(any(UpdateAliasesRequest.class));
            verify(indicesClient, atLeastOnce()).get(any(GetIndexRequest.class));

            assertEquals(2, mocked.constructed().size());

            IndexStateManagementClient updatePolicyClient = mocked.constructed().getFirst();
            verify(updatePolicyClient).tryGetPolicy("sessions-old-index-rollover-policy");
            verify(updatePolicyClient).updatePolicy(
                    argThat((Policy policy) -> "sessions-old-index-rollover-policy".equals(policy.getPolicyId())),
                    eq(7L),
                    eq(9L)
            );
            verify(updatePolicyClient, never()).createPolicy(any());

            IndexStateManagementClient addPolicyClient = mocked.constructed().get(1);
            verify(addPolicyClient).addPolicy("sessions", "sessions-old-index-rollover-policy");
        }
    }

    private void stubOldIndexRolloverDefaults() {
        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(null);
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(null);
        when(rolloverProperties.minIndexSize()).thenReturn(Optional.empty());
    }
}
