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

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

public class MapUtils {
    public static void deepMapTraversalSafe(Map<String, Object> map, Consumer<Object> callback, String... keys) {
        deepMapTraversalSafe(map, callback, 0, keys);
    }

    private static void deepMapTraversalSafe(Map<String, Object> map, Consumer<Object> callback, int keyIndex, String... keys) {
        Object value = map;
        for (int i = keyIndex; i < keys.length; i++) {
            String key = keys[i];
            if (value instanceof Map) {
                value = ((Map<String, Object>) value).get(key);
            } else if (value instanceof Collection) {
                for (Object obj : (Collection<?>) value) {
                    if (obj instanceof Map || obj instanceof Collection) {
                        deepTraversalObject(obj, callback, i, keys);
                    }
                }
                return;
            } else {
                return;
            }
        }
        callback.accept(value);
    }

    private static void deepTraversalObject(Object obj, Consumer<Object> callback, int keyIndex, String... keys) {
        if (obj instanceof Map) {
            deepMapTraversalSafe((Map<String, Object>) obj, callback, keyIndex, keys);
        } else if (obj instanceof Collection) {
            for (Object element : (Collection<?>) obj) {
                deepTraversalObject(element, callback, keyIndex, keys);
            }
        }
    }
}
