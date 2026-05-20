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

package org.qubership.integration.platform.engine.mapper.atlasmap;

import kotlin.text.Charsets;
import org.apache.http.client.utils.URLEncodedUtils;

import static org.qubership.integration.platform.engine.util.AtlasMapUtils.getQueryParameters;

public class QipJsonAtlasModuleOptionsDecoder {
    public static QipJsonAtlasModuleOptions decode(String uri) {
        QipJsonAtlasModuleOptions options = new QipJsonAtlasModuleOptions();
        for (var nameValuePair : URLEncodedUtils.parse(getQueryParameters(uri), Charsets.UTF_8)) {
            switch (nameValuePair.getName()) {
                case "serializeTargetDocument" ->
                        options.setSerializeTargetDocument(Boolean.parseBoolean(nameValuePair.getValue()));
            }
        }
        return options;
    }
}
