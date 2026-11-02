package org.qubership.integration.platform.engine.camel.client.http.interceptor;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.qubership.integration.platform.engine.configuration.security.SecurityConfiguration;

import java.io.IOException;
import java.net.URI;

@Slf4j
public class M2MFallbackHandler implements ExecChainHandler {

    private final UrlCache urlCache;

    public M2MFallbackHandler(UrlCache urlCache) {
        this.urlCache = urlCache;
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        URI requestUri;
        String cacheKey;
        try {
            requestUri = request.getUri();
            cacheKey = UrlCache.calculateCacheKey(requestUri);
        } catch (Exception e) {
            log.error("Unable to calculate URLCache key", e);
            throw new IllegalArgumentException("Unable to calculate URLCache key", e);
        }

        if (!urlCache.containsKey(cacheKey)) {
            request = toRepeatableRequest(request);
            ClassicHttpRequest alteredRequest;
            try {
                alteredRequest = buildRequest(request, SecurityConfiguration.getDefaultM2MToken());
                log.debug("Sending request to {} with kubernetes token", requestUri);
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("Error acquiring kubernetes token for m2m communication", e);

                ClassicHttpRequest fallbackRequest = buildRequest(request, SecurityConfiguration.getOldM2MToken());
                return doRequestFallback(fallbackRequest, scope, chain, cacheKey);
            }

            ClassicHttpResponse response = chain.proceed(alteredRequest, scope);
            if (response.getCode() == 401) {
                log.debug("Failed to establish m2m connection to {} with kubernetes token. Cause: 401 Unauthorized",
                        requestUri);
                response.close();

                ClassicHttpRequest fallbackRequest = buildRequest(request, SecurityConfiguration.getOldM2MToken());
                return doRequestFallback(fallbackRequest, scope, chain, cacheKey);
            }
            return response;
        }

        ClassicHttpRequest fallbackRequest = buildRequest(request, SecurityConfiguration.getOldM2MToken());
        log.debug("Sending request to {} with keycloak token", fallbackRequest.getRequestUri());
        return chain.proceed(fallbackRequest, scope);
    }

    private ClassicHttpRequest toRepeatableRequest(ClassicHttpRequest request) throws IOException {
        HttpEntity entity = request.getEntity();
        if (entity == null || entity.isRepeatable()) {
            return request;
        }

        ByteArrayEntity bufferedEntity = new ByteArrayEntity(
                EntityUtils.toByteArray(entity),
                ContentType.parse(entity.getContentType()),
                entity.getContentEncoding(),
                entity.isChunked()
        );
        return ClassicRequestBuilder.copy(request).setEntity(bufferedEntity).build();
    }

    private ClassicHttpResponse doRequestFallback(
            ClassicHttpRequest fallbackRequest,
            ExecChain.Scope scope,
            ExecChain chain,
            String cacheKey
    ) throws HttpException, IOException {
        log.debug("Sending request to {} with keycloak token", fallbackRequest.getRequestUri());
        ClassicHttpResponse fallbackResponse = chain.proceed(fallbackRequest, scope);
        if (isResponseSuccessful(fallbackResponse)) {
            urlCache.store(cacheKey);
        }
        return fallbackResponse;
    }

    private ClassicHttpRequest buildRequest(final ClassicHttpRequest initialRequest, String token) {
        if (StringUtils.isEmpty(token)) {
            throw new IllegalStateException("M2M token is empty");
        }

        return ClassicRequestBuilder.copy(initialRequest)
                .setHeader("Authorization", "Bearer " + token)
                .build();
    }

    private boolean isResponseSuccessful(ClassicHttpResponse response) {
        int statusCode = response.getCode();
        return statusCode >= 200 && statusCode <= 299;
    }
}
