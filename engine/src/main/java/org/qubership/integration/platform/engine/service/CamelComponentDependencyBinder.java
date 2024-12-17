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

package org.qubership.integration.platform.engine.service;

import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.CHAIN_ID_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.CHAIN_NAME_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ELEMENT_ID_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ELEMENT_NAME_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.ENGINE_DOMAIN_TAG;
import static org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore.MAAS_CLASSIFIER;

import org.qubership.integration.platform.engine.camel.components.kafka.TaggedMetricsKafkaClientFactory;
import org.qubership.integration.platform.engine.camel.components.rabbitmq.NoOpMetricsCollector;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletTagsProvider;
import org.qubership.integration.platform.engine.configuration.ServerConfiguration;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentConfiguration;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentInfo;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;
import org.qubership.integration.platform.engine.service.debugger.metrics.MetricsStore;
import org.qubership.integration.platform.engine.service.testing.TestingService;
import com.rabbitmq.client.MetricsCollector;
import com.rabbitmq.client.impl.MicrometerMetricsCollector;
import io.grpc.Status;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.MicrometerHttpClientInterceptor;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.function.UnaryOperator;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.camel.component.kafka.DefaultKafkaClientFactory;
import org.apache.camel.component.kafka.KafkaClientFactory;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.qubership.integration.platform.engine.model.ElementOptions;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CamelComponentDependencyBinder {
    private final ServerConfiguration serverConfiguration;
    private final MetricsStore metricsStore;
    private final Optional<TestingService> testingService;

    @Autowired
    public CamelComponentDependencyBinder(ServerConfiguration serverConfiguration,
        MetricsStore metricsStore,
        Optional<TestingService> testingService
    ) {
        this.serverConfiguration = serverConfiguration;
        this.metricsStore = metricsStore;
        this.testingService = testingService;
    }

    public void bindToRegistry(
        SpringCamelContext context,
        DeploymentInfo deploymentInfo,
        DeploymentConfiguration deploymentConfiguration
    ) {
        for (ElementProperties elementProperties : deploymentConfiguration.getProperties()) {
            String elementId = elementProperties.getElementId();
            if (isKafkaChainElement(elementProperties)) {
                bindForKafka(context, deploymentInfo, elementProperties, elementId);
                continue;
            }

            if (isAmpqChainElement(elementProperties)) {
                bindForRabbitMQ(context, deploymentInfo, elementProperties, elementId);
                continue;
            }

            if (isGrpcChainElement(elementProperties)) {
                bindForGRPC(context, deploymentInfo, elementProperties, elementId);
                continue;
            }

            if (isHttpChainElement(elementProperties) && !isHttpTriggerElement(elementProperties)) {
                bindForHttpSender(context, deploymentInfo, elementProperties, elementId);
                continue;
            }

            if (isHttpTriggerElement(elementProperties)) {
                bindForHttpTrigger(context, deploymentInfo, elementProperties, elementId);
            }
        }
    }

    private void bindForHttpTrigger(SpringCamelContext context, DeploymentInfo deploymentInfo,
        ElementProperties elementProperties, String elementId) {
        KeyValues tags = buildMetricTags(deploymentInfo, elementProperties,
            deploymentInfo.getChainName());
        ServletTagsProvider servletTagsProvider = new ServletTagsProvider(tags);
        context.getRegistry().bind(elementId, ServletTagsProvider.class, servletTagsProvider);
    }

    private void bindForHttpSender(SpringCamelContext context, DeploymentInfo deploymentInfo,
        ElementProperties elementProperties, String elementId) {
        HttpClientConfigurer httpClientConfigurer = clientBuilder -> {
            if (metricsStore.isMetricsEnabled()) {
                MicrometerHttpClientInterceptor interceptor = new MicrometerHttpClientInterceptor(
                    metricsStore.getMeterRegistry(),
                    request -> {
                        try {
                            return elementProperties.getProperties().get(
                                ChainProperties.OPERATION_PATH) != null ?
                                elementProperties.getProperties().get(ChainProperties.OPERATION_PATH) :
                                request.getUri().toString();
                        } catch (URISyntaxException e) {
                            log.error("Failed to get URI from request");
                            return "";
                        }
                    },
                    buildMetricTagsLegacy(deploymentInfo, elementProperties,
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
        context.getRegistry().bind(elementId, HttpClientConfigurer.class, httpClientConfigurer);
    }

    private void bindForGRPC(
            SpringCamelContext context,
            DeploymentInfo deploymentInfo,
            ElementProperties elementProperties,
            String elementId
    ) {
        if (metricsStore.isMetricsEnabled()) {
            Iterable<Tag> tags = buildMetricTagsLegacy(deploymentInfo, elementProperties,
                    deploymentInfo.getChainName());
            UnaryOperator<Counter.Builder> counterCustomizer = counter -> counter.tags(tags);
            UnaryOperator<Timer.Builder> timerCustomizer = timer -> timer.tags(tags);
            MetricCollectingClientInterceptor metricInterceptor =
                new MetricCollectingClientInterceptor(metricsStore.getMeterRegistry(), counterCustomizer, timerCustomizer, Status.Code.OK);
            context.getRegistry().bind(elementId, metricInterceptor);
        }
    }

    private void bindForRabbitMQ(SpringCamelContext context, DeploymentInfo deploymentInfo,
        ElementProperties elementProperties, String elementId) {
        Collection<Tag> tags = buildMetricTagsLegacy(deploymentInfo, elementProperties,
            deploymentInfo.getChainName());

        String maasClassifier = elementProperties.getProperties()
            .get(ElementOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (!StringUtils.isEmpty(maasClassifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, maasClassifier));
        }

        MetricsCollector metricsCollector = metricsStore.isMetricsEnabled()
            ? new MicrometerMetricsCollector(
            metricsStore.getMeterRegistry(), "rabbitmq", tags)
            : new NoOpMetricsCollector();
        context.getRegistry().bind(elementId, MetricsCollector.class, metricsCollector);
    }

    private void bindForKafka(SpringCamelContext context, DeploymentInfo deploymentInfo,
        ElementProperties elementProperties, String elementId) {
        DefaultKafkaClientFactory defaultFactory = new DefaultKafkaClientFactory();
        Collection<Tag> tags = buildMetricTagsLegacy(deploymentInfo, elementProperties,
            deploymentInfo.getChainName());

        String maasClassifier = elementProperties.getProperties()
            .get(ElementOptions.MAAS_DEPLOYMENT_CLASSIFIER_PROP);
        if (!StringUtils.isEmpty(maasClassifier)) {
            tags.add(Tag.of(MAAS_CLASSIFIER, maasClassifier));
        }

        // For camel 'kafka' and 'kafka-custom' component
        KafkaClientFactory kafkaClientFactory = metricsStore.isMetricsEnabled()
            ? new TaggedMetricsKafkaClientFactory(
            defaultFactory,
            metricsStore.getMeterRegistry(),
            tags)
            : defaultFactory;
        context.getRegistry().bind(elementId, KafkaClientFactory.class, kafkaClientFactory);
        context.getRegistry().bind(elementId + "-v2", KafkaClientFactory.class, kafkaClientFactory);
    }


    private static boolean isHttpTriggerElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return ChainElementType.HTTP_TRIGGER.equals(chainElementType);
    }

    private static boolean isKafkaChainElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return ChainElementType.isKafkaAsyncElement(chainElementType) && (
            (!isServiceCallOrAsyncApiTrigger(chainElementType))
                || ChainProperties.OPERATION_PROTOCOL_TYPE_KAFKA.equals(
                elementProperties.getProperties().get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP)));
    }

    private static boolean isAmpqChainElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return ChainElementType.isAmqpAsyncElement(chainElementType) && (
            (!isServiceCallOrAsyncApiTrigger(chainElementType))
                || ChainProperties.OPERATION_PROTOCOL_TYPE_AMQP.equals(
                elementProperties.getProperties().get(ChainProperties.OPERATION_PROTOCOL_TYPE_PROP)));
    }

    private static boolean isHttpChainElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        String protocol = elementProperties.getProperties().get(
            ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);
        return ChainElementType.isHttpElement(chainElementType) && (
            (!ChainElementType.SERVICE_CALL.equals(chainElementType))
                || ChainProperties.OPERATION_PROTOCOL_TYPE_HTTP.equals(protocol)
                || ChainProperties.OPERATION_PROTOCOL_TYPE_GRAPHQL.equals(protocol));
    }

    private static boolean isGrpcChainElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        String protocol = elementProperties.getProperties().get(
            ChainProperties.OPERATION_PROTOCOL_TYPE_PROP);
        return ChainElementType.SERVICE_CALL.equals(chainElementType)
            && ChainProperties.OPERATION_PROTOCOL_TYPE_GRPC.equals(protocol);
    }

    private static boolean isServiceCallOrAsyncApiTrigger(ChainElementType chainElementType) {
        return ChainElementType.SERVICE_CALL.equals(chainElementType)
            || ChainElementType.ASYNCAPI_TRIGGER.equals(chainElementType);
    }

    Collection<Tag> buildMetricTagsLegacy(
        DeploymentInfo deploymentInfo,
        ElementProperties elementProperties,
        String chainName
    ) {
        return new ArrayList<>(Arrays.asList(
            Tag.of(CHAIN_ID_TAG, deploymentInfo.getChainId()),
            Tag.of(CHAIN_NAME_TAG, chainName),
            Tag.of(ELEMENT_ID_TAG, elementProperties.getProperties().get(ChainProperties.ELEMENT_ID)),
            Tag.of(ELEMENT_NAME_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_NAME)),
            Tag.of(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain()))
        );
    }

    KeyValues buildMetricTags(
        DeploymentInfo deploymentInfo,
        ElementProperties elementProperties,
        String chainName
    ) {
        return KeyValues.of(
            KeyValue.of(CHAIN_ID_TAG, deploymentInfo.getChainId()),
            KeyValue.of(CHAIN_NAME_TAG, chainName),
            KeyValue.of(ELEMENT_ID_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_ID)),
            KeyValue.of(ELEMENT_NAME_TAG, elementProperties.getProperties().get(
                ChainProperties.ELEMENT_NAME)),
            KeyValue.of(ENGINE_DOMAIN_TAG, serverConfiguration.getDomain()));
    }
}
