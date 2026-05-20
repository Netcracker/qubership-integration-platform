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

package org.qubership.integration.platform.engine.camel;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.component.kafka.serde.KafkaHeaderDeserializer;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;

@Slf4j
@Component
public class KafkaHeaderStringDeserializer implements KafkaHeaderDeserializer {
    private final String encoding = "UTF8";

    @Override
    public Object deserialize(String key, byte[] value) {
        try {
            return value == null ? null : new String(value, this.encoding);
        } catch (UnsupportedEncodingException e) {
            log.warn("Error when deserializing kafka headers from byte[] to string due to unsupported encoding " + this.encoding);
            return value;
        }
    }
}
