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

package org.qubership.integration.platform.engine.service.groovy;

import groovy.lang.Script;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.language.groovy.GroovyExpression;
import org.apache.camel.language.groovy.GroovyLanguage;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

@Slf4j
@ApplicationScoped
@Named("groovy")
public class GroovyLanguageWithResettableCache extends GroovyLanguage {
    public GroovyLanguageWithResettableCache() {
        super();
    }

    // camel-xml-io-dsl drops whitespace-only expression bodies, leaving the model
    // ExpressionDefinition.expression == null (the JAXB loader used by the Spring Engine keeps the
    // whitespace instead). That null reaches GroovyLanguage.getScriptFromCache, which does
    // ConcurrentHashMap.get(null) and throws NPE. Guard every public entry point that funnels into
    // getScriptFromCache (createExpression, createPredicate, evaluate) by coalescing null to an
    // empty script — restoring parity: an empty groovy script compiles to a no-op returning null.
    @Override
    public GroovyExpression createExpression(String expression) {
        return super.createExpression(coalesce(expression));
    }

    @Override
    public GroovyExpression createPredicate(String expression) {
        return super.createPredicate(coalesce(expression));
    }

    @Override
    public <T> T evaluate(String script, Map<String, Object> bindings, Class<T> resultType) {
        return super.evaluate(coalesce(script), bindings, resultType);
    }

    private static String coalesce(String expression) {
        return expression == null ? "" : expression;
    }

    public void resetScriptCache() {
        log.debug("Resetting groovy script cache");
        try {
            tryResetScriptCache();
        } catch (Exception exception) {
            log.error("Failed to reset groovy script cache", exception);
        }
    }

    public void addScriptToCache(String key, Class<Script> scriptClass) {
        log.debug("Adding compiled groovy script to cache");
        try {
            tryAddScriptToCache(key, scriptClass);
        } catch (Exception exception) {
            log.error("Failed to add compiled groovy script to cache", exception);
        }
    }

    private void tryResetScriptCache()
            throws NoSuchFieldException, IllegalAccessException {
        Field field = this.getClass().getSuperclass().getDeclaredField("scriptCache");
        field.setAccessible(true);
        Map<?, ?> scriptCache = (Map<?, ?>) field.get(this);
        scriptCache.clear();
    }

    private void tryAddScriptToCache(String key, Class<Script> scriptClass)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = this.getClass().getSuperclass().getDeclaredMethod("addScriptToCache", String.class, Class.class);
        method.setAccessible(true);
        method.invoke(this, key, scriptClass);
    }
}
