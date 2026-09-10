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

package org.qubership.integration.platform.engine.camel.metrics;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.http.common.CamelServlet;
import org.apache.camel.http.common.HttpCommonEndpoint;
import org.apache.camel.http.common.HttpConsumer;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomEndpoint;
import org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration;
import org.qubership.integration.platform.engine.registry.GatewayHttpRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import static java.util.Objects.isNull;

@Slf4j
@Component
public class CamelServletObservationConvention extends DefaultServerRequestObservationConvention {
    private final GatewayHttpRegistry httpRegistry;

    @Value("${qip.camel.routes.prefix}")
    private String routesPrefix;

    @Autowired
    public CamelServletObservationConvention(GatewayHttpRegistry httpRegistry) {
        this.httpRegistry = httpRegistry;
    }

    @Override
    public @NotNull KeyValues getLowCardinalityKeyValues(@NotNull ServerRequestObservationContext context) {
        KeyValues values = super.getLowCardinalityKeyValues(context);

        if (context.getCarrier().getHttpServletMapping().getServletName().equals(
            CamelServletConfiguration.CAMEL_SERVLET_NAME)) {
            CamelServlet camelServlet = (CamelServlet) httpRegistry.getCamelServlet(
                CamelServletConfiguration.CAMEL_SERVLET_NAME);
            HttpConsumer consumer = camelServlet.getServletResolveConsumerStrategy()
                .resolve(context.getCarrier(), camelServlet.getConsumers());
            if (!isNull(consumer)) {
                HttpCommonEndpoint endpoint = consumer.getEndpoint();

                String pattern = context.getCarrier().getHttpServletMapping().getPattern();
                String uri = getUriTagValue(pattern, endpoint.getPath());

                values = values.and(KeyValue.of("uri", uri));

                if (endpoint instanceof ServletCustomEndpoint servletCustomEndpoint) {
                    if (servletCustomEndpoint.getTagsProvider() != null) {
                        values = values.and(servletCustomEndpoint.getTagsProvider().get());
                    }
                }
            }
        }

        return values;
    }

    private String getUriTagValue(String pattern, String path) {
        String prefix = pattern.startsWith(CamelServletConfiguration.CAMEL_ROUTES_LEGACY_PREFIX)
            ? CamelServletConfiguration.CAMEL_ROUTES_LEGACY_PREFIX
            : routesPrefix;
        return prefix + path;
    }
}
