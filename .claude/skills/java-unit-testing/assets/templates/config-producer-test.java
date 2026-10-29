package org.qubership.integration.platform.engine.configuration.opensearch;

import com.netcracker.cloud.dbaas.client.opensearch.DbaasOpensearchClient;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchClientSupplierProducerTest {

    private final OpenSearchClientSupplierProducer producer = new OpenSearchClientSupplierProducer();

    @Test
    void shouldCreateSupplierDelegateCallsToTenantClientAndInitializeIt() {
        DbaasOpensearchClient tenantClient = mock(DbaasOpensearchClient.class);
        OpenSearchInitializer openSearchInitializer = mock(OpenSearchInitializer.class);
        OpenSearchClient openSearchClient = mock(OpenSearchClient.class);

        when(tenantClient.getClient()).thenReturn(openSearchClient);
        when(tenantClient.normalize("index-name")).thenReturn("normalized-index-name");

        OpenSearchClientSupplier result = producer.dbaasOpenSearchClientSupplier(
                tenantClient,
                openSearchInitializer
        );

        assertSame(openSearchClient, result.getClient());
        assertEquals("normalized-index-name", result.normalize("index-name"));

        ArgumentCaptor<OpenSearchClientSupplier> captor =
                ArgumentCaptor.forClass(OpenSearchClientSupplier.class);
        verify(openSearchInitializer).initialize(captor.capture());

        assertSame(result, captor.getValue());
    }
}
