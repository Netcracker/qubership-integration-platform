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

package org.qubership.integration.platform.engine.util;

import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.context.propagation.constant.BusinessIds;
import org.slf4j.MDC;

import java.util.Map;

public class MDCUtil {
    private static final String REQUEST_ID = "requestId";

    /**
     * Add businessIds parameters to logger MDC
     */
    public static void setBusinessIds(Map<Object, Object> idsMap) {
        MDC.put(BusinessIds.BUSINESS_IDS, idsMap.toString());
    }

    public static void setCorrelationId(String correlationId) {
        MDC.put(CorrelationIdSetter.CORRELATION_ID, correlationId);
    }

    public static void setRequestId(String requestId) {
        MDC.put(REQUEST_ID, requestId);
    }

    public static void clear() {
        MDC.clear();
    }
}
