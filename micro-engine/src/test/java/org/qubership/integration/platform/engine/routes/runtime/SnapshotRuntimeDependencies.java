package org.qubership.integration.platform.engine.routes.runtime;

import com.netcracker.cloud.bluegreen.api.service.BlueGreenStatePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.quarkus.core.RuntimeRegistry;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors.MaasParametersResolver;
import org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors.MaasParametersResolverPreprocessor;
import org.qubership.integration.platform.engine.consul.updates.UpdateGetterHelper;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.engine.DomainType;
import org.qubership.integration.platform.engine.model.engine.EngineInfo;

import java.util.Map;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Singleton
public class SnapshotRuntimeDependencies {
    @Produces
    @Singleton
    public DefaultCamelContext camelContext() {
        return new DefaultCamelContext(new RuntimeRegistry(Map.of()));
    }

    public void closeCamelContext(@Disposes DefaultCamelContext context) throws Exception {
        context.close();
    }

    @Produces
    @Singleton
    public EngineInfo engineInfo() {
        return EngineInfo.builder()
                .domain("snapshot-tests")
                .domainType(DomainType.MICRO)
                .engineDeploymentName("snapshot-tests")
                .host("127.0.0.1")
                .build();
    }

    @Produces
    @Singleton
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    public void closeMeterRegistry(@Disposes MeterRegistry registry) {
        registry.close();
    }

    @Produces
    @Singleton
    public MaasParametersResolverPreprocessor maasParametersResolverPreprocessor() {
        // Programmatic lookup preserves an absent resolver instead of creating a component-test mock.
        return new MaasParametersResolverPreprocessor(CDI.current().select(MaasParametersResolver.class));
    }

    @Produces
    @Singleton
    public BlueGreenStatePublisher blueGreenStatePublisher() {
        return mock(BlueGreenStatePublisher.class);
    }

    @Produces
    @Singleton
    public KubeOperator kubeOperator() {
        KubeOperator operator = mock(KubeOperator.class);
        when(operator.getAllSecretsWithLabel(any())).thenReturn(Map.of());
        return operator;
    }

    @Produces
    @Singleton
    @Named("commonVariablesUpdateGetter")
    @SuppressWarnings("unchecked")
    public UpdateGetterHelper<Map<String, String>> commonVariablesUpdateGetter() {
        UpdateGetterHelper<Map<String, String>> getter = mock(UpdateGetterHelper.class);
        doAnswer(invocation -> {
            Consumer<Map<String, String>> observer = invocation.getArgument(0);
            observer.accept(Map.of());
            return null;
        }).when(getter).checkForUpdates(any());
        return getter;
    }
}
