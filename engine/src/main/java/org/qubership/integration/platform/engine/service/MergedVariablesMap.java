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

package org.qubership.integration.platform.engine.service;

import java.util.*;

public class MergedVariablesMap<K, V> extends HashMap<K, V> {
    private final Set<K> securedVariableNames;

    MergedVariablesMap() {
        this.securedVariableNames = new HashSet<>();
    }

    private Set<K> getSecuredVariableNames() {
        return securedVariableNames;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map instanceof MergedVariablesMap) {
            securedVariableNames.addAll(
                    ((MergedVariablesMap<K, V>) map).getSecuredVariableNames());
        }

        super.putAll(map);
    }

    public void putAll(Map<? extends K, ? extends V> map, boolean isSecret) {
        if (isSecret) {
            securedVariableNames.addAll(map.keySet());
        }

        super.putAll(map);
    }

    @Override
    public V remove(Object key) {
        securedVariableNames.remove(key);

        return super.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        securedVariableNames.remove(key);

        return super.remove(key, value);
    }

    @Override
    public String toString() {
        Iterator<Entry<K, V>> i = entrySet().iterator();
        if (!i.hasNext())
            return "{}";

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (; ; ) {
            Entry<K, V> e = i.next();
            K key = e.getKey();
            Object value = !this.securedVariableNames.contains(key) ? e.getValue() : "***";
            sb.append(key == this ? "(this Map)" : key);
            sb.append('=');
            sb.append(value == this ? "(this Map)" : value);
            if (!i.hasNext())
                return sb.append('}').toString();
            sb.append(',').append(' ');
        }
    }
}
