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

package org.qubership.integration.platform.runtime.catalog.context;

import org.slf4j.MDC;

import static org.qubership.integration.platform.runtime.catalog.context.ContextConstants.REQUEST_ID;

public final class RequestIdContext {
    public static String get() {
        return MDC.get(REQUEST_ID);
    }

    public static void set(String requestId) {
        MDC.put(REQUEST_ID, requestId);
    }

    public static void clear() {
        MDC.remove(REQUEST_ID);
    }
}
