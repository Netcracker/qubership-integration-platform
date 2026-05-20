package org.qubership.integration.platform.engine.configuration.k8s;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import io.kubernetes.client.util.credentials.Authentication;
import io.kubernetes.client.util.credentials.TokenFileAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KubeOperatorProducerTest {

    private final KubeOperatorProducer producer = new KubeOperatorProducer();

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private KubernetesProperties properties;

    @BeforeEach
    void setUp() {
        producer.properties = properties;
    }

    @Test
    void shouldCreateKubeOperatorInDevMode() {
        when(properties.devMode()).thenReturn(true);
        when(properties.cluster().uri()).thenReturn("https://dev-cluster");
        when(properties.cluster().namespace()).thenReturn("dev-namespace");
        when(properties.cluster().devToken()).thenReturn(Optional.of("dev-token"));

        ApiClient apiClient = mock(ApiClient.class);
        AtomicReference<List<?>> constructorArguments = new AtomicReference<>();

        try (MockedConstruction<ClientBuilder> clientBuilders = mockConstruction(ClientBuilder.class, (mock, context) -> {
            when(mock.setVerifyingSsl(false)).thenReturn(mock);
            when(mock.setBasePath(anyString())).thenReturn(mock);
            when(mock.setAuthentication(any(Authentication.class))).thenReturn(mock);
            when(mock.build()).thenReturn(apiClient);
        });
             MockedConstruction<KubeOperator> kubeOperators = mockConstruction(KubeOperator.class, (mock, context) ->
                     constructorArguments.set(List.copyOf(context.arguments())))) {

            KubeOperator result = producer.kubeOperator();

            ClientBuilder builder = clientBuilders.constructed().getFirst();

            verify(builder).setVerifyingSsl(false);
            verify(builder).setBasePath("https://dev-cluster");
            verify(builder).setAuthentication(isA(AccessTokenAuthentication.class));
            verify(builder, never()).setCertificateAuthority(any(byte[].class));
            verify(builder).build();

            assertSame(kubeOperators.constructed().getFirst(), result);
            assertEquals(List.of(apiClient, "dev-namespace", true), constructorArguments.get());
        }
    }

    @Test
    void shouldCreateKubeOperatorInProdMode(@TempDir Path tempDir) throws Exception {
        Path certPath = tempDir.resolve("ca.crt");
        byte[] certificateBytes = new byte[] {1, 2, 3};
        Files.write(certPath, certificateBytes);

        when(properties.devMode()).thenReturn(false);
        when(properties.cluster().uri()).thenReturn("https://prod-cluster");
        when(properties.cluster().namespace()).thenReturn("prod-namespace");
        when(properties.serviceAccount().cert()).thenReturn(certPath.toString());
        when(properties.serviceAccount().tokenFilePath()).thenReturn("/var/run/secrets/kubernetes.io/serviceaccount/token");

        ApiClient apiClient = mock(ApiClient.class);
        AtomicReference<List<?>> constructorArguments = new AtomicReference<>();

        try (MockedConstruction<ClientBuilder> clientBuilders = mockConstruction(ClientBuilder.class, (mock, context) -> {
            when(mock.setVerifyingSsl(false)).thenReturn(mock);
            when(mock.setBasePath(anyString())).thenReturn(mock);
            when(mock.setCertificateAuthority(any(byte[].class))).thenReturn(mock);
            when(mock.setAuthentication(any(Authentication.class))).thenReturn(mock);
            when(mock.build()).thenReturn(apiClient);
        });
             MockedConstruction<KubeOperator> kubeOperators = mockConstruction(KubeOperator.class, (mock, context) ->
                     constructorArguments.set(List.copyOf(context.arguments())))) {

            KubeOperator result = producer.kubeOperator();

            ClientBuilder builder = clientBuilders.constructed().get(0);

            verify(builder).setVerifyingSsl(false);
            verify(builder).setBasePath("https://prod-cluster");
            verify(builder).setCertificateAuthority(certificateBytes);
            verify(builder).setAuthentication(isA(TokenFileAuthentication.class));
            verify(builder).build();

            assertSame(kubeOperators.constructed().getFirst(), result);
            assertEquals(List.of(apiClient, "prod-namespace", false), constructorArguments.get());
        }
    }

    @Test
    void shouldReturnDefaultKubeOperatorWhenClientInitializationFails() {
        when(properties.devMode()).thenReturn(true);
        when(properties.cluster().uri()).thenReturn("https://dev-cluster");
        when(properties.cluster().devToken()).thenReturn(Optional.empty());

        List<List<?>> constructorArguments = new ArrayList<>();

        try (MockedConstruction<ClientBuilder> clientBuilders = mockConstruction(ClientBuilder.class, (mock, context) -> {
            when(mock.setVerifyingSsl(false)).thenReturn(mock);
            when(mock.setBasePath(anyString())).thenReturn(mock);
            when(mock.setAuthentication(any(Authentication.class))).thenReturn(mock);
            when(mock.build()).thenThrow(new RuntimeException("boom"));
        });
             MockedConstruction<KubeOperator> kubeOperators = mockConstruction(KubeOperator.class, (mock, context) ->
                     constructorArguments.add(List.copyOf(context.arguments())))) {

            KubeOperator result = producer.kubeOperator();

            ClientBuilder builder = clientBuilders.constructed().get(0);

            verify(builder).setVerifyingSsl(false);
            verify(builder).setBasePath("https://dev-cluster");
            verify(builder).setAuthentication(isA(AccessTokenAuthentication.class));
            verify(builder).build();

            assertSame(kubeOperators.constructed().getFirst(), result);
            assertEquals(1, constructorArguments.size());
            assertTrue(constructorArguments.getFirst().isEmpty());
        }
    }
}
