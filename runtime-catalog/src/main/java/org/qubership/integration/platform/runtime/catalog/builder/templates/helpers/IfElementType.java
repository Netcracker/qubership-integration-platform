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
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;

import java.io.IOException;
import java.util.Set;

@TemplatesHelper("if-element-type")
public class IfElementType implements Helper<ChainElement> {

    public static final String EQUALS = "equals";
    public static final String NOT_EQUALS = "not-equals";

    @Override
    public Object apply(ChainElement element, Options options) throws IOException {
        Set<String> keySet = options.hash.keySet();
        if (CollectionUtils.isEmpty(keySet)) {
            throw new IllegalArgumentException("Operation must be provided");
        }

        String operation = keySet.iterator().next().toLowerCase();
        String expected = String.valueOf(options.hash.get(operation));
        if (test(element, expected, operation)) {
            options.buffer().append(options.fn());
        } else {
            options.buffer().append(options.inverse());
        }
        return options.buffer();
    }

    private boolean test(ChainElement element, String expected, String operation) {
        return switch (operation) {
            case EQUALS -> StringUtils.equals(element.getType(), expected);
            case NOT_EQUALS -> !StringUtils.equals(element.getType(), expected);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }
}
