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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create;

import com.netcracker.cloud.security.core.utils.k8s.impl.UrlCache;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.MicrometerHttpClientInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.qubership.integration.platform.engine.client.http.interceptor.M2MFallbackHandler;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.deployment.processing.ElementProcessingAction;
import org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.MetricTagsHelper;
import org.qubership.integration.platform.engine.service.deployment.processing.qualifiers.OnAfterDeploymentContextCreated;
import org.qubership.integration.platform.engine.service.testing.TestingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URISyntaxException;
import java.util.Optional;
import java.util.function.Predicate;

import static org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers.ChainElementTypeHelper.isHttpTriggerElement;

@Slf4j
@Component
@OnAfterDeploymentContextCreated
public class HttpSenderDependencyBinder extends ElementProcessingAction {
    private final MetricsStore metricsStore;
    private final MetricTagsHelper metricTagsHelper;
    private final Optional<TestingService> testingService;
    private final boolean m2mFallbackEnabled;
    private final UrlCache m2mUrlCache;
    private final Predicate<ElementProperties> m2mElementChecker;

    @Autowired
    public HttpSenderDependencyBinder(
        MetricsStore metricsStore,
        MetricTagsHelper metricTagsHelper,
        Optional<TestingService> testingService,
        @Value("${qip.chains.http-client.m2m.fallback-interceptor.enabled}") boolean m2mFallbackEnabled,
        UrlCache m2mUrlCache,
        Predicate<ElementProperties> m2mElementChecker
    ) {
        this.metricsStore = metricsStore;
        this.metricTagsHelper = metricTagsHelper;
        this.testingService = testingService;
        this.m2mFallbackEnabled = m2mFallbackEnabled;
        this.m2mUrlCache = m2mUrlCache;
        this.m2mElementChecker = m2mElementChecker;
    }

    @Override
    public boolean applicableTo(ElementProperties properties) {
        return isHttpChainElement(properties) && !isHttpTriggerElement(properties);
    }

    @Override
    public void apply(
        SpringCamelContext context,
        ElementProperties elementProperties,
        DeploymentInfo deploymentInfo
    ) {
        HttpClientConfigurer httpClientConfigurer = clientBuilder -> {
            if (isKubernetesM2MEnabled(elementProperties)) {
                clientBuilder.addExecInterceptorLast("m2m-fallback-interceptor", new M2MFallbackHandler(m2mUrlCache));
            }

            if (metricsStore.isMetricsEnabled()) {
                MicrometerHttpClientInterceptor interceptor = new MicrometerHttpClientInterceptor(
                    metricsStore.getMeterRegistry(),
                    request -> {
                        try {
                            return elementProperties.getProperties().get(
                                ChainProperties.OPERATION_PATH) != null
                                        ? elementProperties.getProperties().get(ChainProperties.OPERATION_PATH)
                                        : request.getUri().toString();
                        } catch (URISyntaxException e) {
                            log.error("Failed to get URI from request");
                            return "";
                        }
                    },
                    metricTagsHelper.buildMetricTagsLegacy(deploymentInfo, elementProperties,
                        deploymentInfo.getChainName()),
                    true
                );
                clientBuilder.addRequestInterceptorFirst(interceptor.getRequestInterceptor());
                clientBuilder.addResponseInterceptorLast(interceptor.getResponseInterceptor());
            }

            testingService.ifPresent(s -> {
                if (s.canBeMocked(elementProperties)) {
                    HttpRequestInterceptor endpointMockInterceptor =
                            s.buildEndpointMockInterceptor(deploymentInfo.getChainId(), elementProperties);
                    clientBuilder.addRequestInterceptorFirst(endpointMockInterceptor);
                    clientBuilder.setRoutePlanner(s.buildRoutePlanner(deploymentInfo.getChainId(), elementProperties));
                }
            });

            // enable or disable connection reuse, depends on element property
            if (!Boolean.parseBoolean(
                elementProperties.getProperties().getOrDefault(
                    ChainProperties.REUSE_ESTABLISHED_CONN, "true"))) {
                clientBuilder.setConnectionReuseStrategy(
                    (HttpRequest request, HttpResponse response, HttpContext httpContext) -> false);
            }

            // disable automatic retries on error
            clientBuilder.disableAutomaticRetries();
        };
        String elementId = elementProperties.getElementId();
        context.getRegistry().bind(elementId, HttpClientConfigurer.class, httpClientConfigurer);
    }

    private boolean isKubernetesM2MEnabled(ElementProperties elementProperties) {
        return m2mFallbackEnabled
                && (Boolean.parseBoolean(elementProperties.getProperties().get(ChainProperties.M2M))
                || m2mElementChecker.test(elementProperties));
    }

    private static boolean isHttpChainElement(ElementProperties properties) {
        String elementType = properties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        String protocol = properties.getProperties().get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);
        return ChainElementType.isHttpElement(chainElementType) && (
            (!ChainElementType.SERVICE_CALL.equals(chainElementType))
                || ChainProperties.OPERATION_PROTOCOL_TYPE_HTTP.equals(protocol)
                || ChainProperties.OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(protocol));
    }
}
