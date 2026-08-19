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

package org.qubership.integration.platform.io.writers.camel.xml.templates.helpers;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;
import org.qubership.integration.platform.io.util.MaasUtils;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplateInstantiationException;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplatesHelper;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.util.ElementUtils;
import org.qubership.integration.platform.util.HashUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelOptions.ADDRESSES;
import static org.qubership.integration.platform.library.constants.CamelOptions.BROKERS;

@TemplatesHelper
public class EndpointHelperSource {

    @Value("${qip.gateway.egress.url}")
    private String gatewayUrl;

    @Value("${qip.gateway.egress.protocol}")
    private String gatewayProtocol;

    @Value("${qip.control-plane.chain-routes-registration.egress-gateway:true}")
    private boolean registerOnEgress;

    @Value("${qip.gateway.service-path-prefix:/qip/}")
    private String gatewayServicePathPrefix;

    private static final String ADDRESS_IS_EMPTY_MSG = "Please fill environment address field on the service.";
    private static final String NO_ACTIVE_ENVIRONMENT_MSG = "Please select active environment on the service.";
    private static final String NO_SERVICE_MSG = "Please check required services.";

    @PostConstruct
    private void afterInit() {
        if (!gatewayServicePathPrefix.startsWith("/")) {
            gatewayServicePathPrefix = "/" + gatewayServicePathPrefix;
        }
        if (!gatewayServicePathPrefix.endsWith("/")) {
            gatewayServicePathPrefix = gatewayServicePathPrefix + "/";
        }
    }

    /**
     * Method is used to provide External and Internal service integration address
     */
    public CharSequence integrationAddress(Element element) {
        String type = ElementUtils.getPropertyAsString(element, CamelOptions.SYSTEM_TYPE);
        Optional<ServiceEnvironment> environment = element.getEnvironment();
        return switch (type) {
            case CamelOptions.SYSTEM_TYPE_EXTERNAL -> {
                String address = environment
                    .orElseThrow(() -> new TemplateInstantiationException(NO_ACTIVE_ENVIRONMENT_MSG, element))
                    .getAddress();
                yield String.format("%s://%s/system/%s/%s",
                        this.gatewayProtocol, this.gatewayUrl,
                        element.getOriginalId().orElse(element.getId()), HashUtils.sha1hex(address));
            }
            case CamelOptions.SYSTEM_TYPE_INTERNAL -> {
                String address = environment
                    .orElseThrow(() -> new TemplateInstantiationException(NO_ACTIVE_ENVIRONMENT_MSG, element))
                    .getAddress();
                if (StringUtils.isBlank(address)) {
                    throw new TemplateInstantiationException(ADDRESS_IS_EMPTY_MSG, element);
                }
                yield address;
            }
            case CamelOptions.SYSTEM_TYPE_IMPLEMENTED ->
                    environment.map(ServiceEnvironment::getAddress).orElse("");
            default -> "";
        };
    }

    /**
     * Method is used to provide External and Internal async service integration endpoint
     */
    public CharSequence integrationEndpoint(Element element) {
        ServiceEnvironment environment = element.getEnvironment()
            .orElseThrow(() -> new TemplateInstantiationException(NO_SERVICE_MSG, element));
        ArrayList<String> maasParamList = MaasUtils.getMaasParams(element);
        if (!environment.isActivated()) {
            throw new TemplateInstantiationException(NO_ACTIVE_ENVIRONMENT_MSG, element);
        }
        String endpoint = environment.getAddress();
        if (!maasParamList.isEmpty()) {
            endpoint = getMaasParam(element, endpoint);
        }
        if (StringUtils.isBlank(endpoint)) {
            throw new TemplateInstantiationException(ADDRESS_IS_EMPTY_MSG, element);
        }

        return endpoint;
    }

    private static String getMaasParam(Element element, String endpoint) {
        Map<String, Object> elementProperties = element.getProperties();
        if (StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_KAFKA, (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP))) {
            endpoint = MaasUtils.getMaasParamPlaceholder(element.getOriginalId().orElse(element.getId()), BROKERS);
        } else if (StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_AMQP, (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP))) {
            endpoint = MaasUtils.getMaasParamPlaceholder(element.getOriginalId().orElse(element.getId()), ADDRESSES);
        }
        return endpoint;
    }

    /**
     * Method is used to provide External and Internal service integration address
     */
    public CharSequence gatewayURI(Element element) {
        return this.gatewayUrl + "/" + element.getType() + "/" + element.getId();
    }

    /**
     * Method is used to provide a fall-back External service only(!) integration path
     */
    private CharSequence manualGatewayPath(Element element) {
        String path = gatewayServicePathPrefix;
        if ("service-call".equals(element.getType())) {
            String systemId = ElementUtils.getPropertyAsString(element, CamelOptions.SYSTEM_ID);
            if (StringUtils.isBlank(systemId)) {
                throw new TemplateInstantiationException(NO_SERVICE_MSG, element);
            }
            path += systemId;
        } else {
            path += element.getOriginalId().orElse(element.getId());
        }

        return path;
    }

    /**
     * Method is used to provide gateway protocol for CamelHttpUri header
     */
    public CharSequence gatewayProtocol(Element element) {
        return this.gatewayProtocol;
    }

    public CharSequence gatewayUrl(Element element) {
        return this.gatewayUrl;
    }

    /* Left there for old snapshots only, replaced with externalRoutePath */
    @Deprecated(since = "24.4")
    public CharSequence routeVariable(Element element) {
        return externalRoutePath(element);
    }

    public CharSequence externalRoutePath(Element element) {
        if (registerOnEgress) {
            return String.format("%%%%{%s}", ElementUtils.buildRouteVariableName(element));
        } else {
            return manualGatewayPath(element);
        }
    }
}
