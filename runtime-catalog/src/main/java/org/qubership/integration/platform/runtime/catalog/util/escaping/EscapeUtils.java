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

package org.qubership.integration.platform.runtime.catalog.util.escaping;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;

public class EscapeUtils {
    private static final Set<Character> ESCAPED_CHARS = "\\*_{}[]()<>#+!|".chars().mapToObj(a -> (char) a).collect(Collectors.toSet());
    public static final int UNPRINTABLE_SYMBOLS_THRESHOLD = 32;
    public static final String LINE_BREAK = "<br>";

    /**
     * Recursively escape markdown characters in object strings (via reflection).
     * <br>Supported parameters types:
     * <br>1. List
     * <br>2. Map
     * <br>3. String
     */
    public static <T> void escapeMarkdownDataRecursive(T object) throws IllegalAccessException, EscapeUtilsException {
        Set<Object> viewedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
        escapeMarkdownDataRecursive(object, viewedObjects);
    }

    private static <T> void escapeMarkdownDataRecursive(T object, Set<Object> viewedObjects) throws IllegalAccessException, EscapeUtilsException {
        if (viewedObjects.contains(object)) {
            throw new EscapeUtilsException("Object reference loop detected");
        }
        viewedObjects.add(object);
        if (object == null || object.getClass().isPrimitive() || object instanceof Enum) {
            viewedObjects.remove(object);
            return;
        }
        if (object instanceof Map mapObject) {
            for (Object obj : mapObject.entrySet()) {
                Map.Entry entry = (Map.Entry) obj;
                Object value = entry.getValue();
                if (value instanceof String stringObject) {
                    String escapeResult = EscapeUtils.escapeAndReplaceForMarkdown(stringObject);
                    entry.setValue(escapeResult);
                } else {
                    escapeMarkdownDataRecursive(value);
                }
            }
            viewedObjects.remove(object);
            return;
        }
        if (object instanceof List listObject) {
            for (int i = 0; i < listObject.size(); i++) {
                Object value = listObject.get(i);
                if (value instanceof String stringObject) {
                    String escapeResult = EscapeUtils.escapeAndReplaceForMarkdown(stringObject);
                    listObject.set(i, escapeResult);
                } else {
                    escapeMarkdownDataRecursive(value);
                }
            }
            viewedObjects.remove(object);
            return;
        }

        // iterate over object class fields
        Field[] fields = object.getClass().getDeclaredFields();
        for (Field field : fields) {
            Class<?> fieldType = field.getType();
            int modifiers = field.getModifiers();
            if (!Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !field.isAnnotationPresent(EscapeUtilExclude.class)) {
                boolean accessible = field.canAccess(object);
                if (!accessible) {
                    field.setAccessible(true);
                }
                try {
                    Object fieldValue = field.get(object);

                    if (fieldType == String.class) {
                        String escapeResult = EscapeUtils.escapeAndReplaceForMarkdown((String) fieldValue);
                        field.set(object, escapeResult);
                    } else {
                        escapeMarkdownDataRecursive(fieldValue);
                    }
                } finally {
                    if (!accessible) {
                        field.setAccessible(false);
                    }
                }
            }
        }

        viewedObjects.remove(object);
    }

    private static String escapeAndReplaceForMarkdown(String input) {
        if (StringUtils.isEmpty(input)) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length() + (int) (input.length() * 0.08));

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (ESCAPED_CHARS.contains(c)) {
                sb.append("\\");
            }
            if (c == '\n' || c == '\r') {
                sb.append(LINE_BREAK);
            } else {
                // replace unprintable symbols with space
                c = c >= UNPRINTABLE_SYMBOLS_THRESHOLD ? c : ' ';
                sb.append(c);
            }
        }

        return sb.toString();
    }
}
