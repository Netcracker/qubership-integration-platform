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

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.CheckpointUtils;

import static org.apache.camel.Exchange.HTTP_PATH;
import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CheckpointUtilsTest {

    @Test
    void shouldExtractInfoWhenPathMatchesCheckpointRetry() {
        Exchange ex = exchangeWithPath("/chains/CHAIN1/sessions/SESS42/checkpoint-elements/CP123/retry");
        CheckpointUtils.CheckpointInfo info = CheckpointUtils.extractTriggeredCheckpointInfo(ex);
        assertNotNull(info);
        assertEquals("CHAIN1", info.chainId());
        assertEquals("SESS42", info.sessionId());
        assertEquals("CP123", info.checkpointElementId());
    }

    @Test
    void shouldReturnNullWhenPathIsSessionRetryNotCheckpointRetry() {
        Exchange ex = exchangeWithPath("/chains/CHAIN1/sessions/SESS42/retry");
        assertNull(CheckpointUtils.extractTriggeredCheckpointInfo(ex));
    }

    @Test
    void shouldReturnNullWhenPathDoesNotMatch() {
        Exchange ex = exchangeWithPath("/other/anything");
        assertNull(CheckpointUtils.extractTriggeredCheckpointInfo(ex));
    }

    @Test
    void shouldReturnNullWhenPathEmptyOrMissing() {
        Exchange exEmpty = exchangeWithPath("");
        assertNull(CheckpointUtils.extractTriggeredCheckpointInfo(exEmpty));

        Exchange exMissing = new DefaultExchange(new DefaultCamelContext());
        assertNull(CheckpointUtils.extractTriggeredCheckpointInfo(exMissing));
    }

    @Test
    void shouldSetAllSessionPropertiesWhenCalled() {
        Exchange ex = new DefaultExchange(new DefaultCamelContext());
        CheckpointUtils.setSessionProperties(ex, "PARENT", "ORIG");

        assertEquals("PARENT", ex.getProperty(CamelConstants.Properties.CHECKPOINT_PARENT_SESSION_ID));
        assertEquals("PARENT", ex.getProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_PARENT_SESSION_ID));
        assertEquals("ORIG", ex.getProperty(CamelConstants.Properties.CHECKPOINT_ORIGINAL_SESSION_ID));
        assertEquals("ORIG", ex.getProperty(CamelConstants.Properties.CHECKPOINT_INTERNAL_ORIGINAL_SESSION_ID));
    }

    private static Exchange exchangeWithPath(String path) {
        DefaultCamelContext ctx = new DefaultCamelContext();
        Exchange ex = new DefaultExchange(ctx);
        ex.getMessage().setHeader(HTTP_PATH, path);
        return ex;
    }
}
