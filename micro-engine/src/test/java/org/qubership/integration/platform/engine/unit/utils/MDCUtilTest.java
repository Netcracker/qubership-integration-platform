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

package org.qubership.integration.platform.engine.unit.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.context.propagation.constant.BusinessIds;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.MDCUtil;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MDCUtilTest {

    @BeforeEach
    void setup() {
        MDC.clear();
    }

    @AfterEach
    void teardown() {
        MDC.clear();
    }

    @Test
    void shouldPutBusinessIdsStringWhenSetBusinessIdsCalled() {
        Map<Object, Object> ids = new LinkedHashMap<>();
        ids.put("orderId", "o-1");
        ids.put("customerId", 42);

        MDCUtil.setBusinessIds(ids);

        assertEquals(ids.toString(), MDC.get(BusinessIds.BUSINESS_IDS));
    }

    @Test
    void shouldOverwriteBusinessIdsWhenSetBusinessIdsCalledTwice() {
        Map<Object, Object> first = Map.of("a", 1);
        Map<Object, Object> second = Map.of("b", 2);

        MDCUtil.setBusinessIds(first);
        MDCUtil.setBusinessIds(second);

        assertEquals(second.toString(), MDC.get(BusinessIds.BUSINESS_IDS));
    }

    @Test
    void shouldStoreEmptyBracesWhenSetBusinessIdsReceivesEmptyMap() {
        MDCUtil.setBusinessIds(Map.of());
        assertEquals("{}", MDC.get(BusinessIds.BUSINESS_IDS));
    }

    @Test
    void shouldThrowNpeWhenSetBusinessIdsReceivesNull() {
        assertThrows(NullPointerException.class, () -> MDCUtil.setBusinessIds(null));
    }

    @Test
    void shouldPutCorrelationIdWhenSetCorrelationIdCalled() {
        MDCUtil.setCorrelationId("corr-123");
        assertEquals("corr-123", MDC.get(CorrelationIdSetter.CORRELATION_ID));
    }

    @Test
    void shouldPutRequestIdWhenSetRequestIdCalled() {
        MDCUtil.setRequestId("req-777");
        assertEquals("req-777", MDC.get("requestId"));
    }

    @Test
    void shouldClearAllValuesWhenClearCalled() {
        MDCUtil.setBusinessIds(Map.of("k", "v"));
        MDCUtil.setCorrelationId("c-1");
        MDCUtil.setRequestId("r-1");

        MDCUtil.clear();

        assertNull(MDC.get(BusinessIds.BUSINESS_IDS));
        assertNull(MDC.get(CorrelationIdSetter.CORRELATION_ID));
        assertNull(MDC.get("requestId"));
    }
}
