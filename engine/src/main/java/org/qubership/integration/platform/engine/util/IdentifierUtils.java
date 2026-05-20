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

package org.qubership.integration.platform.engine.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.engine.model.ElementIdentifier;

import java.util.regex.Pattern;

@Slf4j
public final class IdentifierUtils {

    private static final String COLON = ":";

    private static final String SESSION_WRAPPER_ELEMENT_ID = "SESSION_WRAPPER";
    private static final String SESSION_WRAPPER_CATCH_ID = "SESSION_WRAPPER_CATCH";
    private static final String SESSION_CATCH_LOG_ID = "SESSION_WRAPPER_CATCH_LOGGER";
    private static final Pattern VALID_UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private static final int MIN_ELEMENT_IDENTIFIER_PARTS = 3;
    private static final int MAX_ELEMENT_IDENTIFIERS_PARTS = 4;
    private static final int MIN_ROUTE_IDENTIFIER_PARTS = 2;
    private static final int MAX_ROUTE_IDENTIFIER_PARTS = 3;

    private static final Pattern REQUEST_ATTEMPT_ID_PATTERN = Pattern.compile("Request attempt--([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private IdentifierUtils() {
    }

    /**
     * Chain identifier has next format chain_uuid:chain_name:random_uuid
     */
    public static Pair<String, String> parseChainIdentifier(Exchange exchange) {
        String routeId = exchange.getFromRouteId();
        if (routeId != null) {
            String[] identifierParts = routeId.split(COLON, MAX_ROUTE_IDENTIFIER_PARTS);
            if (identifierParts.length >= MIN_ROUTE_IDENTIFIER_PARTS) {
                return ImmutablePair.of(identifierParts[0], identifierParts[1]);
            }
        }
        throw new IllegalArgumentException("Invalid session start route identifier: " + routeId);
    }

    /**
     * Is used for providing id object from complex element identifier.
     * Element identifier has next structure: element_uuid:element_camelName:element_name.
     * In case of system, identifier has next structure:
     * element_uuid:element_camelName:element_name:system_uuid
     */
    public static String extractIdFromIdentifier(String nodeId) {
        boolean isServiceElement = IdentifierUtils.isServiceElement(nodeId);
        if (isServiceElement) {
            return null;
        }

        ElementIdentifier spreadIdentifier = spreadIdentifier(nodeId);
        return spreadIdentifier.getElementId();
    }

    /**
     * Method is used to build complex element identifier object based
     * on string from xml configuration
     */
    public static ElementIdentifier spreadIdentifier(NamedNode node) {
        String nodeId = node.getId();
        return spreadIdentifier(nodeId);
    }

    /**
     * Method is used to build complex identifier object based
     * on string from xml configuration
     */
    public static ElementIdentifier spreadIdentifier(String nodeId) {
        if (nodeId != null) {
            String[] identifiers = nodeId.split(COLON, MAX_ELEMENT_IDENTIFIERS_PARTS);
            if (identifiers.length >= MIN_ELEMENT_IDENTIFIER_PARTS) {
                ElementIdentifier identifier = ElementIdentifier.builder()
                        .elementId(identifiers[0])
                        .camelElementName(identifiers[1])
                        .elementName(identifiers[2])
                        .build();

                if (identifiers.length == MAX_ELEMENT_IDENTIFIERS_PARTS) {
                    identifier.setExternalElementId(identifiers[3]);
                }

                return identifier;
            }
        }

        throw new IllegalArgumentException("Invalid id format of element: " + nodeId);
    }

    public static boolean isServiceElement(NamedNode node) {
        return isServiceElement(node.getId());
    }

    /**
     * Every camel configuration must contains custom 'doTry' element which relates
     * with 'doCatch' and 'log' after do 'doCatch'
     */
    public static boolean isServiceElement(String nodeId) {
        return SESSION_WRAPPER_ELEMENT_ID.equals(nodeId)
                || SESSION_WRAPPER_CATCH_ID.equals(nodeId)
                || SESSION_CATCH_LOG_ID.equals(nodeId);
    }

    public static boolean isSessionWrapper(NamedNode node) {
        String nodeId = node.getId();
        if (nodeId != null) {
            String[] identifierParts = nodeId.split(COLON);
            return SESSION_WRAPPER_ELEMENT_ID.equals(identifierParts[0]);
        }
        return false;
    }

    public static boolean isSessionWrapperCatch(NamedNode node) {
        return SESSION_WRAPPER_CATCH_ID.equals(node.getId());
    }

    public static boolean isSessionWrapperCatchLog(NamedNode node) {
        return SESSION_CATCH_LOG_ID.equals(node.getId());
    }

    public static boolean isValidUUID(String text) {
        return VALID_UUID.matcher(text).matches();
    }

    public static String getServiceCallRetryIteratorPropertyName(String elementId) {
        return String.format("internalProperty_serviceCall_%s_Iterator", elementId);
    }

    public static String getServiceCallRetryPropertyName(String elementId) {
        return String.format("internalProperty_serviceCall_%s_Retry", elementId);
    }
}
