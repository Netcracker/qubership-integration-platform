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

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.web.util.UriTemplate;

import java.util.Map;

import static org.apache.camel.Exchange.HTTP_PATH;

@Slf4j
public class CheckpointUtils {
    public record CheckpointInfo(String chainId, String sessionId, String checkpointElementId) {}

    public static final String SESSION_RETRY_PATH_TEMPLATE =
            "/chains/{checkpointChainId}/sessions/{checkpointSessionId}/retry";
    public static final String CHECKPOINT_RETRY_PATH_TEMPLATE =
            "/chains/{checkpointChainId}/sessions/{checkpointSessionId}/checkpoint-elements/{checkpointElementId}/retry";
    public static final String CHECKPOINT_CHAIN_ID_PATH_VAR = "checkpointChainId";
    public static final String CHECKPOINT_SESSION_ID_PATH_VAR = "checkpointSessionId";
    public static final String CHECKPOINT_ELEMENT_ID_PATH_VAR = "checkpointElementId";

    private CheckpointUtils() {
    }

    public static CheckpointInfo extractTriggeredCheckpointInfo(Exchange exchange) {
        Map<String, String> pathVariables = new UriTemplate(CHECKPOINT_RETRY_PATH_TEMPLATE)
                .match(exchange.getMessage().getHeader(HTTP_PATH, "", String.class));

        String chainId = pathVariables.get(CHECKPOINT_CHAIN_ID_PATH_VAR);
        String sessionId = pathVariables.get(CHECKPOINT_SESSION_ID_PATH_VAR);
        String checkpointElementId = pathVariables.get(CHECKPOINT_ELEMENT_ID_PATH_VAR);

        return chainId != null && sessionId != null && checkpointElementId != null
                ? new CheckpointInfo(chainId, sessionId, checkpointElementId)
                : null;
    }

    public static void setSessionProperties(Exchange exchange, String parentSessionId, String originalSessionId) {
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_PARENT_SESSION_ID, parentSessionId);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID, parentSessionId);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_ORIGINAL_SESSION_ID, originalSessionId);
        exchange.setProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID, originalSessionId);
    }
}
