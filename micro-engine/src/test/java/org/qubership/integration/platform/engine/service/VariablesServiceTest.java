package org.qubership.integration.platform.engine.service;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.configuration.camel.StartupErrorHandlingConfiguration;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
import org.qubership.integration.platform.engine.consul.updates.UpdateGetterHelper;
import org.qubership.integration.platform.engine.errorhandling.DeploymentRetriableException;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class VariablesServiceTest {

    private static final String SECRET_LABEL = "variables";
    private static final String DEFAULT_SECRET_NAME = "default-secret";

    @Mock
    private KubeOperator operator;
    @Mock
    private ApplicationConfiguration applicationConfiguration;
    @Mock
    private UpdateGetterHelper<Map<String, String>> commonVariablesUpdateGetter;
    @Mock
    private StartupErrorHandlingConfiguration startupErrorHandlingConfiguration;

    @Test
    void shouldInjectCommonVariablesAndNamespaceWhenCommonVariablesRefreshes() {
        VariablesService service = service(false);
        when(applicationConfiguration.getNamespace()).thenReturn("namespace-a");
        stubCommonVariables(Map.of("host", "example.org"));

        service.refreshCommonVariables();

        assertEquals("https://example.org/namespace-a", service.injectVariables("https://#{host}/#{namespace}"));
    }

    @Test
    void shouldEscapeCommonVariablesWhenRequested() {
        VariablesService service = service(false);
        when(applicationConfiguration.getNamespace()).thenReturn("namespace-a");
        stubCommonVariables(Map.of("payload", "<tag&>"));

        service.refreshCommonVariables();

        assertEquals("body=&lt;tag&amp;&gt;", service.injectVariables("body=#{payload}", true));
    }

    @Test
    void shouldRemoveEmptyQueryPropertyBeforeInjectingVariables() {
        VariablesService service = service(false);
        when(applicationConfiguration.getNamespace()).thenReturn("namespace-a");
        stubCommonVariables(Map.of("host", "example.org", "empty", ""));

        service.refreshCommonVariables();

        assertEquals("host=example.org", service.injectVariables("host=#{host}&amp;empty=#{empty}"));
    }

    @Test
    void shouldUseOnlyNamespaceWhenCommonVariablesAreMissing() {
        VariablesService service = service(false);
        when(applicationConfiguration.getNamespace()).thenReturn("namespace-a");
        doThrow(new KVNotFoundException("missing"))
                .when(commonVariablesUpdateGetter)
                .checkForUpdates(any());

        service.refreshCommonVariables();

        assertEquals("namespace-a", service.injectVariables("#{namespace}"));
    }

    @Test
    void shouldInjectSecuredVariablesAndSkipDefaultSecretWhenDisabled() {
        VariablesService service = service(false);
        when(operator.getAllSecretsWithLabel(Pair.of(SECRET_LABEL, "secured"))).thenReturn(Map.of(
                DEFAULT_SECRET_NAME, Map.of("password", "default-password"),
                "custom-secret", Map.of("token", "secret-token")
        ));

        service.refreshSecuredVariables();

        assertEquals("secret-token", service.injectVariables("#{custom-secret:token}"));
        assertThrows(DeploymentRetriableException.class, () -> service.injectVariables("#{password}"));
    }

    @Test
    void shouldInjectDefaultSecretVariablesWhenEnabled() {
        VariablesService service = service(true);
        when(operator.getAllSecretsWithLabel(Pair.of(SECRET_LABEL, "secured"))).thenReturn(Map.of(
                DEFAULT_SECRET_NAME, Map.of("password", "default-password")
        ));

        service.refreshSecuredVariables();

        assertEquals("default-password", service.injectVariables("#{password}"));
    }

    @Test
    void shouldInjectVariablesToExchangeProperties() {
        VariablesService service = service(false);
        when(applicationConfiguration.getNamespace()).thenReturn("namespace-a");
        stubCommonVariables(Map.of("host", "example.org"));
        Map<String, Object> properties = new HashMap<>();

        service.refreshCommonVariables();
        service.injectVariablesToExchangeProperties(properties);

        assertInstanceOf(MergedVariablesMap.class, properties.get(CamelConstants.Properties.VARIABLES_PROPERTY_MAP_NAME));
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) properties.get(CamelConstants.Properties.VARIABLES_PROPERTY_MAP_NAME);
        assertEquals("example.org", variables.get("host"));
        assertEquals("namespace-a", variables.get("namespace"));
    }

    @Test
    void shouldDetectVariableReferences() {
        VariablesService service = service(false);

        assertTrue(service.hasVariableReferences("host=#{host}"));
        assertFalse(service.hasVariableReferences("host=${host}"));
    }

    @Test
    void shouldThrowWhenVariableCannotBeResolved() {
        VariablesService service = service(false);

        DeploymentRetriableException exception = assertThrows(
                DeploymentRetriableException.class,
                () -> service.injectVariables("host=#{missing}"));

        assertEquals("Couldn't resolve variables. #{missing} variable doesn't exist", exception.getMessage());
    }

    @Test
    void shouldIgnoreInitializationErrorsWhenConfigured() {
        VariablesService service = service(false);
        KubeApiException exception = new KubeApiException("kube is unavailable");
        when(operator.getAllSecretsWithLabel(Pair.of(SECRET_LABEL, "secured"))).thenThrow(exception);
        when(operator.isDevmode()).thenReturn(false);
        when(startupErrorHandlingConfiguration.ignoreVariablesErrors()).thenReturn(true);

        assertDoesNotThrow(service::initialize);
    }

    @Test
    void shouldPropagateInitializationErrorsWhenNotIgnored() {
        VariablesService service = service(false);
        KubeApiException exception = new KubeApiException("kube is unavailable");
        when(operator.getAllSecretsWithLabel(Pair.of(SECRET_LABEL, "secured"))).thenThrow(exception);
        when(operator.isDevmode()).thenReturn(false);
        when(startupErrorHandlingConfiguration.ignoreVariablesErrors()).thenReturn(false);

        assertSame(exception, assertThrows(KubeApiException.class, service::initialize));
    }

    private VariablesService service(boolean defaultSecretEnabled) {
        return new VariablesService(
                operator,
                applicationConfiguration,
                commonVariablesUpdateGetter,
                SECRET_LABEL,
                DEFAULT_SECRET_NAME,
                defaultSecretEnabled,
                startupErrorHandlingConfiguration);
    }

    private void stubCommonVariables(Map<String, String> variables) {
        doAnswer(invocation -> {
            Consumer<Map<String, String>> consumer = invocation.getArgument(0);
            consumer.accept(new HashMap<>(variables));
            return null;
        }).when(commonVariablesUpdateGetter).checkForUpdates(any());
    }
}
