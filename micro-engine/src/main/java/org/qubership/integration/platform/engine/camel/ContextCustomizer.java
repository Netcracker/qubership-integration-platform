package org.qubership.integration.platform.engine.camel;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.spi.CamelContextCustomizer;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;

@Slf4j
@ApplicationScoped
@Unremovable
public class ContextCustomizer implements CamelContextCustomizer {
    @Inject
    @Named("camelObservationTracer")
    MicrometerObservationTracer tracer;

    @Inject
    CamelDebugger debugger;

    @Override
    public void configure(CamelContext camelContext) {
        // Forcing initialization of tracer to prevent its lazy
        // initialization in the middle of integration chain deployment process.
        tracer.init(camelContext);

        // Setting up debugger
        camelContext.setDebugger(debugger);
        camelContext.setDebugging(true);
    }
}
