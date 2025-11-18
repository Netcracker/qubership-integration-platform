package org.qubership.integration.platform.engine.camel;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.observation.MicrometerObservationTracer;
import org.apache.camel.spi.CamelContextCustomizer;
import org.apache.camel.support.DefaultRegistry;
import org.qubership.integration.platform.engine.camel.repository.PerDeploymentBeanRepository;

@Slf4j
@ApplicationScoped
@Unremovable
public class ContextCustomizer implements CamelContextCustomizer {
    @Inject
    @Named("camelObservationTracer")
    MicrometerObservationTracer tracer;

    @Inject
    PerDeploymentBeanRepository perDeploymentBeanRepository;

    @Override
    public void configure(CamelContext camelContext) {
        // Forcing initialization of tracer to prevent its lazy
        // initialization in the middle of integration chain deployment process.
        tracer.init(camelContext);

        // Adding per deployment bean repository to camel bean registry
        DefaultRegistry registry = (DefaultRegistry) camelContext.getRegistry();
        registry.addBeanRepository(perDeploymentBeanRepository);
    }
}
