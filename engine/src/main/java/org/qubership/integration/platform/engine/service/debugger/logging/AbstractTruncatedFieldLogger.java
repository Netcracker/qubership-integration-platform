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

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

public abstract class AbstractTruncatedFieldLogger {

    @Value("${qip.logging.fields-max-size}")
    protected Integer fieldValueMaxSize;

    protected String truncateValue(String value) {
        if (fieldValueMaxSize != null && fieldValueMaxSize >= 0 && value != null) {
            return StringUtils.abbreviate(value, fieldValueMaxSize + 3);
        }
        return value;
    }
}
