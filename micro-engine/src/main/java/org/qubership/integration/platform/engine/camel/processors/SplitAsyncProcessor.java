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

package org.qubership.integration.platform.engine.camel.processors;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SplitAsyncProcessor implements Processor {
    @Override
    public void process(Exchange exchange) throws Exception {
        exchange.setProperty(Properties.IS_MAIN_EXCHANGE, false);
        exchange.removeProperty(Properties.CHAIN_TIME_OUT_AFTER);
    }
}
