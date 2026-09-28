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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PodSecrets {

    public static final String DEFAULT_BASE_PATH = "/etc/secrets";
    public static final String DEFAULT_SECRET_FILE = "pod-secrets";

    private PodSecrets() {
    }

    public static String read(String propertyName) {
        return read(propertyName, DEFAULT_SECRET_FILE);
    }

    public static String read(String propertyName, String secretFile) {
        return read(propertyName, secretFile, DEFAULT_BASE_PATH);
    }

    public static String read(String propertyName, String secretFile, String basePath) {
        String value = readSecretFile(propertyName, secretFile, basePath);
        return value == null ? readEnvironment(propertyName) : value;
    }

    static String readSecretFile(String propertyName, String secretFile, String basePath) {
        Path directory = Path.of(basePath, secretFile).toAbsolutePath().normalize();
        Path resolved = directory.resolve(propertyName).normalize();
        if (!directory.equals(resolved.getParent())) {
            throw new IllegalStateException("Failed to read secret file " + resolved + " for key "
                    + propertyName + ". Reason: Directory injection or path traversal detected");
        }
        for (String candidate : candidateNames(propertyName)) {
            Path file = directory.resolve(candidate);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                return Files.readString(file);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read secret file " + secretFile + " for key "
                        + propertyName + ". Reason: " + exception.getMessage(), exception);
            }
        }
        return null;
    }

    private static String readEnvironment(String propertyName) {
        for (String candidate : candidateNames(propertyName)) {
            String value = System.getenv(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Set<String> candidateNames(String propertyName) {
        return new LinkedHashSet<>(List.of(
                propertyName,
                propertyName.toUpperCase(Locale.ROOT),
                propertyName.toLowerCase(Locale.ROOT)));
    }
}
