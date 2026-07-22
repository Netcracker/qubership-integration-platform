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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.mapper.model.mapping.MappingDescription;
import org.qubership.integration.platform.mapper.model.mapping.action.AttributeKind;
import org.qubership.integration.platform.mapper.model.mapping.action.ElementType;
import org.qubership.integration.platform.mapper.model.mapping.definition.Attribute;
import org.qubership.integration.platform.mapper.model.mapping.definition.MessageSchema;
import org.qubership.integration.platform.mapper.model.mapping.definition.ObjectSchema;
import org.qubership.integration.platform.mapper.model.metadata.Metadata;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MappingDescriptionDeserializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testMetadataDeserialization() throws JsonProcessingException {
        Metadata metadata = objectMapper.readValue("""
                {
                    "foo": "bar",
                    "baz": [ { } ]
                }
                """, Metadata.class);
        assertEquals(Set.of("foo", "baz"), metadata.keySet());
        assertEquals("bar", metadata.get("foo"));
        assertTrue(metadata.get("baz") instanceof List);
        assertEquals(1, ((List<?>) metadata.get("baz")).size());
        assertTrue(((List<?>) metadata.get("baz")).get(0) instanceof Map);
    }

    @Test
    void testNullTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("{\"name\": \"null\"}", org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.NullType);
    }

    @Test
    void testStringTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("{\"name\": \"string\"}", org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testIntegerTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("{\"name\": \"number\"}", org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.IntegerType);
    }

    @Test
    void testBooleanTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("{\"name\": \"boolean\"}", org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.BooleanType);
    }

    @Test
    void testTypeDefinitionDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition typeDefinition = objectMapper.readValue("""
                {
                    "id": "foo",
                    "name": "bar",
                    "type": { "name": "string" }
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition.class);
        assertEquals("foo", typeDefinition.getId());
        assertEquals("bar", typeDefinition.getName());
        assertTrue(typeDefinition.getType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testArrayTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "array",
                    "itemType": { "name": "string" }
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.ArrayType);
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.ArrayType) type).getItemType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.ArrayType) type).getDefinitions().isEmpty());
    }

    @Test
    void testDeserializationOfArrayTypeWithDefinitions() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "array",
                    "itemType": { "name": "string" },
                    "definitions": [
                        {
                            "id": "foo",
                            "name": "bar",
                            "type": { "name": "string" }
                        }
                    ]
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.ArrayType);
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.ArrayType) type).getItemType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
        assertEquals(1, ((org.qubership.integration.platform.mapper.model.datatypes.ArrayType) type).getDefinitions().size());

        org.qubership.integration.platform.mapper.model.datatypes.TypeDefinition typeDefinition = ((org.qubership.integration.platform.mapper.model.datatypes.ArrayType) type).getDefinitions().iterator().next();
        assertEquals("foo", typeDefinition.getId());
        assertEquals("bar", typeDefinition.getName());
        assertTrue(typeDefinition.getType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testAttributeDeserialization() throws JsonProcessingException {
        Attribute attribute = objectMapper.readValue("""
                {
                    "id": "foo",
                    "name": "bar",
                    "type": { "name":  "string" }
                }
                """, Attribute.class);
        assertEquals("foo", attribute.getId());
        assertEquals("bar", attribute.getName());
        assertTrue(attribute.getType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testObjectSchemaDeserialization() throws JsonProcessingException {
        ObjectSchema schema = objectMapper.readValue("""
                {
                    "id": "foo",
                    "attributes": [
                        { "id": "bar", "name": "baz", "type": { "name":  "string" } }
                    ]
                }
                """, ObjectSchema.class);
        assertEquals("foo", schema.getId());
        assertEquals(1, schema.getAttributes().size());
        assertEquals("bar", schema.getAttributes().iterator().next().getId());
    }

    @Test
    void testObjectTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "object",
                    "schema": { "id": "foo", "attributes": [] }
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.ObjectType);
        assertEquals("foo", ((org.qubership.integration.platform.mapper.model.datatypes.ObjectType) type).getSchema().getId());
    }

    @Test
    void testReferenceTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "reference",
                    "definitionId": "foo"
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.ReferenceType);
        assertEquals("foo", ((org.qubership.integration.platform.mapper.model.datatypes.ReferenceType) type).getDefinitionId());
    }

    @Test
    void testAnyOfTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "anyOf",
                    "types": [{ "name": "string" }]
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.AnyOfType);
        assertEquals(1, ((org.qubership.integration.platform.mapper.model.datatypes.AnyOfType) type).getTypes().size());
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.AnyOfType) type).getTypes().iterator().next() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testAllOfTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "allOf",
                    "types": [{ "name": "string" }]
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.AllOfType);
        assertEquals(1, ((org.qubership.integration.platform.mapper.model.datatypes.AllOfType) type).getTypes().size());
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.AllOfType) type).getTypes().iterator().next() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testOneOfTypeDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.datatypes.DataType type = objectMapper.readValue("""
                {
                    "name": "oneOf",
                    "types": [{ "name": "string" }]
                }
                """, org.qubership.integration.platform.mapper.model.datatypes.DataType.class);
        assertTrue(type instanceof org.qubership.integration.platform.mapper.model.datatypes.OneOfType);
        assertEquals(1, ((org.qubership.integration.platform.mapper.model.datatypes.OneOfType) type).getTypes().size());
        assertTrue(((org.qubership.integration.platform.mapper.model.datatypes.OneOfType) type).getTypes().iterator().next() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
    }

    @Test
    void testGivenValueDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueSupplier supplier = objectMapper.readValue("""
                {
                    "kind": "given",
                    "value": "foo"
                }
                """, org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueSupplier.class);
        assertTrue(supplier instanceof org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueSupplier);
        assertEquals("foo", ((org.qubership.integration.platform.mapper.model.mapping.definition.constant.GivenValue) supplier).getValue());
    }

    @Test
    void testValueGeneratorDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueGenerator generator = objectMapper.readValue("""
                {
                    "name": "foo",
                    "parameters": ["bar", "baz", "biz"]
                }
                """, org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueGenerator.class);
        assertEquals("foo", generator.getName());
        assertEquals(List.of("bar", "baz", "biz"), generator.getParameters());
    }

    @Test
    void testGeneratedValueDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueSupplier supplier = objectMapper.readValue("""
                {
                    "kind": "generated",
                    "generator": { "name": "foo", "parameters": [] }
                }
                """, org.qubership.integration.platform.mapper.model.mapping.definition.constant.ValueSupplier.class);
        assertTrue(supplier instanceof org.qubership.integration.platform.mapper.model.mapping.definition.constant.GeneratedValue);
        assertEquals("foo", ((org.qubership.integration.platform.mapper.model.mapping.definition.constant.GeneratedValue) supplier).getGenerator().getName());
    }

    @Test
    void testConstantDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.definition.constant.Constant constant = objectMapper.readValue("""
                {
                    "id": "foo",
                    "name": "bar",
                    "type": { "name":  "string" },
                    "valueSupplier": { "kind": "given", "value": "baz" }
                }
                """, org.qubership.integration.platform.mapper.model.mapping.definition.constant.Constant.class);
        assertEquals("foo", constant.getId());
        assertEquals("bar", constant.getName());
        assertTrue(constant.getType() instanceof org.qubership.integration.platform.mapper.model.datatypes.StringType);
        assertTrue(constant.getValueSupplier() instanceof org.qubership.integration.platform.mapper.model.mapping.definition.constant.GivenValue);
        assertEquals("baz", ((org.qubership.integration.platform.mapper.model.mapping.definition.constant.GivenValue) constant.getValueSupplier()).getValue());
    }

    @Test
    void testMessageSchemaDeserialization() throws JsonProcessingException {
        MessageSchema messageSchema = objectMapper.readValue("""
                {
                    "headers": [{ "id": "foo", "name": "bar", "type": { "name": "string" } }],
                    "properties": [{ "id": "baz", "name": "biz", "type": { "name": "boolean" } }],
                    "body": { "name": "number" }
                }
                """, MessageSchema.class);
        assertEquals(1, messageSchema.getHeaders().size());
        assertEquals("foo", messageSchema.getHeaders().iterator().next().getId());
        assertEquals(1, messageSchema.getProperties().size());
        assertEquals("baz", messageSchema.getProperties().iterator().next().getId());
        assertTrue(messageSchema.getBody() instanceof org.qubership.integration.platform.mapper.model.datatypes.IntegerType);
    }

    @Test
    void testTransformationDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.action.Transformation transformation = objectMapper.readValue("""
                {
                    "name": "foo",
                    "parameters": ["bar", "baz", "biz"]
                }
                """, org.qubership.integration.platform.mapper.model.mapping.action.Transformation.class);
        assertEquals("foo", transformation.getName());
        assertEquals(List.of("bar", "baz", "biz"), transformation.getParameters());
    }

    @Test
    void testConstantReferenceDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.action.ElementReference elementReference = objectMapper.readValue("""
                {
                    "type": "constant",
                    "constantId": "foo"
                }
                """, org.qubership.integration.platform.mapper.model.mapping.action.ElementReference.class);
        assertTrue(elementReference instanceof org.qubership.integration.platform.mapper.model.mapping.action.ConstantReference);
        assertEquals("foo", ((org.qubership.integration.platform.mapper.model.mapping.action.ConstantReference) elementReference).getConstantId());
    }

    @Test
    void testAttributeReferenceDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.action.ElementReference elementReference = objectMapper.readValue("""
                {
                    "type": "attribute",
                    "kind": "header",
                    "path": ["foo", "bar"]
                }
                """, org.qubership.integration.platform.mapper.model.mapping.action.ElementReference.class);
        assertTrue(elementReference instanceof org.qubership.integration.platform.mapper.model.mapping.action.AttributeReference);
        assertEquals(AttributeKind.HEADER, ((org.qubership.integration.platform.mapper.model.mapping.action.AttributeReference) elementReference).getKind());
        assertEquals(List.of("foo", "bar"), ((org.qubership.integration.platform.mapper.model.mapping.action.AttributeReference) elementReference).getPath());
    }

    @Test
    void testMappingActionDeserialization() throws JsonProcessingException {
        org.qubership.integration.platform.mapper.model.mapping.action.MappingAction action = objectMapper.readValue("""
                {
                    "id": "fizz",
                    "sources": [{ "type": "constant", "constantId": "foo" }],
                    "target": { "type": "attribute", "kind": "property", "path": ["bar"] },
                    "transformation": { "name": "baz", "parameters": [] }
                }
                """, org.qubership.integration.platform.mapper.model.mapping.action.MappingAction.class);
        assertEquals("fizz", action.getId());
        assertEquals(1, action.getSources().size());
        assertEquals(ElementType.CONSTANT, action.getSources().get(0).getType());
        assertEquals(Collections.singletonList("bar"), action.getTarget().getPath());
        assertEquals("baz", action.getTransformation().getName());
    }

    @Test
    void testMappingDescriptionDeserialization() throws JsonProcessingException {
        MappingDescription mappingDescription = objectMapper.readValue("""
                {
                    "source": { "headers": [], "properties": [], "body": { "name": "null" } },
                    "target": { "headers": [], "properties": [], "body": { "name": "boolean" } },
                    "constants": [],
                    "actions": []
                }
                """, MappingDescription.class);
        assertTrue(mappingDescription.getSource().getBody() instanceof org.qubership.integration.platform.mapper.model.datatypes.NullType);
        assertTrue(mappingDescription.getTarget().getBody() instanceof org.qubership.integration.platform.mapper.model.datatypes.BooleanType);
    }
}
