package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.Component;
import org.apache.camel.Producer;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsProvider;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GraphqlCustomEndpointTest {

    @Mock
    Component component;
    @Mock
    HttpClientConfigurer httpClientConfigurer;

    private GraphqlCustomEndpoint endpoint;

    @BeforeEach
    void setUp() {
        endpoint = new GraphqlCustomEndpoint("graphql-custom:http://localhost/graphql", component);
    }

    @Test
    void shouldCreateGraphqlCustomProducerWhenCreateProducer() throws Exception {
        Producer producer = endpoint.createProducer();

        assertNotNull(producer);
        assertInstanceOf(GraphqlCustomProducer.class, producer);
    }

    @Test
    void shouldReturnExistingHttpClientWhenHttpClientAlreadySet() {
        CloseableHttpClient existing = mock(CloseableHttpClient.class);
        endpoint.setHttpClient(existing);

        endpoint.setHttpClientConfigurer(httpClientConfigurer);

        CloseableHttpClient out = endpoint.getHttpclient();

        assertSame(existing, out);
        verifyNoInteractions(httpClientConfigurer);
    }

    @Test
    void shouldCreateAndCacheHttpClientAndApplyProxyAuthAndConfigurerWhenHttpClientNotSet() throws Exception {
        endpoint.setHttpClientConfigurer(httpClientConfigurer);

        endpoint.setProxyHost("proxy.local:3128");

        endpoint.setAccessToken("tkn");
        endpoint.setJwtAuthorizationType("Token");

        endpoint.setUsername("user");
        endpoint.setPassword("pass");

        CloseableHttpClient c1 = endpoint.getHttpclient();
        CloseableHttpClient c2 = endpoint.getHttpclient();

        assertNotNull(c1);
        assertSame(c1, c2);

        ArgumentCaptor<HttpClientBuilder> builderCaptor = ArgumentCaptor.forClass(HttpClientBuilder.class);
        verify(httpClientConfigurer, times(1)).configureHttpClient(builderCaptor.capture());

        HttpClientBuilder builder = builderCaptor.getValue();

        HttpHost proxy = readFirstFieldAssignableTo(builder, HttpHost.class);
        assertNotNull(proxy);
        assertEquals("proxy.local", proxy.getHostName());
        assertEquals(3128, proxy.getPort());

        Object defaultHeadersObj = readFieldByName(builder);
        assertNotNull(defaultHeadersObj);
        assertInstanceOf(Collection.class, defaultHeadersObj);

        @SuppressWarnings("unchecked")
        Collection<Header> defaultHeaders = (Collection<Header>) defaultHeadersObj;

        Header auth = defaultHeaders.stream()
                .filter(h -> HttpHeaders.AUTHORIZATION.equalsIgnoreCase(h.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(auth);
        assertEquals("Token tkn", auth.getValue());

        CredentialsProvider credentialsProvider =
                readFirstFieldAssignableTo(builder, CredentialsProvider.class);
        assertNotNull(credentialsProvider);

        var credentials = credentialsProvider.getCredentials(new AuthScope(null, -1), null);
        assertNotNull(credentials);
        assertInstanceOf(UsernamePasswordCredentials.class, credentials);

        UsernamePasswordCredentials up = (UsernamePasswordCredentials) credentials;
        assertEquals("user", up.getUserName());
        assertArrayEquals("pass".toCharArray(), up.getPassword());
        assertNotNull(credentials);
        assertInstanceOf(UsernamePasswordCredentials.class, credentials);
    }

    @Test
    void shouldUseBearerAuthorizationTypeWhenAccessTokenPresentAndJwtAuthorizationTypeNull() throws Exception {
        endpoint.setHttpClientConfigurer(httpClientConfigurer);

        endpoint.setAccessToken("tkn");
        endpoint.setJwtAuthorizationType(null);

        endpoint.getHttpclient();

        ArgumentCaptor<HttpClientBuilder> builderCaptor = ArgumentCaptor.forClass(HttpClientBuilder.class);
        verify(httpClientConfigurer).configureHttpClient(builderCaptor.capture());

        HttpClientBuilder builder = builderCaptor.getValue();

        Object defaultHeadersObj = readFieldByName(builder);
        assertNotNull(defaultHeadersObj);
        assertInstanceOf(Collection.class, defaultHeadersObj);

        @SuppressWarnings("unchecked")
        Collection<Header> defaultHeaders = (Collection<Header>) defaultHeadersObj;

        Header auth = defaultHeaders.stream()
                .filter(h -> HttpHeaders.AUTHORIZATION.equalsIgnoreCase(h.getName()))
                .findFirst()
                .orElse(null);

        assertNotNull(auth);
        assertEquals("Bearer tkn", auth.getValue());
    }

    private static Object readFieldByName(Object target) throws Exception {
        Field f = findField(target.getClass());
        assert f != null;
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type) {
        Class<?> cur = type;
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                if (f.getName().equals("defaultHeaders")) {
                    return f;
                }
            }
            cur = cur.getSuperclass();
        }
        assert type != null;
        fail("Field '" + "defaultHeaders" + "' not found on " + type.getName());
        return null;
    }

    private static <T> T readFirstFieldAssignableTo(Object target, Class<T> fieldType) throws Exception {
        Field f = findFirstFieldAssignableTo(target.getClass(), fieldType);
        assert f != null;
        f.setAccessible(true);
        Object value = f.get(target);
        return value == null ? null : fieldType.cast(value);
    }

    private static Field findFirstFieldAssignableTo(Class<?> type, Class<?> fieldType) {
        Class<?> cur = type;
        while (cur != null) {
            for (Field f : cur.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
            cur = cur.getSuperclass();
        }
        assert type != null;
        fail("No field assignable to " + fieldType.getName() + " found on " + type.getName());
        return null;
    }
}
