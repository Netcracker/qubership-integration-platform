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

package org.qubership.integration.platform.catalog.util;

import org.qubership.integration.platform.catalog.model.designgenerator.DiagramConstants;
import org.qubership.integration.platform.catalog.model.designgenerator.ReservedPlaceholders;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.regex.Matcher;

public class DiagramBuilderEscapeUtil {

    // character codes from unicode table
    private static final Set<Character> MERMAID_ESCAPE_SUBSET =
            Set.of('#', '<', '>', ',', '-', ':', ';');

    private DiagramBuilderEscapeUtil() {
    }

    public static String removeOrReplaceUnsupportedCharacters(String arg) {
        StringBuilder stringBuilder = new StringBuilder();
        for (char c : arg.toCharArray()) {
            if ((c >= 48 && c <= 57) || (c >= 64 && c <= 90) || (c >= 97 && c <= 122)) {
                stringBuilder.append(c);
            } else {
                if (c == ' ') {
                    stringBuilder.append('_');
                }
            }
        }
        return stringBuilder.toString();
    }

    public static String escapeMermaidArg(String arg) {
        StringBuilder stringBuilder = new StringBuilder();
        for (char c : arg.toCharArray()) {
            if (c >= 32 && c <= 126 && !MERMAID_ESCAPE_SUBSET.contains(c)) {
                stringBuilder.append(c);
            } else {
                stringBuilder.append('#').append(Integer.toString(c, 10)).append(';');
            }
        }
        return stringBuilder.toString();
    }

    public static String escapePlantUMLArg(String arg) {
        return '"' + arg + '"';
    }

    public static String[] substituteReferences(String chainId, ChainElement currentElement, String[] args) {
        List<String> result = new ArrayList<>();

        for (String arg : args) {
            for (Map.Entry<String, String> entry : DiagramBuilderEscapeUtil.extractPlaceholders(arg).entrySet()) {
                String replacement;
                switch (DiagramConstants.RESERVED_PLACEHOLDERS.getOrDefault(entry.getValue(), ReservedPlaceholders.EMPTY)) {
                    case ELEMENT_CHAIN_SELF_REF_PLACEHOLDER:
                        replacement = chainId;
                        break;
                    case ELEMENT_NAME_REF_PLACEHOLDER:
                        replacement = currentElement.getName();
                        break;
                    default:
                        replacement = "";
                        break;
                }

                arg = arg.replace(entry.getKey(), replacement);
            }
            result.add(arg);
        }

        return result.toArray(new String[0]);
    }

    public static String substituteProperties(String chainId, ChainElement currentElement, String text) {
        if (text == null) {
            return null;
        }

        String result = text;

        for (Map.Entry<String, String> entry : DiagramBuilderEscapeUtil.extractPlaceholders(text).entrySet()) {
            String replacement;
            switch (DiagramConstants.RESERVED_PLACEHOLDERS.getOrDefault(entry.getValue(), ReservedPlaceholders.EMPTY)) {
                case ELEMENT_CHAIN_SELF_REF_PLACEHOLDER:
                    replacement = chainId;
                    break;
                case ELEMENT_NAME_REF_PLACEHOLDER:
                    replacement = currentElement.getName();
                    break;
                default:
                    Object property = currentElement.getProperties().getOrDefault(entry.getValue(), DiagramConstants.EMPTY_PROPERTY_STUB);
                    replacement = property == null || StringUtils.isEmpty(property.toString()) ?
                        DiagramConstants.EMPTY_PROPERTY_STUB :
                        property.toString();
                    break;
            }
            result = result.replace(entry.getKey(), replacement);
        }

        return result;
    }

    /**
     * @return [placeholder, paramName]
     */
    private static Map<String, String> extractPlaceholders(String line) {
        Map<String, String> placeholders = new HashMap<>();
        if (line != null) {
            Matcher matcher = DiagramConstants.PLACEHOLDER_PATTERN.matcher(line);
            while (matcher.find()) {
                String placeholder = matcher.group();
                placeholders.put(placeholder, placeholder.substring(3, placeholder.length() - 1));
            }
        }
        return placeholders;
    }
}
