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

package org.qubership.integration.platform.engine.camel.components.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import org.apache.camel.component.servlet.CamelHttpTransportServlet;
import org.apache.camel.component.servlet.ServletEndpoint;
import org.apache.camel.http.common.HttpConsumer;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CustomCamelHttpTransportServlet extends CamelHttpTransportServlet {

    private final ConcurrentMap<String, HttpConsumer> consumers = new ConcurrentHashMap<>();

    @Override
    public void init(ServletConfig config) throws ServletException {
        log.debug("CustomCamelHttpTransportServlet init");
        super.init(config);
        this.setServletResolveConsumerStrategy(new CustomHttpRestServletResolveConsumerStrategy());
    }

    @Override
    public void connect(HttpConsumer consumer) {
        ServletEndpoint endpoint = getServletEndpoint(consumer);
        if (endpoint.getServletName() != null && endpoint.getServletName().equals(getServletName())) {
            log.debug("Connecting consumer: {}", consumer);
            consumers.put(consumer.getEndpoint().getEndpointUri(), consumer);
        }
    }

    @Override
    public void disconnect(HttpConsumer consumer) {
        log.debug("Disconnecting consumer: {}", consumer);
        consumers.remove(consumer.getEndpoint().getEndpointUri());
    }

    @Override
    public Map<String, HttpConsumer> getConsumers() {
        return Collections.unmodifiableMap(consumers);
    }

    private ServletEndpoint getServletEndpoint(HttpConsumer consumer) {
        if (!(consumer.getEndpoint() instanceof ServletEndpoint)) {
            throw new RuntimeException(
                    "Invalid consumer type. Must be ServletEndpoint but is "
                            + consumer.getClass().getName());
        }
        return (ServletEndpoint) consumer.getEndpoint();
    }

}
