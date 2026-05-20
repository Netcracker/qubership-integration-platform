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

package org.qubership.integration.platform.variables.management.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@ConditionalOnMissingBean(name = "swaggerConfiguration")
@AutoConfiguration(value = "qubershipSwaggerConfiguration")
public class SwaggerAutoConfiguration {

    @Bean
    public OpenAPI getApi() {
        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(getInfo());
    }

    private Info getInfo() {
        return new Info()
                .title("Qubership Integration Platform Variables Management")
                .description("REST API of Qubership Integration Platform Variables Management microservice")
                .extensions(Map.of("x-api-kind", "no-bwc"))
                .version("v1");
    }
}

