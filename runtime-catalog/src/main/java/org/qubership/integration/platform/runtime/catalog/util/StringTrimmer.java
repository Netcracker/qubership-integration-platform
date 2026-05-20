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

package org.qubership.integration.platform.runtime.catalog.util;

import org.mapstruct.Mapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.isNull;

@Mapper(
        componentModel = "spring"
)
public class StringTrimmer {
    public String trimString(String value) {
        return isNull(value) ? null : value.trim();
    }

    public Map<String, Object> trimMapValue(Map<String, Object> property) {
        return trimStringValuesInMap(property);
    }

    private Object trimStringValuesInObject(Object object) {
        if (object instanceof String s) {
            return s.trim();
        } else if (object instanceof Map<?, ?> map) {
            return trimStringValuesInMap(map);
        } else if (object instanceof List<?> list) {
            return list.stream().map(this::trimStringValuesInObject).toList();
        } else {
            return object;
        }
    }

    private <T, V> Map<T, Object> trimStringValuesInMap(Map<T, V> map) {
        return map.entrySet().stream().collect(
                HashMap::new,
                (m, entry) -> m.put(entry.getKey(), trimStringValuesInObject(entry.getValue())),
                HashMap::putAll
        );
    }
}
