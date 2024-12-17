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

package org.qubership.integration.platform.engine.mapper.atlasmap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.qubership.integration.platform.mapper.ComplexField;
import io.atlasmap.api.AtlasContext;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.DefaultAtlasContextFactory;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

class ComplexMappingTest {
    private static final String XML_SOURCE = """
            <document>
                <data>
                    <key>The answer to the ultimate question of life, the universe and everything</key>
                    <value>42</value>
                    <metadata>
                        <reference>The Hitchhiker's Guide to the Galaxy</reference>
                        <tags>42</tags>
                        <tags>meaning of life</tags>
                        <tags>Douglas Adams</tags>
                    </metadata>
                </data>
            </document>
            """;

    private static final String JSON_SOURCE = """
            {
                "data": {
                    "key": "The answer to the ultimate question of life, the universe and everything",
                    "value": "42",
                    "metadata": {
                        "reference": "The Hitchhiker's Guide to the Galaxy",
                        "tags": ["42", "meaning of life", "Douglas Adams"]
                    }
                }
            }
            """;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SOURCE_ID = "source-ds-id";

    private static final String TARGET_ID = "target-ds-id";

    private enum Format {
        JSON,
        XML
    }

    private record MapResult(String document, List<Audit> audits) {}

    private MapResult doMap(
            Format sourceFormat,
            Format targetFormat,
            Mappings mappings
    ) throws Exception {
        AtlasMapping atlasMapping = new AtlasMapping();
        atlasMapping.setName("testObjectMapping");

        DataSource sourceDataSource = new DataSource();
        sourceDataSource.setId(SOURCE_ID);
        sourceDataSource.setName("source-ds");
        sourceDataSource.setDescription("");
        sourceDataSource.setUri("atlas:cip:" + sourceFormat.name().toLowerCase() + ":" + SOURCE_ID);
        sourceDataSource.setDataSourceType(DataSourceType.SOURCE);

        DataSource targetDataSource = new DataSource();
        targetDataSource.setId(TARGET_ID);
        targetDataSource.setName("target-ds");
        targetDataSource.setDescription("");
        targetDataSource.setUri("atlas:cip:" + targetFormat.name().toLowerCase() + ":" + TARGET_ID);
        targetDataSource.setDataSourceType(DataSourceType.TARGET);

        atlasMapping.getDataSource().add(sourceDataSource);
        atlasMapping.getDataSource().add(targetDataSource);

        atlasMapping.setMappings(mappings);

        DefaultAtlasContextFactory contextFactory = DefaultAtlasContextFactory.getInstance();

        AtlasContext context = contextFactory.createContext(atlasMapping);
        AtlasSession session = context.createSession();
        session.setDefaultSourceDocument(sourceFormat.equals(Format.JSON) ? JSON_SOURCE : XML_SOURCE);
        context.process(session);
        Object target = session.getTargetDocument(TARGET_ID);

        return new MapResult(target.toString(), session.getAudits().getAudit());
    }

    private void assertThereIsNoErrors(List<Audit> audits) {
        List<Audit> errors = audits.stream().filter(audit -> audit.getStatus().equals(AuditStatus.ERROR)).toList();
        assertTrue(errors.isEmpty(), errors.stream().map(audit -> audit.getPath() + ": " + audit.getMessage())
                .collect(Collectors.joining(System.lineSeparator())));
    }

    private MapResult doMappingWithoutSchema(Format sourceFormat, Format targetFormat) throws Exception {
        Mappings mappings = new Mappings();

        Mapping mapping = new Mapping();
        mapping.setId("mapping-id");

        Field inputField = new ComplexField();
        inputField.setName("data");
        inputField.setPath("/data");
        inputField.setFieldType(FieldType.ANY);
        inputField.setDocId(SOURCE_ID);

        mapping.getInputField().add(inputField);

        Field outputField = new ComplexField();
        outputField.setName("message");
        outputField.setPath("/result/message");
        outputField.setFieldType(FieldType.ANY);
        outputField.setDocId(TARGET_ID);

        mapping.getOutputField().add(outputField);

        mappings.getMapping().add(mapping);

        MapResult result = doMap(sourceFormat, targetFormat, mappings);

        assertThereIsNoErrors(result.audits());

        return result;
    }

    @Test
    void testJsonToJsonMapping() throws Exception {
        MapResult result = doMappingWithoutSchema(Format.JSON, Format.JSON);

        String expected = """
                {
                    "result": {
                        "message": {
                            "key": "The answer to the ultimate question of life, the universe and everything",
                            "value": "42",
                            "metadata": {
                                "reference": "The Hitchhiker's Guide to the Galaxy",
                                "tags": ["42", "meaning of life", "Douglas Adams"]
                            }
                        }
                    }
                }
                """;
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(result.document()));
    }

    @Test
    void testJsonToXmlMapping() throws Exception {
        MapResult result = doMappingWithoutSchema(Format.JSON, Format.XML);

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
                "<result><message><key>The answer to the ultimate question of life, the universe and everything</key>" +
                "<value>42</value><metadata><reference>The Hitchhiker's Guide to the Galaxy</reference>" +
                "<tags>42</tags><tags>meaning of life</tags><tags>Douglas Adams</tags></metadata></message></result>",
                result.document());
    }

    @Test
    void testXmlToJsonMapping() throws Exception {
        MapResult result = doMappingWithoutSchema(Format.XML, Format.JSON);

        String expected = """
                {
                    "result": {
                        "message": {
                            "key": "The answer to the ultimate question of life, the universe and everything",
                            "value": "42",
                            "metadata": {
                                "reference": "The Hitchhiker's Guide to the Galaxy",
                                "tags": ["42", "meaning of life", "Douglas Adams"]
                            }
                        }
                    }
                }
                """;
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(result.document()));

    }

    @Test
    void testXmlToXmlMapping() throws Exception {
        MapResult result = doMappingWithoutSchema(Format.XML, Format.XML);

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
                "<result><message><key>The answer to the ultimate question of life, the universe and everything</key>" +
                "<value>42</value><metadata><reference>The Hitchhiker's Guide to the Galaxy</reference>" +
                "<tags>42</tags><tags>meaning of life</tags><tags>Douglas Adams</tags></metadata></message></result>",
                result.document());
    }

    @Test
    void testMappingWithSourceSchema() throws Exception {
        Mappings mappings = new Mappings();

        Mapping mapping = new Mapping();
        mapping.setId("mapping-id");

        Field key = new JsonField();
        key.setName("key");
        key.setPath("/data/key");
        key.setFieldType(FieldType.STRING);
        key.setDocId(SOURCE_ID);

        Field value = new JsonField();
        value.setName("value");
        value.setPath("/data/value");
        value.setFieldType(FieldType.STRING);
        value.setDocId(SOURCE_ID);

        FieldGroup metadata = new FieldGroup();
        metadata.setName("metadata");
        metadata.setPath("/data/metadata");
        metadata.setFieldType(FieldType.COMPLEX);
        metadata.setDocId(SOURCE_ID);

        Field tags = new JsonField();
        tags.setName("tags");
        tags.setPath("/data/metadata/tags<>");
        tags.setCollectionType(CollectionType.LIST);
        tags.setFieldType(FieldType.STRING);
        tags.setDocId(SOURCE_ID);

        metadata.getField().add(tags);

        Field inputField = new ComplexField(List.of(key, value, metadata));
        inputField.setName("data");
        inputField.setPath("/data");
        inputField.setFieldType(FieldType.ANY);
        inputField.setDocId(SOURCE_ID);

        mapping.getInputField().add(inputField);

        Field outputField = new ComplexField();
        outputField.setName("message");
        outputField.setPath("/result/message");
        outputField.setFieldType(FieldType.ANY);
        outputField.setDocId(TARGET_ID);

        mapping.getOutputField().add(outputField);

        mappings.getMapping().add(mapping);

        MapResult result = doMap(Format.JSON, Format.JSON, mappings);

        assertThereIsNoErrors(result.audits());

        String expected = """
                {
                    "result": {
                        "message": {
                            "key": "The answer to the ultimate question of life, the universe and everything",
                            "value": "42",
                            "metadata": {
                                "tags": ["42", "meaning of life", "Douglas Adams"]
                            }
                        }
                    }
                }
                """;
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(result.document()));
    }

    @Test
    void testMappingWithTargetSchema() throws Exception {
        Mappings mappings = new Mappings();

        Mapping mapping = new Mapping();
        mapping.setId("mapping-id");

        Field inputField = new ComplexField();
        inputField.setName("data");
        inputField.setPath("/data");
        inputField.setFieldType(FieldType.ANY);
        inputField.setDocId(SOURCE_ID);

        mapping.getInputField().add(inputField);

        Field value = new JsonField();
        value.setName("value");
        value.setPath("/result/message/value");
        value.setFieldType(FieldType.NUMBER);
        value.setDocId(SOURCE_ID);

        FieldGroup metadata = new FieldGroup();
        metadata.setName("metadata");
        metadata.setPath("/result/message/metadata");
        metadata.setFieldType(FieldType.COMPLEX);
        metadata.setDocId(TARGET_ID);

        Field reference = new JsonField();
        reference.setName("reference");
        reference.setPath("/result/message/metadata/reference");
        reference.setFieldType(FieldType.STRING);
        reference.setDocId(TARGET_ID);

        metadata.getField().add(reference);

        Field outputField = new ComplexField(List.of(value, metadata));
        outputField.setName("message");
        outputField.setPath("/result/message");
        outputField.setFieldType(FieldType.ANY);
        outputField.setDocId(TARGET_ID);

        mapping.getOutputField().add(outputField);

        mappings.getMapping().add(mapping);

        MapResult result = doMap(Format.JSON, Format.JSON, mappings);

        assertThereIsNoErrors(result.audits());

        String expected = """
                {
                    "result": {
                        "message": {
                            "value":42,
                            "metadata": { "reference":"The Hitchhiker's Guide to the Galaxy" }
                        }
                    }
                }
                """;
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(result.document()));
    }

    @Test
    void testMappingScalars() throws Exception {
        Mappings mappings = new Mappings();

        Mapping mapping = new Mapping();
        mapping.setId("mapping-id");

        Field inputField = new ComplexField();
        inputField.setName("reference");
        inputField.setPath("/data/metadata/reference");
        inputField.setFieldType(FieldType.ANY);
        inputField.setDocId(SOURCE_ID);

        mapping.getInputField().add(inputField);

        Field outputField = new ComplexField();
        outputField.setName("message");
        outputField.setPath("/result/message");
        outputField.setFieldType(FieldType.ANY);
        outputField.setDocId(TARGET_ID);

        mapping.getOutputField().add(outputField);

        mappings.getMapping().add(mapping);

        MapResult result = doMap(Format.JSON, Format.JSON, mappings);

        assertThereIsNoErrors(result.audits());

        String expected = """
                {
                    "result": {
                        "message": "The Hitchhiker's Guide to the Galaxy"
                    }
                }
                """;
        assertEquals(objectMapper.readTree(expected), objectMapper.readTree(result.document()));
    }
}