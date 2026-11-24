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

package org.qubership.integration.platform.runtime.catalog.configuration;

import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.folder.StringToFolderContentFilterConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;


@AutoConfiguration
public class WebAutoConfiguration implements WebMvcConfigurer {


    private final StringToFolderContentFilterConverter stringToFolderContentFilterConverter;
    private final JsonMapper primaryObjectMapper;

    @Autowired
    public WebAutoConfiguration(
            StringToFolderContentFilterConverter stringToFolderContentFilterConverter,
            @Qualifier("primaryObjectMapper") JsonMapper primaryObjectMapper) {
        this.stringToFolderContentFilterConverter = stringToFolderContentFilterConverter;
        this.primaryObjectMapper = primaryObjectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MDCInterceptor());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        WebMvcConfigurer.super.addFormatters(registry);
        registry.addConverter(stringToFolderContentFilterConverter);
    }

    /** Keep binary response converters ahead of Jackson so byte arrays and resources are written as files. */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // Keep the catalog mapper converter on the server; Boot also adds converter beans to REST clients.
        converters.add(0, new JacksonJsonHttpMessageConverter(primaryObjectMapper));

        int nextPriorityIndex = 0;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof ByteArrayHttpMessageConverter) {
                converters.add(nextPriorityIndex++, converters.remove(i));
                break;
            }
        }
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof ResourceHttpMessageConverter) {
                converters.add(nextPriorityIndex, converters.remove(i));
                break;
            }
        }
    }
}
