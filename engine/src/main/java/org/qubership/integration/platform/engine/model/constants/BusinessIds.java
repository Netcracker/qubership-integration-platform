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

package org.qubership.integration.platform.engine.model.constants;

import java.util.Map;

public final class BusinessIds {
    public static final String CHAIN_ID = "chainId";

    /**
     * [request_prop_name, log_prop_name]
     * Mappings for businessIdentifiers map building
     */
    public static final Map<String, String> MAPPING = Map.ofEntries(
            Map.entry("chainId", CHAIN_ID),
            Map.entry("checkpointElementId", "sessionElementId"),
            Map.entry("sessionId", "sessionId")
    );

    public static final String BUSINESS_IDS = "businessIdentifiers";

    private BusinessIds() {
    }
}
