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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
//import io.micrometer.common.KeyValue;
//import io.micrometer.common.KeyValues;
import lombok.extern.slf4j.Slf4j;
//import org.apache.camel.http.common.CamelServlet;
//import org.apache.camel.http.common.HttpCommonEndpoint;
//import org.apache.camel.http.common.HttpConsumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
//import org.jetbrains.annotations.NotNull;
//import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomEndpoint;
import org.qubership.integration.platform.engine.registry.GatewayHttpRegistry;
//import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
//import org.springframework.http.server.observation.ServerRequestObservationContext;

//import static java.util.Objects.isNull;

@Slf4j
@ApplicationScoped
public class CamelServletObservationConvention /*extends DefaultServerRequestObservationConvention */ {
    private final GatewayHttpRegistry httpRegistry;
    private final String camelServletName;
    private final String camelRoutesPrefix;

    @Inject
    public CamelServletObservationConvention(
            @ConfigProperty(name = "quarkus.camel.servlet.servlet-name") String servletName,
            @ConfigProperty(name = "qip.camel.routes-prefix") String routesPrefix,
            GatewayHttpRegistry httpRegistry
    ) {
        this.camelServletName = servletName;
        this.camelRoutesPrefix = routesPrefix;
        this.httpRegistry = httpRegistry;
    }
// FIXME [migration to quarkus]
//    @Override
//    public @NotNull KeyValues getLowCardinalityKeyValues(@NotNull ServerRequestObservationContext context) {
//        KeyValues values = super.getLowCardinalityKeyValues(context);
//
//        if (context.getCarrier().getHttpServletMapping().getServletName().equals(camelServletName)) {
//            CamelServlet camelServlet = (CamelServlet) httpRegistry.getCamelServlet(camelServletName);
//            HttpConsumer consumer = camelServlet.getServletResolveConsumerStrategy()
//                    .resolve(context.getCarrier(), camelServlet.getConsumers());
//            if (!isNull(consumer)) {
//                HttpCommonEndpoint endpoint = consumer.getEndpoint();
//
//                values = values.and(KeyValue.of("uri", camelRoutesPrefix + endpoint.getPath()));
//
//                if (endpoint instanceof ServletCustomEndpoint servletCustomEndpoint) {
//                    if (servletCustomEndpoint.getTagsProvider() != null) {
//                        values = values.and(servletCustomEndpoint.getTagsProvider().get());
//                    }
//                }
//            }
//        }
//
//        return values;
//    }
}
