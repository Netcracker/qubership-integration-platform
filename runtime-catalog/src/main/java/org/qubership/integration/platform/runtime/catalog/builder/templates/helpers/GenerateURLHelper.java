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

import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplatesHelper;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SnapshotCreationException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.springframework.util.CollectionUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@TemplatesHelper
public class GenerateURLHelper {

    private static final String CHECK_REQUIRED_FIELDS_FOR_ELEMENT = "Check required fields for element.";

    /**
     * Method is used to append the path and query parameter for URL
     *
     * @param element
     * @return
     */
    public CharSequence integeratePathAndQueryParams(ChainElement element) {
        String oldFormatUrl = getOldFormatUrl(element);
        if (!StringUtils.isBlank(oldFormatUrl)) {
            return oldFormatUrl;
        }

        return generatePathParamString(element) + generateQueryParamString(element);
    }

    /**
     * Checks if there is old format url saved (with path and query parameters already filled on ui)
     * */
    private String getOldFormatUrl(ChainElement element) {
        String baseURL = element.getPropertyAsString(CamelOptions.OPERATION_PATH);
        Map<String, String> pathMap = (LinkedHashMap) element.getProperty(CamelOptions.OPERATION_PATH_PARAMETERS);
        Map<String, String> queryMap = (LinkedHashMap) element.getProperty(CamelOptions.OPERATION_QUERY_PARAMETERS);
        if (CollectionUtils.isEmpty(pathMap) && CollectionUtils.isEmpty(queryMap)) {
            return null;
        }

        if (pathMap != null) {
            for (String key : pathMap.keySet()) {
                if (StringUtils.isEmpty(pathMap.get(key))) {
                    continue;
                }

                if (!baseURL.contains(pathMap.get(key))) {
                    return null;
                }
            }
        }

        if (queryMap != null) {
            for (String key : queryMap.keySet()) {
                if (StringUtils.isEmpty(queryMap.get(key))) {
                    continue;
                }

                if (!baseURL.contains(String.format("%s=%s", key, queryMap.get(key)))) {
                    return null;
                }
            }
        }

        return baseURL;
    }

    private String generatePathParamString(ChainElement element) {
        String baseURL = element.getPropertyAsString(CamelOptions.OPERATION_PATH);

        if (baseURL == null) {
            baseURL = "";
        }

        Map<String, String> map = (LinkedHashMap) element.getProperty(CamelOptions.OPERATION_PATH_PARAMETERS);

        if (map == null || map.isEmpty()) {
            return baseURL;
        }

        for (String key : map.keySet()) {
            if (StringUtils.isEmpty(map.get(key))) {
                throw new SnapshotCreationException(CHECK_REQUIRED_FIELDS_FOR_ELEMENT, element);
            }

            baseURL = baseURL.replace("{" + key + "}", map.get(key));
        }

        return baseURL;
    }

    private String generateQueryParamString(ChainElement element) {
        StringBuilder result = new StringBuilder();
        Map<String, String> map = (LinkedHashMap) element.getProperty(CamelOptions.OPERATION_QUERY_PARAMETERS);

        if (map == null || map.isEmpty()) {
            return result.toString();
        }

        for (String key : map.keySet()) {
            if (!StringUtils.isEmpty(map.get(key))) {
                if (result.length() == 0) {
                    result.append("?");
                } else {
                    result.append("&");
                }
                result.append(key).append("=").append(map.get(key));
            }
        }
        return result.toString();
    }
}
