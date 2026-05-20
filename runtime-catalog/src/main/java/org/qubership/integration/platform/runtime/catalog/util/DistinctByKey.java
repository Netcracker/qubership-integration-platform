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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public class DistinctByKey<T> implements Predicate<T> {

    private final Function<T, Object> keyExtractor;
    private final Set<Object> seenObjects;


    private DistinctByKey(Function<T, Object> keyExtractor) {
        this.keyExtractor = keyExtractor;
        this.seenObjects = ConcurrentHashMap.newKeySet();
    }


    public static <T> DistinctByKey<T> newInstance(Function<T, Object> keyExtractor) {
        return new DistinctByKey<>(keyExtractor);
    }

    @Override
    public boolean test(T key) {
        return seenObjects.add(keyExtractor.apply(key));
    }
}
