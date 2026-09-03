package org.qubership.integration.platform.engine.service.groovy;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Predicate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
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
    void shouldEvaluateToNullWithoutThrowingWhenExpressionTextIsNull() {
        Exchange exchange = new DefaultExchange(camelContext);
        // The NPE this guards against (ConcurrentHashMap.get(null)) fires at evaluate time, not at
        // createExpression time, so the assertDoesNotThrow must wrap evaluate to be a real guard.
        Expression expression = language.createExpression((String) null);

        Object result = assertDoesNotThrow(() -> expression.evaluate(exchange, Object.class));

        assertNull(result);
    }

    @Test
    void shouldEvaluateWhitespaceOnlyExpressionToNull() {
        Exchange exchange = new DefaultExchange(camelContext);
        Expression expression = language.createExpression("   \n   ");

        assertNull(expression.evaluate(exchange, Object.class));
    }

    @Test
    void shouldMatchFalsyWithoutThrowingWhenPredicateTextIsNull() {
        Exchange exchange = new DefaultExchange(camelContext);
        Predicate predicate = language.createPredicate((String) null);

        boolean matched = assertDoesNotThrow(() -> predicate.matches(exchange));

        assertFalse(matched);
    }

    @Test
    void shouldEvaluateToNullWithoutThrowingWhenScriptingEvaluateGetsNull() {
        Object result = assertDoesNotThrow(
                () -> language.evaluate((String) null, new HashMap<>(), Object.class));

        assertNull(result);
    }

    @Test
    void shouldStillEvaluateNonEmptyScript() {
        Exchange exchange = new DefaultExchange(camelContext);

        Expression expression = language.createExpression("1 + 1");

        assertEquals(2, expression.evaluate(exchange, Integer.class));
    }

    // Both methods below reach Camel's private GroovyLanguage.scriptCache by reflection and log
    // instead of failing when that reflection breaks. These two tests turn a Camel upgrade that
    // renames the field or the method into a build failure rather than a silently stale cache.
    // The cache key and the script body disagree on purpose: whichever class evaluate() runs
    // reveals whether the cache write and the reset actually reached Camel.

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
