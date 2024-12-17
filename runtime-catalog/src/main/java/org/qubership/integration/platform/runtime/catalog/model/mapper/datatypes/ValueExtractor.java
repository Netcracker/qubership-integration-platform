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

package org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes;

import java.math.BigDecimal;

import static java.util.Objects.isNull;

public class ValueExtractor {
    public static Object getValue(TypeKind kind, String string) {
        return isNull(string)
                ? null
                : switch (kind) {
                    case STRING -> string;
                    case BOOLEAN -> Boolean.valueOf(string);
                    case INTEGER -> new BigDecimal(string);
                    default -> null;
                };
    }
}
