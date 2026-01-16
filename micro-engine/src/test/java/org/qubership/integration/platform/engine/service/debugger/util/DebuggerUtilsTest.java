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

package org.qubership.integration.platform.engine.service.debugger.util;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.model.deployment.properties.CamelDebuggerProperties;
import org.qubership.integration.platform.engine.model.logging.LogPayload;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class DebuggerUtilsTest {

    private Exchange mockExchange() {
        return MockExchanges.basic();
    }

    @Test
    void shouldDetectFailedOperationWhenExceptionPresent() {
        Exchange ex = mockExchange();
        when(ex.getException()).thenReturn(new RuntimeException("boom"));
        assertTrue(DebuggerUtils.isFailedOperation(ex));
    }

    @Test
    void shouldDetectNotFailedOperationWhenNoException() {
        Exchange ex = mockExchange();
        when(ex.getException()).thenReturn(null);
        assertFalse(DebuggerUtils.isFailedOperation(ex));
    }

    @Test
    void shouldReturnElementIdWhenFullStepIdContainsSeparator() {
        assertEquals("elt", DebuggerUtils.getStepChainElementId("prefix--elt"));
    }

    @Test
    void shouldReturnEmptyElementIdWhenSeparatorAbsent() {
        assertEquals("", DebuggerUtils.getStepChainElementId("no-separator-here"));
    }

    @Test
    void shouldReturnConcatenatedNodeIdWhenSplitIdNotNull() {
        assertEquals("nodeXsplitY", DebuggerUtils.getNodeIdForExecutionMap("nodeX", "splitY"));
    }

    @Test
    void shouldReturnNodeIdAsIsWhenSplitIdNull() {
        assertEquals("nodeX", DebuggerUtils.getNodeIdForExecutionMap("nodeX", null));
    }

    @Test
    void shouldReturnCompletedWithWarningsWhenElementWarningTrue() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.ELEMENT_WARNING, true);
        assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldReturnCompletedWithWarningsWhenOverallWarningAtomicTrue() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.ELEMENT_WARNING, false);
        ex.setProperty(CamelConstants.Properties.OVERALL_STATUS_WARNING, new AtomicBoolean(true));
        assertEquals(ExecutionStatus.COMPLETED_WITH_WARNINGS, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldReturnCompletedWithErrorsWhenExceptionCaughtPresent() {
        Exchange ex = mockExchange();
        ex.setProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, new IllegalStateException());
        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldReturnCompletedWithErrorsWhenSessionFailedTrue() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.SESSION_FAILED, true);
        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldReturnCompletedWithErrorsWhenLastExceptionPresent() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.LAST_EXCEPTION, new RuntimeException());
        assertEquals(ExecutionStatus.COMPLETED_WITH_ERRORS, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldReturnCompletedNormallyWhenNoWarningsOrErrors() {
        Exchange ex = mockExchange();
        assertEquals(ExecutionStatus.COMPLETED_NORMALLY, DebuggerUtils.extractExecutionStatus(ex));
    }

    @Test
    void shouldRemoveStepFromAllChildExchangesWhenCalled() {
        Exchange root = mockExchange();
        root.setProperty(CamelConstants.Properties.SESSION_ID, "S1");

        Exchange child1 = mockExchange();
        Exchange child2 = mockExchange();

        Deque<String> steps1 = new ConcurrentLinkedDeque<>(List.of("A", "B", "C"));
        Deque<String> steps2 = new ConcurrentLinkedDeque<>(List.of("B", "D"));
        child1.setProperty(CamelConstants.Properties.STEPS, steps1);
        child2.setProperty(CamelConstants.Properties.STEPS, steps2);

        ConcurrentMap<String, Exchange> sessionMap = new ConcurrentHashMap<>();
        sessionMap.put("E1", child1);
        sessionMap.put("E2", child2);

        ConcurrentMap<String, ConcurrentMap<String, Exchange>> exchangesBySession = new ConcurrentHashMap<>();
        exchangesBySession.put("S1", sessionMap);
        root.setProperty(CamelConstants.Properties.EXCHANGES, exchangesBySession);

        DebuggerUtils.removeStepPropertyFromAllExchanges(root, "B");

        assertFalse(child1.getProperty(CamelConstants.Properties.STEPS, Deque.class).contains("B"));
        assertFalse(child2.getProperty(CamelConstants.Properties.STEPS, Deque.class).contains("B"));
        assertTrue(child1.getProperty(CamelConstants.Properties.STEPS, Deque.class).contains("A"));
    }

    @Test
    void shouldChooseBodyWhenExplicitLogPayloadContainsBody() {
        Exchange ex = mockExchange();
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogPayload()).thenReturn(EnumSet.of(LogPayload.BODY));
        String body = "payload";
        assertEquals(body, DebuggerUtils.chooseLogPayload(ex, body, dbg));
    }

    @Test
    void shouldHideBodyWhenExplicitLogPayloadExcludesBody() {
        Exchange ex = mockExchange();
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogPayload()).thenReturn(EnumSet.noneOf(LogPayload.class));
        String body = "payload";
        assertEquals("<body not logged>", DebuggerUtils.chooseLogPayload(ex, body, dbg));
    }

    @Test
    void shouldRespectLegacyFlagWhenLogPayloadIsNull() {
        Exchange ex = mockExchange();
        CamelDebuggerProperties dbg = mock(CamelDebuggerProperties.class, RETURNS_DEEP_STUBS);
        when(dbg.getRuntimeProperties(ex).getLogPayload()).thenReturn(null);

        when(dbg.getRuntimeProperties(ex).isLogPayloadEnabled()).thenReturn(true);
        assertEquals("payload", DebuggerUtils.chooseLogPayload(ex, "payload", dbg));

        when(dbg.getRuntimeProperties(ex).isLogPayloadEnabled()).thenReturn(false);
        assertEquals("<body not logged>", DebuggerUtils.chooseLogPayload(ex, "payload", dbg));
    }

    @Test
    void shouldInitInternalVariablesWhenPropertiesAreNull() {
        Exchange ex = mockExchange();
        long before = System.currentTimeMillis();

        DebuggerUtils.initInternalExchangeVariables(ex);

        Deque<?> steps = ex.getProperty(CamelConstants.Properties.STEPS, Deque.class);
        ConcurrentHashMap<?, ?> exchanges = ex.getProperty(CamelConstants.Properties.EXCHANGES, ConcurrentHashMap.class);
        Long started = ex.getProperty(CamelConstants.Properties.EXCHANGE_START_TIME_MS, Long.class);

        assertNotNull(steps);
        assertTrue(steps.isEmpty());
        assertNotNull(exchanges);
        assertTrue(exchanges.isEmpty());
        assertNotNull(started);
        assertTrue(started >= before && started <= System.currentTimeMillis());
    }

    @Test
    void shouldCopyInternalVariablesWhenPropertiesAlreadySet() {
        Exchange ex = mockExchange();

        ConcurrentLinkedDeque<String> originalSteps = new ConcurrentLinkedDeque<>(List.of("X"));
        ConcurrentHashMap<String, String> originalExchanges = new ConcurrentHashMap<>(Map.of("k", "v"));

        ex.setProperty(CamelConstants.Properties.STEPS, originalSteps);
        ex.setProperty(CamelConstants.Properties.EXCHANGES, originalExchanges);

        DebuggerUtils.initInternalExchangeVariables(ex);

        Deque<String> steps = ex.getProperty(CamelConstants.Properties.STEPS, Deque.class);
        ConcurrentHashMap<?, ?> exchanges = ex.getProperty(CamelConstants.Properties.EXCHANGES, ConcurrentHashMap.class);

        assertNotSame(originalSteps, steps);
        assertEquals(List.of("X"), new ArrayList<>(steps));
        assertNotSame(originalExchanges, exchanges);
        assertEquals("v", exchanges.get("k"));
    }

    @Test
    void shouldGetExceptionFromExchangeWithPriorityOrder() {
        Exchange ex = mockExchange();

        ex.setProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, new IllegalStateException("caught"));
        ex.setProperty(CamelConstants.Properties.LAST_EXCEPTION, new IllegalArgumentException("last"));
        when(ex.getException()).thenReturn(new NullPointerException("ex"));

        Throwable t1 = DebuggerUtils.getExceptionFromExchange(ex);
        assertInstanceOf(IllegalStateException.class, t1);

        ex.setProperty(ExchangePropertyKey.EXCEPTION_CAUGHT, null);
        Throwable t2 = DebuggerUtils.getExceptionFromExchange(ex);
        assertInstanceOf(IllegalArgumentException.class, t2);

        ex.setProperty(CamelConstants.Properties.LAST_EXCEPTION, null);
        Throwable t3 = DebuggerUtils.getExceptionFromExchange(ex);
        assertInstanceOf(NullPointerException.class, t3);
    }

    @Test
    void shouldSetOverallWarningWhenAtomicPresent() {
        Exchange ex = mockExchange();
        ex.setProperty(CamelConstants.Properties.OVERALL_STATUS_WARNING, new AtomicBoolean(false));
        DebuggerUtils.setOverallWarning(ex, true);
        assertTrue(ex.getProperty(CamelConstants.Properties.OVERALL_STATUS_WARNING, AtomicBoolean.class).get());
        DebuggerUtils.setOverallWarning(ex, false);
        assertFalse(ex.getProperty(CamelConstants.Properties.OVERALL_STATUS_WARNING, AtomicBoolean.class).get());
    }

    @Test
    void shouldNotFailWhenOverallWarningAtomicAbsent() {
        Exchange ex = mockExchange();
        assertDoesNotThrow(() -> DebuggerUtils.setOverallWarning(ex, true));
    }

    @Test
    void shouldReturnSameIdWhenFormattingPatternsDoNotMatch() {
        assertEquals("plain-id", DebuggerUtils.getNodeIdFormatted("plain-id"));
        assertEquals("plain-id", DebuggerUtils.getStepNameFormatted("plain-id"));
    }

    @Test
    void shouldExtractUuidFromCustomStepIdInGetNodeIdFormatted() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String nodeId = "My Step Name--" + uuid;
        assertEquals(uuid, DebuggerUtils.getNodeIdFormatted(nodeId));
    }

    @Test
    void shouldExtractNameFromCustomStepIdInGetStepNameFormatted() {
        String uuid = "00000000-0000-0000-0000-000000000000";
        String nodeId = "customStep__42--" + uuid;
        assertEquals("customStep__42", DebuggerUtils.getStepNameFormatted(nodeId));
    }

    @Test
    void shouldSupportCaseInsensitiveUuidMatching() {
        String uuidUpper = "123E4567-E89B-12D3-A456-426614174000";
        String nodeId = "SomeStep--" + uuidUpper;
        assertEquals("SomeStep", DebuggerUtils.getStepNameFormatted(nodeId));
        assertEquals(uuidUpper, DebuggerUtils.getNodeIdFormatted(nodeId));
    }

}
