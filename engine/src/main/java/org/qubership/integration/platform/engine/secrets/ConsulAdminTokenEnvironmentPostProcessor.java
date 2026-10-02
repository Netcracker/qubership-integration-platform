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

package org.qubership.integration.platform.engine.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Feeds the Consul ACL token from a mounted Secret file into the environment.
 *
 * <p>At startup it reads the file {@link #TOKEN_FILE_VARIABLE} points at (default
 * {@link #DEFAULT_TOKEN_FILE}) and puts the value first as {@code consul.token} and
 * {@code spring.cloud.consul.config.acl-token}, so the mount wins over the deprecated
 * {@code CONSUL_ADMIN_TOKEN} environment variable without touching the placeholders in
 * {@code application.yml}. Running before the configuration data import is required because that
 * import resolves {@code spring.cloud.consul.config.acl-token}.
 *
 * <p>For requests made after startup use {@link #readToken()}: it reads the file every time, so a
 * rotated Secret is picked up without a restart. The Spring Cloud Consul import itself captures the
 * token only once, at startup.
 */
public class ConsulAdminTokenEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER - 1;

    public static final String TOKEN_FILE_VARIABLE = "CONSUL_ADMIN_TOKEN_FILE";
    public static final String TOKEN_VARIABLE = "CONSUL_ADMIN_TOKEN";
    public static final String DEFAULT_TOKEN_FILE = "/etc/secrets/pod-secrets/consul_admin_token";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ConsulAdminTokenEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "consulAdminToken";
    private static final String CONSUL_TOKEN_PROPERTY = "consul.token";
    private static final String CONSUL_ACL_TOKEN_PROPERTY = "spring.cloud.consul.config.acl-token";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String token = readFromFile();
        if (token == null) {
            token = readFromEnvironment();
            if (token == null) {
                return;
            }
            LOGGER.warn("Consul admin token is resolved from the deprecated {} environment variable. Mount the"
                    + " token as a file instead; the variable is removed in release 27.3.", TOKEN_VARIABLE);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(CONSUL_TOKEN_PROPERTY, token);
        properties.put(CONSUL_ACL_TOKEN_PROPERTY, token);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Reads the current Consul admin token, preferring the mounted file and falling back to the
     * deprecated environment variable. Nothing is cached, so a rotated Secret takes effect at once.
     *
     * @return the token, or {@code null} if neither the file nor the variable carries one
     */
    public static String readToken() {
        String token = readFromFile();
        return token == null ? readFromEnvironment() : token;
    }

    private static String readFromFile() {
        String location = setting(TOKEN_FILE_VARIABLE);
        Path file = Path.of(location == null || location.isBlank() ? DEFAULT_TOKEN_FILE : location);
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return emptyAsNull(Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException exception) {
            LOGGER.error("Failed to read the Consul admin token file at {}", file, exception);
            return null;
        }
    }

    private static String readFromEnvironment() {
        String token = setting(TOKEN_VARIABLE);
        return token == null ? null : emptyAsNull(token.strip());
    }

    private static String setting(String name) {
        String value = System.getProperty(name);
        return value == null ? System.getenv(name) : value;
    }

    private static String emptyAsNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
