package org.qubership.integration.platform.engine.consul;

import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ConsulRestClientProducerTest {

    private ConsulRestClientProducer producer;

    @Mock
    Vertx vertx;
    @Mock
    ConsulClient consulClient;

    @BeforeEach
    void setUp() {
        producer = new ConsulRestClientProducer();
    }

    @Test
    void shouldCreateConsulClientWithConfiguredUriAndToken() {
        producer.uri = URI.create("https://consul.example.com:8501");
        producer.token = "acl-token";
        producer.connectTimeout = 300000;

        AtomicReference<Vertx> capturedVertx = new AtomicReference<>();
        AtomicReference<ConsulClientOptions> capturedOptions = new AtomicReference<>();

        try (MockedStatic<ConsulClient> consulClientMock = mockStatic(ConsulClient.class)) {
            consulClientMock.when(() -> ConsulClient.create(
                            eq(vertx),
                            any(ConsulClientOptions.class)
                    ))
                    .thenAnswer(invocation -> {
                        capturedVertx.set(invocation.getArgument(0));
                        capturedOptions.set(invocation.getArgument(1));
                        return consulClient;
                    });

            ConsulClient result = producer.consulClient(vertx);

            assertSame(consulClient, result);
            assertSame(vertx, capturedVertx.get());
            assertEquals("consul.example.com", capturedOptions.get().getHost());
            assertEquals(8501, capturedOptions.get().getPort());
            assertFalse(capturedOptions.get().isSsl());
            assertEquals("acl-token", capturedOptions.get().getAclToken());
        }
    }

    @Test
    void shouldCreateConsulClientWithoutSslWhenHttpUriProvided() {
        producer.uri = URI.create("http://consul.example.com:8500");
        producer.token = "acl-token";
        producer.connectTimeout = 300000;

        AtomicReference<ConsulClientOptions> capturedOptions = new AtomicReference<>();

        try (MockedStatic<ConsulClient> consulClientMock = mockStatic(ConsulClient.class)) {
            consulClientMock.when(() -> ConsulClient.create(
                            eq(vertx),
                            any(ConsulClientOptions.class)
                    ))
                    .thenAnswer(invocation -> {
                        capturedOptions.set(invocation.getArgument(1));
                        return consulClient;
                    });

            ConsulClient result = producer.consulClient(vertx);

            assertSame(consulClient, result);
            assertEquals("consul.example.com", capturedOptions.get().getHost());
            assertEquals(8500, capturedOptions.get().getPort());
            assertFalse(capturedOptions.get().isSsl());
            assertEquals("acl-token", capturedOptions.get().getAclToken());
        }
    }
}
