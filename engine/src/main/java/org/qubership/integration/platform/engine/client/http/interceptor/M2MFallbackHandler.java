package org.qubership.integration.platform.engine.client.http.interceptor;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.qubership.integration.platform.engine.configuration.security.SecurityConfiguration;

import java.io.IOException;

@Slf4j
public class M2MFallbackHandler implements ExecChainHandler {

    private final UrlCache urlCache;

    public M2MFallbackHandler(UrlCache urlCache) {
        this.urlCache = urlCache;
    }

    @Override
    public ClassicHttpResponse execute(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        String cacheKey = UrlCache.calculateCacheKey(request.getRequestUri());
        if (!urlCache.containsKey(cacheKey)) {
            ClassicHttpRequest alteredRequest;
            try {
                alteredRequest = buildRequest(request, SecurityConfiguration.getDefaultM2MToken());
                log.debug("Sending request to {} with kubernetes token", request.getRequestUri());
            } catch (IllegalStateException | IllegalArgumentException e) {
                log.warn("Error acquiring kubernetes token for m2m communication", e);

                ClassicHttpRequest fallbackRequest = buildRequest(request, SecurityConfiguration.getOldM2MToken());
                return doRequestFallback(fallbackRequest, scope, chain, cacheKey);
            }

            ClassicHttpResponse response = chain.proceed(alteredRequest, scope);
            if (response.getCode() == 401) {
                log.debug("Failed to establish m2m connection to {} with kubernetes token. Cause: 401 Unauthorized",
                        request.getRequestUri());
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
