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

package org.qubership.integration.platform.engine.registry;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.http.common.DefaultHttpRegistry;
import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.http.common.HttpRegistry;
import org.apache.camel.http.common.HttpRegistryProvider;
import org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GatewayHttpRegistry implements HttpRegistry {

    private final HttpRegistry httpRegistry;

    public GatewayHttpRegistry() {
        this.httpRegistry = DefaultHttpRegistry.getHttpRegistry(
            CamelServletConfiguration.CAMEL_SERVLET_NAME);
    }

    @Override
    public void register(HttpConsumer consumer) {
        httpRegistry.register(consumer);
    }

    @Override
    public void register(HttpRegistryProvider provider) {
        httpRegistry.register(provider);
    }

    @Override
    public void unregister(HttpConsumer consumer) {
        httpRegistry.unregister(consumer);
    }

    @Override
    public void unregister(HttpRegistryProvider provider) {
        httpRegistry.unregister(provider);
    }

    @Override
    public HttpRegistryProvider getCamelServlet(String servletName) {
        return httpRegistry.getCamelServlet(servletName);
    }
}
