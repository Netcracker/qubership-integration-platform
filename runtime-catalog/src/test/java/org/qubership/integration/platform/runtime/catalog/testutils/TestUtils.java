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

package org.qubership.integration.platform.runtime.catalog.testutils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.apache.commons.io.IOUtils;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;

public class TestUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final YAMLMapper YAML_MAPPER = new YAMLMapper();


    private TestUtils() {
    }


    public static String getResourceFileContent(final String filePath) throws IOException {
        return IOUtils.toString(
                Objects.requireNonNull(TestUtils.class.getResourceAsStream(filePath), "The following file must not be null: " + filePath),
                UTF_8
        );
    }

    public static File getFileFromResource(String filePath) throws URISyntaxException {
        return new File(
                Objects.requireNonNull(TestUtils.class.getResource(filePath), "The following file must not be null: " + filePath).toURI()
        );
    }

    public static ChainElement findElementInChain(Chain chain, String elementType) {
        return chain.getElements().stream()
                .filter(element -> elementType.equals(element.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Element " + elementType + " mot found in test data"));
    }
}
