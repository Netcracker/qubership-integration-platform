package org.qubership.integration.platform.engine.service.externallibrary;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

// resetScriptCache and addScriptToCache reach Camel's private GroovyLanguage.scriptCache by
// reflection and log instead of failing when that reflection breaks. These two tests turn a Camel
// upgrade that renames the field or the method into a build failure rather than a silently stale
// cache. The cache key and the script body disagree on purpose: whichever class evaluate() runs
// reveals whether the cache write and the reset actually reached Camel.
class GroovyLanguageWithResettableCacheTest {

    private DefaultCamelContext camelContext;
    private GroovyLanguageWithResettableCache language;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        language = new GroovyLanguageWithResettableCache();
        language.setCamelContext(camelContext);
        language.start();
    }

    @AfterEach
    void tearDown() {
        language.stop();
        camelContext.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldEvaluateTheScriptThatWasAddedToTheCache() {
        Class<Script> compiled = (Class<Script>) new GroovyShell().getClassLoader().parseClass("'cached'");

        language.addScriptToCache("'not compiled'", compiled);

        assertEquals("cached", language.evaluate("'not compiled'", new HashMap<>(), String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDropCachedScriptsOnReset() {
        Class<Script> compiled = (Class<Script>) new GroovyShell().getClassLoader().parseClass("'cached'");
        language.addScriptToCache("'not compiled'", compiled);
        // Without this the test passes vacuously when the cache write itself is the broken half.
        assertEquals("cached", language.evaluate("'not compiled'", new HashMap<>(), String.class));

        language.resetScriptCache();

        assertEquals("not compiled", language.evaluate("'not compiled'", new HashMap<>(), String.class));
    }
}
