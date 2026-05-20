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


import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;

import java.util.Collection;

public class CompareListUtils {
    public static boolean listEquals(Collection<?> oldList, Collection<?> newList, boolean strict) {
        if (!listSizeEquals(oldList, newList)) {
            return false;
        }

        for (Object newObject : newList) {
            boolean equalsFound = false;
            for (Object oldObject : oldList) {
                if (newObject instanceof AbstractEntity) {
                    if (((AbstractSystemEntity) newObject).equals(oldObject, strict)) {
                        equalsFound = true;
                        break;
                    }
                } else {
                    if (newObject.equals(oldObject)) {
                        equalsFound = true;
                        break;
                    }
                }
            }
            if (!equalsFound) {
                return false;
            }
        }
        return true;
    }

    public static boolean listEquals(Collection<?> oldList, Collection<?> newList) {
        return listEquals(oldList, newList, true);
    }

    private static boolean listSizeEquals(Collection<?> oldList, Collection<?> newList) {
        if (oldList == null || newList == null) {
            return oldList == newList;
        }

        return oldList.size() == newList.size();
    }
}
