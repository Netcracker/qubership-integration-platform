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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@Slf4j
@AutoConfiguration
public class SwaggerAutoConfiguration {

    private static final String O_AUTH_2_ACCESS_TOKEN = "BearerAuth";

    @Bean
    @ConditionalOnMissingBean
    public OpenAPI getApi() {
        return new OpenAPI()
            .addServersItem(new Server().url("/"))
            .info(getInfo())
            .schemaRequirement(O_AUTH_2_ACCESS_TOKEN, securityScheme())
            .addSecurityItem(new SecurityRequirement().addList(O_AUTH_2_ACCESS_TOKEN));
    }

    private Info getInfo() {
        return new Info()
            .title("Cloud Integration Platform Catalog")
            .description("REST API of Cloud Integration Platform Catalog microservice")
            .extensions(Map.of("x-api-kind", "no-bwc"))
            .version("v1")
            .contact(getContact());
    }

    private Contact getContact() {
        return new Contact()
            .name("Netcracker Opensource Group")
            .email("opensourcegroup@netcracker.com");
    }

    private static SecurityScheme securityScheme() {
        return new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT");
    }
}
