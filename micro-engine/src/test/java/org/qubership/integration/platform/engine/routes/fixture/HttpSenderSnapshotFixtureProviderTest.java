package org.qubership.integration.platform.engine.routes.fixture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.Options;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureResponse;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class HttpSenderSnapshotFixtureProviderTest {
    private HttpSenderSnapshotFixtureProvider provider;

    @Mock
    private Function<Options, WireMockServer> serverFactory;

    @Mock
    private WireMockServer firstServer;

    @Mock
    private WireMockServer secondServer;

    @BeforeEach
    void setUp() {
        provider = new HttpSenderSnapshotFixtureProvider(serverFactory);
    }

    @Test
    void shouldStartServersOnlyWhenAnInvocationEntersTheSender() throws Exception {
        SnapshotFixtureBinding successful = binding("successful", Map.of("first", interaction("successful", 1, null)));
        SnapshotFixtureBinding rejected = binding("rejected", Map.of(
                "first", interaction("rejected", 0, 0),
                "second", interaction("rejected", 0, 1)));
        SnapshotFixtureBinding unused = binding("unused", Map.of("first", interaction("unused", 0, 0)));
        when(serverFactory.apply(any())).thenReturn(firstServer, secondServer);

        try (SnapshotFixture fixture = provider.create("test", List.of(successful, rejected, unused))) {
            fixture.start();

            verify(serverFactory, times(2)).apply(any());
            verify(firstServer).start();
            verify(secondServer).start();
        }

        verify(firstServer).stop();
        verify(secondServer).stop();
    }

    @Test
    void shouldKeepUnusedBindingsInactiveAcrossInvocations() throws Exception {
        SnapshotFixtureBinding unused = binding("unused", Map.of(
                "first", interaction("unused", 0, 0),
                "second", interaction("unused", 0, null)));

        try (SnapshotFixture fixture = provider.create("test", List.of(unused))) {
            fixture.start();
            for (String id : List.of("first", "second")) {
                SnapshotScenarioInvocation invocation = invocation(id);
                fixture.beforeInvocation(invocation);
                fixture.verifyInvocation(invocation);
            }
        }

        verifyNoInteractions(serverFactory);
    }

    @Test
    void shouldRejectAndRecordAnUnexpectedSendWhenTheBindingIsUnused() throws Exception {
        SnapshotFixtureBinding unused = binding("unused", Map.of("first", interaction("unused", 0, 0)));
        try (SnapshotFixture fixture = provider.create("test", List.of(unused));
                DefaultCamelContext context = new DefaultCamelContext()) {
            fixture.start();
            context.getRegistry().bind("unused", (HttpClientConfigurer) builder -> { });
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    errorHandler(noErrorHandler());
                    from("direct:unused").step("unused").toD("http://127.0.0.1:1/forbidden").end();
                }
            });
            fixture.configure(context);
            context.start();
            SnapshotScenarioInvocation invocation = invocation("first");
            fixture.beforeInvocation(invocation);

            try (ProducerTemplate producer = context.createProducerTemplate()) {
                Exchange result = producer.request("direct:unused", exchange -> exchange.getMessage()
                        .setHeader(Exchange.HTTP_URI, "http://127.0.0.1:1/forbidden"));

                IllegalStateException failure = assertInstanceOf(IllegalStateException.class, result.getException());
                assertEquals("HTTP sender fixture 'unused' was invoked although the scenario expects no sends.", failure.getMessage());
                AssertionError assertion = assertThrows(AssertionError.class, () -> fixture.verifyInvocation(invocation));
                assertTrue(assertion.getMessage().contains("entered the sender an unexpected number of times"));
            }
        }

        verifyNoInteractions(serverFactory);
    }

    private static SnapshotFixtureBinding binding(String id, Map<String, SnapshotFixtureInteraction> interactions) {
        SnapshotFixtureDefinition definition = new SnapshotFixtureDefinition(id, "http-sender-http", "test", id, null);
        return new SnapshotFixtureBinding(definition, interactions);
    }

    private static SnapshotFixtureInteraction interaction(String id, int requestCount, Integer sendCount) {
        SnapshotFixtureResponse response = new SnapshotFixtureResponse(200, null, "response", Map.of(),
                sendCount == null ? Map.of() : Map.of("expectedSendCount", sendCount));
        SnapshotFixtureRequestExpectation expected = new SnapshotFixtureRequestExpectation(requestCount, null, null,
                null, null, null, null, Map.of(), Map.of());
        return new SnapshotFixtureInteraction(id, response, expected, null, null, null, null, null);
    }

    private static SnapshotScenarioInvocation invocation(String id) {
        return ObjectMappers.getObjectMapper().convertValue(Map.of("id", id), SnapshotScenarioInvocation.class);
    }
}
