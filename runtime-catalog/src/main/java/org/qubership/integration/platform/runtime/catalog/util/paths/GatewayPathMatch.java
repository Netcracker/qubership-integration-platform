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

package org.qubership.integration.platform.runtime.catalog.util.paths;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves a route path to the Gateway API {@code HTTPPathMatch} type/value pair that
 * correctly matches it: {@code PathPrefix} for a literal path, or {@code RegularExpression}
 * for a path containing one or more {@code {param}} placeholders (each placeholder is
 * replaced with {@code [^/]+}; no anchors are added, since Istio/Envoy's regex path
 * matching already requires a full match). Unless the path already ends in a slash, a
 * trailing {@code /?} is appended to the regex so it stays optional, matching
 * {@code PathPrefix}'s own behavior of treating a path and that same path with a trailing
 * slash as equivalent. Equality is by (type, value), so an instance can be used both to
 * build a new rule's match and as an identity key when checking whether an existing rule's
 * match belongs to a given route.
 */
public final class GatewayPathMatch {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}/]+\\}");
    private static final String PATH_PREFIX = "PathPrefix";
    private static final String REGULAR_EXPRESSION = "RegularExpression";

    private final String type;
    private final String value;

    private GatewayPathMatch(String type, String value) {
        this.type = type;
        this.value = value;
    }

    public static GatewayPathMatch forPath(String path) {
        if (!PLACEHOLDER.matcher(path).find()) {
            return new GatewayPathMatch(PATH_PREFIX, path);
        }
        String regex = PLACEHOLDER.matcher(path).replaceAll("[^/]+");
        return new GatewayPathMatch(REGULAR_EXPRESSION, regex.endsWith("/") ? regex : regex + "/?");
    }

    public static GatewayPathMatch of(String type, String value) {
        return new GatewayPathMatch(type, value);
    }

    public String getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GatewayPathMatch other)) {
            return false;
        }
        return Objects.equals(type, other.type) && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }
}
