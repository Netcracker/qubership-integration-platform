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

package org.qubership.integration.platform.runtime.catalog.mapper.metadata;

import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class MetadataUtils {
    private MetadataUtils() {}

    public static class Keys {
        public static final String DATA_FORMAT = "dataFormat";
        public static final String SOURCE_FORMAT = "sourceFormat";
        public static final String SOURCE_TYPE = "sourceType";
        public static final String XML_NAMESPACES = "xmlNamespaces";
    }

    public static DataFormat getDataFormat(Metadata metadata) {
        String name = Optional.ofNullable(metadata.get(Keys.DATA_FORMAT))
                .map(Object::toString)
                .orElse(DataFormat.UNSPECIFIED.name())
                .toUpperCase(Locale.ROOT);
        return DataFormat.valueOf(name);
    }

    public static SourceType getSourceType(Metadata metadata) {
        String name = Optional.ofNullable(metadata.get(Keys.SOURCE_TYPE))
                .map(Object::toString)
                .orElse(SourceType.UNSPECIFIED.name())
                .toUpperCase(Locale.ROOT);
        return SourceType.valueOf(name);
    }

    public static DataFormat getSourceFormat(Metadata metadata) {
        String name = Optional.ofNullable(metadata.get(Keys.SOURCE_FORMAT))
                .map(Object::toString)
                .orElse(DataFormat.UNSPECIFIED.name())
                .toUpperCase(Locale.ROOT);
        return DataFormat.valueOf(name);
    }

    public static Collection<XMLNamespace> getXmlNamespaces(Metadata metadata) {
        Object o = metadata.get(Keys.XML_NAMESPACES);
        if (isNull(o) || !(o instanceof Collection)) {
            return Collections.emptyList();
        }
        return ((Collection<?>) o).stream()
                .map(i -> new XMLNamespace(getValue(i, "alias"), getValue(i, "uri")))
                .collect(Collectors.toList());
    }

    private static String getValue(Object m, String key) {
        if (isNull(m) || !(m instanceof Map)) {
            return null;
        }
        Object v = ((Map<?, ?>) m).get(key);
        return isNull(v) ? null : v.toString();
    }
}
