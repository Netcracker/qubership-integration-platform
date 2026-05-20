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

package org.qubership.integration.platform.engine.configuration.camel;

import jakarta.servlet.Servlet;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.ComponentCustomizer;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextPropsProvider;
import org.qubership.integration.platform.engine.camel.components.servlet.CustomCamelHttpTransportServlet;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomComponent;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomFilterStrategy;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.apache.tomcat.util.buf.EncodedSolidusHandling.PASS_THROUGH;

@Slf4j
@Configuration
public class CamelServletConfiguration {

    public static final String CAMEL_SERVLET_NAME = "CamelServlet";
    public static final String CAMEL_ROUTES_PREFIX = "/routes";
    private static final String CAMEL_SERVLET_MAPPING = CAMEL_ROUTES_PREFIX + "/*";

    /**
     * Alternative - camel autoconfig
     * {@link org.apache.camel.component.servlet.springboot.ServletMappingAutoConfiguration}
     */
    @Bean
    public ServletRegistrationBean<Servlet> camelServlet() {
        if (log.isDebugEnabled()) {
            log.debug("Registration of camel servlet");
        }
        var mapping = new ServletRegistrationBean<>();
        mapping.setName(CAMEL_SERVLET_NAME);
        mapping.addUrlMappings(CAMEL_SERVLET_MAPPING);
        mapping.setServlet(new CustomCamelHttpTransportServlet());

        return mapping;
    }

    @Bean
    public TomcatConnectorCustomizer connectorCustomizer() {
        return connector -> connector.setEncodedSolidusHandling(PASS_THROUGH.getValue());
    }

    @Bean
    public ComponentCustomizer servletCustomComponentCustomizer(
        Optional<ContextPropsProvider> contextPropsProvider
    ) {
        return ComponentCustomizer.builder(ServletCustomComponent.class)
            .build((component) -> {
                component.setHeaderFilterStrategy(
                    new ServletCustomFilterStrategy(contextPropsProvider));
            });
    }
}
