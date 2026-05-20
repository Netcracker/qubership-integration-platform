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

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Version;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class FreemakerAutoConfiguration {
    public static final Version CONF_FEATURE_VER = freemarker.template.Configuration.VERSION_2_3_33;

    @Bean
    public StringTemplateLoader freemakerTemplateLoader() {
        return new StringTemplateLoader();
    }

    @Bean
    public freemarker.template.Configuration freemakerConfig(StringTemplateLoader freemakerTemplateLoader) {
        freemarker.template.Configuration fmc = new freemarker.template.Configuration(CONF_FEATURE_VER);
        fmc.setTemplateLoader(freemakerTemplateLoader);
        return fmc;
    }
}
