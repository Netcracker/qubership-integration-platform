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

package org.qubership.integration.platform.engine.service.debugger.logging;

import net.logstash.logback.marker.LogstashMarker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static net.logstash.logback.marker.Markers.append;

@Component
@ConditionalOnProperty(name = "qip.logging.format", havingValue = "json", matchIfMissing = true)
public class LogExchangeMarkers extends AbstractTruncatedFieldLogger {

    public LogstashMarker buildExchangeMarkers(String body, String headers, String properties) {
        return append("exchange_body", truncateValue(body))
                .and(append("exchange_headers", truncateValue(headers)))
                .and(append("exchange_properties", truncateValue(properties)));
    }
}
