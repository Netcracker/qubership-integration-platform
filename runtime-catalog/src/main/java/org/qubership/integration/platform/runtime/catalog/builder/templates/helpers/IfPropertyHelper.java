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

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handlebars helper, which checks, whether an element property
 * with provided name equals, contain provided value or not
 */
@TemplatesHelper("if-property")
public class IfPropertyHelper extends PropertyHelperSource implements Helper<String> {

    private static final Pattern QUERY_PARAM_PATTERN = Pattern.compile("([^\\?]+)(\\?.*)?");

    private static final String EQUALS = "equals";
    private static final String NOT_EQUALS = "not-equals";
    private static final String PRESENTED = "presented";
    private static final String NOT_PRESENTED = "not-presented";
    private static final String EMPTY = "empty";
    private static final String NOT_EMPTY = "not-empty";
    private static final String CONTAINS = "contains";
    private static final String NOT_CONTAINS = "not-contains";
    private static final String CONTAINS_QUERY_PARAMS = "contains-query-params";
    private static final String NOT_CONTAINS_QUERY_PARAMS = "not-contains-query-params";
    private static final String IN = "in";
    private static final String NOT_IN = "not-in";

    @Override
    public Object apply(String propertyName, Options options) throws IOException {
        var result = test(propertyName, options);
        var buffer = options.buffer();
        if (result) {
            buffer.append(options.fn());
        } else {
            buffer.append(options.inverse());
        }
        return buffer;
    }

    /**
     * If operation not defined, helper checks either such parameter is presented
     */
    private boolean test(String propertyName, Options options) {
        Set<String> keySet = options.hash.keySet();
        if (CollectionUtils.isEmpty(keySet)) {
            throw new IllegalArgumentException("Operation must be provided");
        }

        String operation = keySet.iterator().next().toLowerCase();
        String expected = (String) options.hash.get(operation);
        String actual = getPropertyStringValue(propertyName, options);
        Object actualObject = getPropertyValue(propertyName, options);

        return switch (operation) {
            case EQUALS -> actual != null && actual.equals(expected);
            case NOT_EQUALS -> actual == null || !actual.equals(expected);
            case CONTAINS -> actual != null && actual.contains(expected);
            case NOT_CONTAINS -> actual == null || !actual.contains(expected);
            case EMPTY -> actualObject instanceof Collection ?
                    CollectionUtils.isEmpty((Collection<?>) actualObject) : StringUtils.isEmpty(actual);
            case NOT_EMPTY -> actualObject instanceof Collection ?
                    CollectionUtils.isNotEmpty((Collection<?>) actualObject) : StringUtils.isNotEmpty(actual);
            case PRESENTED -> actual != null;
            case NOT_PRESENTED -> actual == null;
            case CONTAINS_QUERY_PARAMS -> containsQueryParams(actual);
            case NOT_CONTAINS_QUERY_PARAMS -> !containsQueryParams(actual);
            case IN -> actual != null && equalsIn(actual, expected);
            case NOT_IN -> actual == null || !equalsIn(actual, expected);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    private boolean containsQueryParams(String actual) {
        if (actual != null) {
            Matcher matcher = QUERY_PARAM_PATTERN.matcher(actual);
            if (matcher.find()) {
                return matcher.group(2) != null;
            }
        }
        return false;
    }

    private boolean equalsIn(String actual, String expected) {
        for (String item : expected.split(",")) {
            if (actual.equals(item.strip())) {
                return true;
            }
        }
        return false;
    }
}
