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

package org.qubership.integration.platform.engine.camel;

import org.apache.camel.impl.engine.DefaultClassResolver;
import org.apache.camel.util.CastUtils;

public class QipCustomClassResolver extends DefaultClassResolver {
    private final ClassLoader classLoader;

    public QipCustomClassResolver(ClassLoader сlassLoader) {
        this.classLoader = сlassLoader;
    }

    @Override
    public Class<?> resolveClass(String name) {
        Class<?> answer = loadClass(name, classLoader);
        if (answer == null) {
            answer = loadClass(name, org.apache.camel.impl.engine.DefaultClassResolver.class.getClassLoader());
            if (answer == null && getApplicationContextClassLoader() != null) {
                // fallback and use application context class loader
                answer = loadClass(name, getApplicationContextClassLoader());
            }
        }
        return answer;
    }

    @Override
    public <T> Class<T> resolveClass(String name, Class<T> type) {
        Class<T> answer = CastUtils.cast(loadClass(name, classLoader));
        if (answer == null) {
            answer = CastUtils.cast(loadClass(name, org.apache.camel.impl.engine.DefaultClassResolver.class.getClassLoader()));
            if (answer == null && getApplicationContextClassLoader() != null) {
                // fallback and use application context class loader
                answer = CastUtils.cast(loadClass(name, getApplicationContextClassLoader()));
            }
        }
        return answer;
    }

    @Override
    public Class<?> resolveClass(String name, ClassLoader loader) {
        return loadClass(name, loader);
    }

    @Override
    public <T> Class<T> resolveClass(String name, Class<T> type, ClassLoader loader) {
        return CastUtils.cast(loadClass(name, loader));
    }

    @Override
    public Class<?> resolveMandatoryClass(String name) throws ClassNotFoundException {
        Class<?> answer = resolveClass(name);
        if (answer == null) {
            throw new ClassNotFoundException(name);
        }
        return answer;
    }

    @Override
    public <T> Class<T> resolveMandatoryClass(String name, Class<T> type) throws ClassNotFoundException {
        Class<T> answer = resolveClass(name, type);
        if (answer == null) {
            throw new ClassNotFoundException(name);
        }
        return answer;
    }

    @Override
    public Class<?> resolveMandatoryClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> answer = resolveClass(name, loader);
        if (answer == null) {
            throw new ClassNotFoundException(name);
        }
        return answer;
    }

    @Override
    public <T> Class<T> resolveMandatoryClass(String name, Class<T> type, ClassLoader loader) throws ClassNotFoundException {
        Class<T> answer = resolveClass(name, type, loader);
        if (answer == null) {
            throw new ClassNotFoundException(name);
        }
        return answer;
    }
}
