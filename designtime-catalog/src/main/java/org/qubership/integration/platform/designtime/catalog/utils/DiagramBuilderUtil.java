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

package org.qubership.integration.platform.designtime.catalog.utils;

import org.qubership.integration.platform.designtime.catalog.service.designgenerator.SequenceDiagramBuilder;

import java.util.List;
import java.util.Map;

import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.AFTER;
import static org.qubership.integration.platform.catalog.model.constant.CamelOptions.LABEL;
import static org.qubership.integration.platform.catalog.model.designgenerator.DiagramOperationType.*;

public class DiagramBuilderUtil {
    private DiagramBuilderUtil() {
    }

    public static void buildValidateRequest(String refChainId, SequenceDiagramBuilder builder, Map<String, Object> properties) {
        List<Map<String, Object>> afterList = (List<Map<String, Object>>) properties.get(AFTER);
        if (afterList != null && !afterList.isEmpty()) {
            boolean atLeastOneHandler = false;
            for (Map<String, Object> after : afterList) {
                if (after != null && after.containsKey(LABEL)) {
                    builder.append(atLeastOneHandler ? ELSE : START_ALT, "Schema: " + after.get(LABEL));
                    builder.append(LINE_WITH_ARROW_SOLID_RIGHT, refChainId, refChainId, "Validate request");
                    atLeastOneHandler = true;
                }
            }

            if (atLeastOneHandler) {
                builder.append(END);
            }
        }
    }
}
