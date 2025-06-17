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

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class StringToFolderContentFilterConverter implements Converter<String, FolderContentFilter> {
    private final ObjectMapper mapper;

    @Autowired
    public StringToFolderContentFilterConverter(@Qualifier("primaryObjectMapper") ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public FolderContentFilter convert(String source) {
        try {
            return StringUtils.isBlank(source)
                ? new FolderContentFilter()
                : mapper.readValue(source, FolderContentFilter.class);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
