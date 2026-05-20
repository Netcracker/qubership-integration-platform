package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.qubership.integration.platform.engine.opensearch.ism.IndexStateManagementClient;
import org.qubership.integration.platform.engine.opensearch.ism.model.FailedIndex;
import org.qubership.integration.platform.engine.opensearch.ism.model.Policy;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.opensearch.ism.rest.PolicyResponse;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerPolicyOperationsTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchClient client;

    @Test
    void shouldUpdatePolicyWhenExistingPolicyIsFound() throws Exception {
        Policy policy = Policy.builder()
                .policyId("policy-1")
                .build();

        PolicyResponse existingPolicy = PolicyResponse.builder()
                .seqNo(11L)
                .primaryTerm(17L)
                .build();

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.tryGetPolicy("policy-1")).thenReturn(Optional.of(existingPolicy))
        )) {
            boolean result = OpenSearchTestUtils.invoke(
                    initializer,
                    "createOrUpdatePolicy",
                    new Class<?>[]{OpenSearchClient.class, Policy.class},
                    client,
                    policy
            );

            assertFalse(result);

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).tryGetPolicy("policy-1");
            verify(ismClient).updatePolicy(policy, 11L, 17L);
            verify(ismClient, never()).createPolicy(any());
        }
    }

    @Test
    void shouldCreatePolicyWhenExistingPolicyIsNotFound() throws Exception {
        Policy policy = Policy.builder()
                .policyId("policy-1")
                .build();

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.tryGetPolicy("policy-1")).thenReturn(Optional.empty())
        )) {
            boolean result = OpenSearchTestUtils.invoke(
                    initializer,
                    "createOrUpdatePolicy",
                    new Class<?>[]{OpenSearchClient.class, Policy.class},
                    client,
                    policy
            );

            assertTrue(result);

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).tryGetPolicy("policy-1");
            verify(ismClient).createPolicy(policy);
            verify(ismClient, never()).updatePolicy(any(), anyLong(), anyLong());
        }
    }

    @Test
    void shouldReturnFalseWhenCreateOrUpdatePolicyFailsWithIOException() throws Exception {
        Policy policy = Policy.builder()
                .policyId("policy-1")
                .build();

        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.tryGetPolicy("policy-1")).thenThrow(new IOException("boom"))
        )) {
            boolean result = OpenSearchTestUtils.invoke(
                    initializer,
                    "createOrUpdatePolicy",
                    new Class<?>[]{OpenSearchClient.class, Policy.class},
                    client,
                    policy
            );

            assertFalse(result);

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).tryGetPolicy("policy-1");
            verify(ismClient, never()).createPolicy(any());
            verify(ismClient, never()).updatePolicy(any(), anyLong(), anyLong());
        }
    }

    @Test
    void shouldAddPolicyToIndexWhenIsmStatusHasNoFailures() throws Exception {
        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.addPolicy("index-1", "policy-1"))
                        .thenReturn(ISMStatusResponse.builder()
                                .failures(false)
                                .build())
        )) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "addPolicyToIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                    client,
                    "index-1",
                    "policy-1"
            );

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).addPolicy("index-1", "policy-1");
        }
    }

    @Test
    void shouldNotThrowWhenAddPolicyToIndexReturnsFailures() throws Exception {
        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.addPolicy("index-1", "policy-1"))
                        .thenReturn(ISMStatusResponse.builder()
                                .failures(true)
                                .failedIndices(List.of(
                                        FailedIndex.builder().reason("failure reason").build()
                                ))
                                .build())
        )) {
            assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "addPolicyToIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                    client,
                    "index-1",
                    "policy-1"
            ));

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).addPolicy("index-1", "policy-1");
        }
    }

    @Test
    void shouldNotThrowWhenAddPolicyToIndexFailsWithException() throws Exception {
        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.addPolicy("index-1", "policy-1"))
                        .thenThrow(new IOException("boom"))
        )) {
            assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "addPolicyToIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                    client,
                    "index-1",
                    "policy-1"
            ));

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).addPolicy("index-1", "policy-1");
        }
    }

    @Test
    void shouldTryToAddPolicyToIndex() throws Exception {
        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(IndexStateManagementClient.class)) {
            OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "tryToAddPolicyToIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                    client,
                    "index-1",
                    "policy-1"
            );

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).addPolicy("index-1", "policy-1");
        }
    }

    @Test
    void shouldIgnoreExceptionWhenTryToAddPolicyToIndexFails() throws Exception {
        try (MockedConstruction<IndexStateManagementClient> mocked = mockConstruction(
                IndexStateManagementClient.class,
                (mock, context) -> when(mock.addPolicy("index-1", "policy-1"))
                        .thenThrow(new IOException("boom"))
        )) {
            assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                    initializer,
                    "tryToAddPolicyToIndex",
                    new Class<?>[]{OpenSearchClient.class, String.class, String.class},
                    client,
                    "index-1",
                    "policy-1"
            ));

            IndexStateManagementClient ismClient = mocked.constructed().getFirst();
            verify(ismClient).addPolicy("index-1", "policy-1");
        }
    }
}
