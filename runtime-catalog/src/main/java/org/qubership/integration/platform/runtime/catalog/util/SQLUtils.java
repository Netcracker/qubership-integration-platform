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
import java.util.List;

public class SQLUtils {
    /**
     * Convert array ['abc','cde'] to string "{abc,cde}"
     */
    public static String convertListToValuesQuery(List<String> list) {
        StringBuilder builder = new StringBuilder("{");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                String value = list.get(i);
                builder.append(value);
                if (i < list.size() - 1) {
                    builder.append(",");
                }
            }
        }
        return builder.append("}").toString();
    }

    public static <T extends Collection<?>> T prepareCollectionForHqlNotInClause(T input) {
        return input == null || input.isEmpty() ? null : input;
    }
}
