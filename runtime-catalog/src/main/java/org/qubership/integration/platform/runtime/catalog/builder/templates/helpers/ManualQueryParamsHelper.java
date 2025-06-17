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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Options;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@TemplatesHelper
@Slf4j
public class ManualQueryParamsHelper {
    private static final Set<String> EXCLUDED_PROPS = Set.of(CamelNames.REUSE_ESTABLISHED_CONN);
    private static final String JSON_ERROR_MESSAGE = "Error while processing a JSON ";
    private static final String JSON_QUERY_PARAMETERS = "jsonQueryParameters";
    private static final String QUERY_PREFIX = "?";
    private static final String QUERY_DELIMITER = "&";
    private static final String QUERY_SUFFIX = "";
    private static final String KEY_VALUE_DELIMITER = "=";

    private final ObjectMapper objectMapper;


    @Autowired
    public ManualQueryParamsHelper(@Qualifier("primaryObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unused")
    public CharSequence manualQuery(Options options) {
        Map<String, Object> hash = options.hash;
        StringJoiner queryJoiner = new StringJoiner(QUERY_DELIMITER, QUERY_PREFIX, QUERY_SUFFIX);

        boolean atLeastOneProperty = false;
        for (Map.Entry<String, Object> entry : hash.entrySet()) {
            if (StringUtils.isNotEmpty((CharSequence) entry.getValue())) {
                if (entry.getKey().equals(JSON_QUERY_PARAMETERS)) {
                    try {
                        Map<String, Object> jsonNode = objectMapper.readValue(
                            entry.getValue().toString(), new TypeReference<>() {});

                        for (Map.Entry<String, Object> jsonEntry : jsonNode.entrySet()) {
                            atLeastOneProperty = true;
                            String key = jsonEntry.getKey();
                            if (!EXCLUDED_PROPS.contains(key)) {
                                queryJoiner.add(key + KEY_VALUE_DELIMITER + jsonEntry.getValue().toString());
                            }
                        }
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to process query JSON parameters", e);
                    }
                } else {
                    atLeastOneProperty = true;
                    queryJoiner.add(entry.getKey() + KEY_VALUE_DELIMITER + entry.getValue());
                }
            }
        }
        return atLeastOneProperty ? queryJoiner.toString() : "";
    }
}
