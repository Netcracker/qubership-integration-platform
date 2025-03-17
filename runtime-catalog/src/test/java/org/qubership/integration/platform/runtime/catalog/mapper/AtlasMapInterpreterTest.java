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

import io.atlasmap.api.AtlasException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.AtlasMapInterpreter;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.skyscreamer.jsonassert.JSONAssert;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class AtlasMapInterpreterTest {

    private static final String CONSTANTS_HEADERS_PROPERTIES_ACTIONS_NO_BODY = "mapper/mapping/config/7_constants_headers_properties_actions_no_body.yml";
    private static final String JSON_TO_JSON_CONFIG = "mapper/mapping/config/8_json_to_json_no_transformation.yml";
    private static final String JSON_TO_JSON_SOURCE = "mapper/mapping/data/8_source.json";
    private static final String JSON_TO_JSON_TARGET = "mapper/mapping/data/8_target.json";
    private static final String XML_TO_JSON_CONFIG = "mapper/mapping/config/9_xml_to_json_no_transformation.yml";
    private static final String XML_TO_JSON_SOURCE = "mapper/mapping/data/9_source.xml";
    private static final String XML_TO_JSON_TARGET = "mapper/mapping/data/9_target.json";
    private static final String XML_TO_XML_CONFIG = "mapper/mapping/config/10_xml_to_xml_no_transformation.yml";
    private static final String XML_TO_XML_SOURCE = "mapper/mapping/data/10_source.xml";
    private static final String XML_TO_XML_TARGET = "mapper/mapping/data/10_target.xml";
    private static final String DEFAULT_VALUE_CONFIG = "mapper/mapping/config/13_default_value.yml";
    private static final String LOOKUP_VALUE_CONFIG = "mapper/mapping/config/14_lookup_values.yml";
    private static final String LOOKUP_VALUE_SOURCE = "mapper/mapping/data/14_source.json";

    private MappingInterpreter interpreter;


    @BeforeEach
    void setUp() {
        interpreter = new AtlasMapInterpreter(MapperTestUtils.objectMapper);
    }

    // TODO: fix this
//    @Test
    void interpretationWithNoBody() {
        File file = MapperTestUtils.getConfigurationFile(CONSTANTS_HEADERS_PROPERTIES_ACTIONS_NO_BODY);
        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(file);
        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);
        assertAll(
                () -> assertNotNull(atlasMapInterpretation)
        );
    }

    // TODO: fix this
//    @Test
    void interpretationJsonToJsonMapping() throws IOException, AtlasException {
        String sourceContent = MapperTestUtils.getJsonContentFromFile(JSON_TO_JSON_SOURCE);

        File configurationFile = MapperTestUtils.getConfigurationFile(JSON_TO_JSON_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        String targetContentExpected = MapperTestUtils.getJsonContentFromFile(JSON_TO_JSON_TARGET);
        String targetContentActual = MapperTestUtils.processJsonMapping(sourceContent, atlasMapInterpretation);

        assertAll(
                () -> assertNotNull(atlasMapInterpretation),
                () -> assertTrue(targetContentActual != null && !(targetContentActual.equals("") || targetContentActual.equals("null"))),
                () -> JSONAssert.assertEquals(targetContentExpected, targetContentActual, false)
        );
    }

    // TODO: fix this
//    @Test
    void interpretationXMLToJsonMapping() throws IOException, AtlasException {
        String sourceContent = MapperTestUtils.getJsonContentFromFile(XML_TO_JSON_SOURCE);

        File configurationFile = MapperTestUtils.getConfigurationFile(XML_TO_JSON_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        String targetContentExpected = MapperTestUtils.getJsonContentFromFile(XML_TO_JSON_TARGET);
        String targetContentActual = MapperTestUtils.processJsonMapping(sourceContent, atlasMapInterpretation);

        assertAll(
                () -> assertNotNull(atlasMapInterpretation)
                , () -> assertTrue(targetContentActual != null && !(targetContentActual.equals("") || targetContentActual.equals("null")))
                , () -> JSONAssert.assertEquals(targetContentExpected, targetContentActual, false)
        );
    }

    // TODO: fix this
//    @Test
    void interpretationXMLToXMLMapping() throws IOException, AtlasException {
        String sourceContent = MapperTestUtils.getJsonContentFromFile(XML_TO_XML_SOURCE);

        File configurationFile = MapperTestUtils.getConfigurationFile(XML_TO_XML_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        String targetContentExpected = MapperTestUtils.getJsonContentFromFile(XML_TO_XML_TARGET);
        String targetContentActual = MapperTestUtils.processXmlMapping(sourceContent, atlasMapInterpretation);

        Diff myDiff = DiffBuilder.compare(Input.fromString(targetContentExpected))
                .withTest(Input.fromString(targetContentActual))
                .checkForSimilar()
                .ignoreWhitespace()
                .build();

        assertAll(
                () -> assertNotNull(atlasMapInterpretation)
                , () -> assertTrue(targetContentActual != null && !(targetContentActual.equals("") || targetContentActual.equals("null")))
                , () -> assertFalse(myDiff.hasDifferences())
        );
    }

    @Test
    void interpretationDefaultValue() throws IOException, AtlasException {
        File configurationFile = MapperTestUtils.getConfigurationFile(DEFAULT_VALUE_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        assertAll(
                () -> assertNotNull(atlasMapInterpretation)
        );
    }

    // TODO: fix this
//    @Test
    void interpretationWithLookup() throws IOException, AtlasException {
        String sourceContent = MapperTestUtils.getJsonContentFromFile(LOOKUP_VALUE_SOURCE);

        File configurationFile = MapperTestUtils.getConfigurationFile(LOOKUP_VALUE_CONFIG);

        MappingDescription mappingDescriptionActual = MapperTestUtils.getMappingFromFile(configurationFile);

        String atlasMapInterpretation = interpreter.getInterpretation(mappingDescriptionActual);

        //String targetContentExpected = MapperTestUtils.getJsonContentFromFile(JSON_TO_JSON_TARGET);
        String targetContentActual = MapperTestUtils.processJsonMapping(sourceContent, atlasMapInterpretation);

        assertAll(
                () -> assertNotNull(atlasMapInterpretation),
                () -> assertTrue(targetContentActual != null && !(targetContentActual.equals("") || targetContentActual.equals("null")))
                //,() -> JSONAssert.assertEquals(targetContentExpected, targetContentActual, false)
        );
    }

}
