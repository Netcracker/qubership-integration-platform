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

package org.qubership.integration.platform.engine.service.deployment.processing.actions.context.create.helpers;

import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.deployment.update.ElementProperties;

public final class ChainElementTypeHelper {
    public static boolean isServiceCallOrAsyncApiTrigger(ChainElementType chainElementType) {
        return ChainElementType.SERVICE_CALL.equals(chainElementType)
            || ChainElementType.ASYNCAPI_TRIGGER.equals(chainElementType);
    }

    public static boolean isHttpTriggerElement(ElementProperties elementProperties) {
        String elementType = elementProperties.getProperties().get(ChainProperties.ELEMENT_TYPE);
        ChainElementType chainElementType = ChainElementType.fromString(elementType);
        return ChainElementType.HTTP_TRIGGER.equals(chainElementType);
    }
}
