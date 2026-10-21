/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.camel.client.http.interceptor;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.client.http.interceptor.M2MFallbackHandler;
import org.qubership.integration.platform.engine.configuration.security.SecurityConfiguration;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class M2MFallbackHandlerTest {

    private static final String REQUEST_URL = "http://some-service:8080/api/v1/resource?param=value";
    private static final String K8S_TOKEN = "k8s-token";
    private static final String OLD_TOKEN = "keycloak-token";
    private static final String AUTHORIZATION = "Authorization";

    @Mock
    private UrlCache urlCache;
    @Mock
    private ExecChain chain;
    @Mock
    private ExecChain.Scope scope;

    private MockedStatic<SecurityConfiguration> securityConfiguration;
    private M2MFallbackHandler handler;
    private ClassicHttpRequest request;
    private String cacheKey;

    @BeforeEach
    void setUp() {
        securityConfiguration = mockStatic(SecurityConfiguration.class);
        handler = new M2MFallbackHandler(urlCache);
        request = new BasicClassicHttpRequest("POST", URI.create(REQUEST_URL));
        request.setHeader("X-Custom", "custom-value");
        cacheKey = UrlCache.calculateCacheKey(REQUEST_URL);
    }

    @AfterEach
    void tearDown() {
        securityConfiguration.close();
    }

    @Test
    void usesOldTokenDirectlyWhenUrlIsCached() throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(true);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse response = response(500);
        when(chain.proceed(any(), eq(scope))).thenReturn(response);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(response, result);
        List<ClassicHttpRequest> sent = sentRequests(1);
        assertBearer(OLD_TOKEN, sent.get(0));
        securityConfiguration.verify(SecurityConfiguration::getDefaultM2MToken, never());
        verify(urlCache, never()).store(anyString());
    }

    @Test
    void throwsWhenUrlIsCachedAndOldTokenIsEmpty() {
        when(urlCache.containsKey(cacheKey)).thenReturn(true);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn("");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> handler.execute(request, scope, chain));

        assertEquals("M2M token is empty", e.getMessage());
        verifyNoInteractions(chain);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 302, 403, 500})
    void returnsKubernetesTokenResponseWhenNotUnauthorized(int status) throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        ClassicHttpResponse response = response(status);
        when(chain.proceed(any(), eq(scope))).thenReturn(response);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(response, result);
        List<ClassicHttpRequest> sent = sentRequests(1);
        ClassicHttpRequest sentRequest = sent.get(0);
        assertBearer(K8S_TOKEN, sentRequest);
        assertNotSame(request, sentRequest);
        assertEquals("POST", sentRequest.getMethod());
        assertEquals(URI.create(REQUEST_URL), sentRequest.getUri());
        assertEquals("custom-value", sentRequest.getFirstHeader("X-Custom").getValue());
        assertNull(request.getFirstHeader(AUTHORIZATION), "original request must not be modified");
        verify(response, never()).close();
        securityConfiguration.verify(SecurityConfiguration::getOldM2MToken, never());
        verify(urlCache, never()).store(anyString());
    }

    @Test
    void replacesExistingAuthorizationHeader() throws Exception {
        request.setHeader(AUTHORIZATION, "Bearer stale");
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        ClassicHttpResponse response = response(200);
        when(chain.proceed(any(), eq(scope))).thenReturn(response);

        handler.execute(request, scope, chain);

        ClassicHttpRequest sentRequest = sentRequests(1).get(0);
        assertEquals(1, sentRequest.getHeaders(AUTHORIZATION).length);
        assertBearer(K8S_TOKEN, sentRequest);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 299})
    void fallsBackToOldTokenOn401AndCachesUrlOnSuccess(int fallbackStatus) throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse unauthorized = response(401);
        ClassicHttpResponse fallbackResponse = response(fallbackStatus);
        when(chain.proceed(any(), eq(scope))).thenReturn(unauthorized, fallbackResponse);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(fallbackResponse, result);
        List<ClassicHttpRequest> sent = sentRequests(2);
        assertBearer(K8S_TOKEN, sent.get(0));
        assertBearer(OLD_TOKEN, sent.get(1));
        assertEquals("custom-value", sent.get(1).getFirstHeader("X-Custom").getValue());

        var order = inOrder(unauthorized, chain);
        order.verify(chain).proceed(any(), eq(scope));
        order.verify(unauthorized).close();
        order.verify(chain).proceed(any(), eq(scope));

        verify(urlCache).store(cacheKey);
        verify(fallbackResponse, never()).close();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 199, 300, 401, 403, 500})
    void fallsBackToOldTokenOn401AndDoesNotCacheUrlOnFailure(int fallbackStatus) throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse unauthorized = response(401);
        ClassicHttpResponse fallbackResponse = response(fallbackStatus);
        when(chain.proceed(any(), eq(scope))).thenReturn(unauthorized, fallbackResponse);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(fallbackResponse, result);
        verify(unauthorized).close();
        sentRequests(2);
        verify(urlCache, never()).store(anyString());
    }

    @Test
    void throwsWhenFallbackAfter401HasEmptyOldToken() throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn("");
        ClassicHttpResponse unauthorized = response(401);
        when(chain.proceed(any(), eq(scope))).thenReturn(unauthorized);

        assertThrows(IllegalStateException.class, () -> handler.execute(request, scope, chain));

        verify(unauthorized).close();
        sentRequests(1);
        verify(urlCache, never()).store(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void fallsBackToOldTokenWhenKubernetesTokenIsMissing(String k8sToken) throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(k8sToken);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse fallbackResponse = response(200);
        when(chain.proceed(any(), eq(scope))).thenReturn(fallbackResponse);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(fallbackResponse, result);
        assertBearer(OLD_TOKEN, sentRequests(1).get(0));
        verify(urlCache).store(cacheKey);
    }

    @Test
    void fallsBackToOldTokenWhenKubernetesTokenAcquisitionFailsWithIllegalArgument() throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken)
                .thenThrow(new IllegalArgumentException("bad audience"));
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse fallbackResponse = response(404);
        when(chain.proceed(any(), eq(scope))).thenReturn(fallbackResponse);

        ClassicHttpResponse result = handler.execute(request, scope, chain);

        assertSame(fallbackResponse, result);
        assertBearer(OLD_TOKEN, sentRequests(1).get(0));
        verify(urlCache, never()).store(anyString());
    }

    @Test
    void fallsBackToOldTokenWhenKubernetesTokenAcquisitionFailsWithIllegalState() throws Exception {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken)
                .thenThrow(new IllegalStateException("token file missing"));
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse response = response(200);
        when(chain.proceed(any(), eq(scope))).thenReturn(response);

        handler.execute(request, scope, chain);

        assertBearer(OLD_TOKEN, sentRequests(1).get(0));
        verify(urlCache).store(cacheKey);
    }

    @Test
    void doesNotCatchOtherKubernetesTokenExceptions() {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        RuntimeException failure = new RuntimeException("unexpected");
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenThrow(failure);

        RuntimeException e = assertThrows(RuntimeException.class, () -> handler.execute(request, scope, chain));

        assertSame(failure, e);
        verifyNoInteractions(chain);
    }

    @Test
    void throwsWhenBothTokensAreUnavailable() {
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(null);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn("");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> handler.execute(request, scope, chain));

        assertEquals("M2M token is empty", e.getMessage());
        verifyNoInteractions(chain);
        verify(urlCache, never()).store(anyString());
    }

    @Test
    void throwsIllegalArgumentExceptionWhenRequestUriCannotBeResolved() throws Exception {
        ClassicHttpRequest brokenRequest = mock(ClassicHttpRequest.class);
        URISyntaxException cause = new URISyntaxException("::", "invalid");
        when(brokenRequest.getUri()).thenThrow(cause);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(brokenRequest, scope, chain));

        assertEquals("Unable to calculate URLCache key", e.getMessage());
        assertSame(cause, e.getCause());
        verifyNoInteractions(urlCache, chain);
        securityConfiguration.verifyNoInteractions();
    }

    @Test
    void throwsIllegalArgumentExceptionWhenCacheKeyCannotBeCalculated() {
        RuntimeException cause = new RuntimeException("bad key");
        try (MockedStatic<UrlCache> urlCacheStatic = mockStatic(UrlCache.class)) {
            urlCacheStatic.when(() -> UrlCache.calculateCacheKey(any(URI.class))).thenThrow(cause);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> handler.execute(request, scope, chain));

            assertEquals("Unable to calculate URLCache key", e.getMessage());
            assertSame(cause, e.getCause());
        }
        verifyNoInteractions(urlCache, chain);
        securityConfiguration.verifyNoInteractions();
    }

    @Test
    void resendsNonRepeatableBodyOnFallbackAfter401() throws Exception {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        request.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), body.length,
                ContentType.TEXT_PLAIN));
        when(urlCache.containsKey(cacheKey)).thenReturn(false);
        securityConfiguration.when(SecurityConfiguration::getDefaultM2MToken).thenReturn(K8S_TOKEN);
        securityConfiguration.when(SecurityConfiguration::getOldM2MToken).thenReturn(OLD_TOKEN);
        ClassicHttpResponse unauthorized = response(401);
        ClassicHttpResponse ok = response(200);
        List<byte[]> sentBodies = new ArrayList<>();
        when(chain.proceed(any(), eq(scope))).thenAnswer(invocation -> {
            ClassicHttpRequest sent = invocation.getArgument(0);
            sentBodies.add(EntityUtils.toByteArray(sent.getEntity()));
            return sentBodies.size() == 1 ? unauthorized : ok;
        });

        handler.execute(request, scope, chain);

        assertEquals(2, sentBodies.size());
        assertArrayEquals(body, sentBodies.get(0));
        assertArrayEquals(body, sentBodies.get(1));
    }

    private List<ClassicHttpRequest> sentRequests(int expectedCount) throws Exception {
        ArgumentCaptor<ClassicHttpRequest> captor = ArgumentCaptor.forClass(ClassicHttpRequest.class);
        verify(chain, times(expectedCount)).proceed(captor.capture(), eq(scope));
        return captor.getAllValues();
    }

    private static void assertBearer(String token, ClassicHttpRequest request) {
        assertEquals("Bearer " + token, request.getFirstHeader(AUTHORIZATION).getValue());
    }

    private static ClassicHttpResponse response(int status) {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        lenient().when(response.getCode()).thenReturn(status);
        return response;
    }
}
