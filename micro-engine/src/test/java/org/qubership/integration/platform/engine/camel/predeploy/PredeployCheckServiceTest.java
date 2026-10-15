package org.qubership.integration.platform.engine.camel.predeploy;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PredeployCheckServiceTest {

    private final CamelContext context = new DefaultCamelContext();

    @AfterEach
    void tearDown() throws Exception {
        context.close();
    }

    @Test
    void shouldCheckEveryStaticEndpointOfMatchingSchemeBeforeRoutesStart() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("mock:in")
                        .doTry()
                            .to("mock:out")
                            .toD("mock:dynamic-static")
                            .toD("mock:${header.target}")
                            .to("direct:other")
                        .doCatch(Exception.class)
                            .choice()
                                .when(constant(true)).to("mock:nested")
                            .end()
                        .end();
            }
        });
        RecordingCheck check = new RecordingCheck("mock");

        new PredeployCheckService(List.of(check)).checkRoutes(context);

        assertEquals(
                List.of("mock://in consumer", "mock://out producer", "mock://nested producer",
                        "mock://dynamic-static producer"),
                check.checked);
    }

    private static class RecordingCheck implements PredeployCheck {
        private final String scheme;
        private final List<String> checked = new ArrayList<>();

        RecordingCheck(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public boolean applicableTo(String scheme) {
            return this.scheme.equals(scheme);
        }

        @Override
        public void check(Endpoint endpoint, boolean consumer) {
            checked.add(endpoint.getEndpointUri() + (consumer ? " consumer" : " producer"));
        }
    }
}
