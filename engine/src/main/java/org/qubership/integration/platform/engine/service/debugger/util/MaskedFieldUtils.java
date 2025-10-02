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

package org.qubership.integration.platform.engine.service.debugger.util;

import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;

import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for managing masked fields in Camel Exchange properties.
 */
public class MaskedFieldUtils {

    /**
     * Retrieves masked fields from a given property object.
     * <p>
     * Expects the property to be a {@code Set<String>}.
     * Returns an empty {@code Set} if null or not a Set.
     *
     * @param maskedFieldsProperty the property object holding masked fields
     * @return a new {@code Set<String>} of masked fields (never null)
     */
    @SuppressWarnings("unchecked")
    public static Set<String> getMaskedFields(Object maskedFieldsProperty) {
        if (maskedFieldsProperty instanceof Set<?>) {
            return new HashSet<>((Set<String>) maskedFieldsProperty);
        }
        return new HashSet<>();
    }

    /**
     * Stores masked fields into the Exchange property.
     * <p>
     * Replaces any existing value with a new {@code Set<String>}.
     *
     * @param exchange the Camel Exchange
     * @param fields   the masked fields to store (ignored if null)
     */
    public static void setMaskedFields(Exchange exchange, Set<String> fields) {
        if (fields != null) {
            exchange.setProperty(
                    CamelConstants.Properties.MASKED_FIELDS_PROPERTY,
                    new HashSet<>(fields) // stored as Set
            );
        }
    }

    /**
     * Adds new masked fields to the existing ones in the Exchange property.
     * <p>
     * If no existing masked fields are present, they will be created.
     *
     * @param exchange  the Camel Exchange
     * @param newFields the fields to add (ignored if null or empty)
     */
    public static void addMaskedFields(Exchange exchange, Set<String> newFields) {
        if (newFields == null || newFields.isEmpty()) {
            return;
        }
        Set<String> existing = getMaskedFields(
                exchange.getProperty(CamelConstants.Properties.MASKED_FIELDS_PROPERTY)
        );
        existing.addAll(newFields);
        setMaskedFields(exchange, existing);
    }
}
