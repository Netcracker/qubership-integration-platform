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

package org.qubership.integration.platform.runtime.catalog.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.v2.AtlasMapping;
import org.apache.commons.text.StringEscapeUtils;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class MapperTestUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    public static final DefaultAtlasContextFactory FACTORY = DefaultAtlasContextFactory.getInstance();

    public static File getConfigurationFile(String fileName) {
        ClassLoader classLoader = MapperTestUtils.class.getClassLoader();
        File testFile = new File(Objects.requireNonNull(classLoader.getResource(fileName)).getFile());
        return testFile;
    }

    public static String getJsonContentFromFile(String fileName) throws IOException {
        ClassLoader classLoader = MapperTestUtils.class.getClassLoader();
        File contentFile = new File(Objects.requireNonNull(classLoader.getResource(fileName)).getFile());
        return Files.readString(Path.of(contentFile.getPath()));
    }

    public static MappingDescription getMappingFromFile(File file) {
        MappingDescription mappingDescription = null;
        try {
            mappingDescription = YAML_MAPPER.readValue(file, MappingDescription.class);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mappingDescription;
    }

    public static Object processMapping(String source, String mappingConfig) throws IOException, AtlasException {
        String mapping = StringEscapeUtils.unescapeXml(mappingConfig);

        StringReader stringReader = new StringReader(mapping);

        AtlasMapping atlasMapping = OBJECT_MAPPER.readValue(stringReader, AtlasMapping.class);
        AtlasContext context = FACTORY.createContext(atlasMapping);
        AtlasSession session = context.createSession();
        session.setDefaultSourceDocument(source);

        context.process(session);

        Object target = session.getDefaultTargetDocument();
        return target;
    }

    public static String processJsonMapping(String source, String mappingConfig) throws IOException, AtlasException {
        return (String) processMapping(source, mappingConfig);
    }

    public static String processXmlMapping(String source, String mappingConfig) throws IOException, AtlasException {
        return (String) processMapping(source, mappingConfig);
    }
}
