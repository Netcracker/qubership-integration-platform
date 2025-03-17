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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.catalog.exception.SnapshotCreationException;
import org.qubership.integration.platform.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.catalog.model.system.ServiceEnvironment;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.util.ElementUtils;
import org.qubership.integration.platform.catalog.util.HashUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.util.MaasUtils;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Map;

import static org.qubership.integration.platform.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.ADDRESSES;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.BROKERS;

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

    private final static String ADDRESS_IS_EMPTY_MSG = "Please fill environment address field on the service.";
    private final static String NO_ACTIVE_ENVIRONMENT_MSG = "Please select active environment on the service.";
    private final static String NO_SERVICE_MSG = "Please check required services.";

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
    public CharSequence integrationAddress(ChainElement element) {
        String type = element.getPropertyAsString(CamelOptions.SYSTEM_TYPE);

        String address = "";
        ServiceEnvironment environment = element.getEnvironment();

        switch (type) {
            case CamelOptions.SYSTEM_TYPE_EXTERNAL -> {
                if (environment == null)
                    throw new SnapshotCreationException(NO_ACTIVE_ENVIRONMENT_MSG, element);
                address = String.format("%s://%s/system/%s/%s",
                        this.gatewayProtocol, this.gatewayUrl, element.getOriginalId(), HashUtils.sha1hex(environment.getAddress()));
            }
            case CamelOptions.SYSTEM_TYPE_INTERNAL -> {
                if (environment == null)
                    throw new SnapshotCreationException(NO_ACTIVE_ENVIRONMENT_MSG, element);
                address = environment.getAddress();
                if (StringUtils.isBlank(address))
                    throw new SnapshotCreationException(ADDRESS_IS_EMPTY_MSG, element);
            }
            case CamelOptions.SYSTEM_TYPE_IMPLEMENTED ->
                    address = (environment == null) ? "" : environment.getAddress();
        }

        return address;
    }

    /**
     * Method is used to provide External and Internal async service integration endpoint
     */
    public CharSequence integrationEndpoint(ChainElement element) {
        ServiceEnvironment environment = element.getEnvironment();
        ArrayList<String> maasParamList = MaasUtils.getMaasParams(element);
        if (environment == null)
            throw new SnapshotCreationException(NO_SERVICE_MSG, element);
        if (environment.isNotActivated()) {
            throw new SnapshotCreationException(NO_ACTIVE_ENVIRONMENT_MSG, element);
        }
        String endpoint = environment.getAddress();
        if(!maasParamList.isEmpty()){
            endpoint = getMaasParam(element, endpoint);
        }
        if (StringUtils.isBlank(endpoint))
            throw new SnapshotCreationException(ADDRESS_IS_EMPTY_MSG, element);

        return endpoint;
    }

    private static String getMaasParam(ChainElement element, String endpoint) {
        Map<String, Object> elementProperties = element.getProperties();
        if(StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_KAFKA, (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP) )) {
            endpoint = MaasUtils.getMaasParamPlaceholder(element.getOriginalId(), BROKERS);
        }else if(StringUtils.equalsIgnoreCase(OPERATION_PROTOCOL_TYPE_AMQP, (String) elementProperties.get(OPERATION_PROTOCOL_TYPE_PROP) )) {
            endpoint = MaasUtils.getMaasParamPlaceholder(element.getOriginalId(), ADDRESSES);
        }
        return endpoint;
    }

    /**
     * Method is used to provide External and Internal service integration address
     */
    public CharSequence gatewayURI(ChainElement element) {
        return this.gatewayUrl + "/" + element.getType() + "/" + element.getId();
    }

    /**
     * Method is used to provide a fall-back External service only(!) integration path
     */
    private CharSequence manualGatewayPath(ChainElement element) {
        String path = gatewayServicePathPrefix;
        if ("service-call".equals(element.getType())) {
            String systemId = element.getPropertyAsString(CamelOptions.SYSTEM_ID);
            if (StringUtils.isBlank(systemId)) {
                throw new SnapshotCreationException(NO_SERVICE_MSG, element);
            }
            path += systemId;
        } else {
            path += element.getOriginalId();
        }

        return path;
    }

    /**
     * Method is used to provide gateway protocol for CamelHttpUri header
     */
    public CharSequence gatewayProtocol(ChainElement element) {
        return this.gatewayProtocol;
    }

    public CharSequence gatewayUrl(ChainElement element) {
        return this.gatewayUrl;
    }

    /* Left there for old snapshots only, replaced with externalRoutePath */
    @Deprecated(since = "24.4")
    public CharSequence routeVariable(ChainElement element) {
        return externalRoutePath(element);
    }

    public CharSequence externalRoutePath(ChainElement element) {
        if (registerOnEgress) {
            return String.format("%%%%{%s}", ElementUtils.buildRouteVariableName(element));
        } else {
            return manualGatewayPath(element);
        }
    }
}
