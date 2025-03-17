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

package org.qubership.integration.platform.engine.service.externallibrary;

import groovy.lang.Script;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.language.groovy.GroovyLanguage;
import org.qubership.integration.platform.engine.events.ExternalLibrariesUpdatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

@Slf4j
@Component("groovy")
public class GroovyLanguageWithResettableCache extends GroovyLanguage {
    public GroovyLanguageWithResettableCache() {
        super();
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

    @EventListener
    public void onExternalLibrariesUpdated(ExternalLibrariesUpdatedEvent event) {
        if (!event.isInitialUpdate()) {
            resetScriptCache();
        }
    }
}
