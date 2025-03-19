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
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.MappingDescription;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.action.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.MessageSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.ObjectSchema;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.constant.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

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
        DataType type = objectMapper.readValue("{\"name\": \"null\"}", DataType.class);
        assertTrue(type instanceof NullType);
    }

    @Test
    void testStringTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("{\"name\": \"string\"}", DataType.class);
        assertTrue(type instanceof StringType);
    }

    @Test
    void testIntegerTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("{\"name\": \"number\"}", DataType.class);
        assertTrue(type instanceof IntegerType);
    }

    @Test
    void testBooleanTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("{\"name\": \"boolean\"}", DataType.class);
        assertTrue(type instanceof BooleanType);
    }

    @Test
    void testTypeDefinitionDeserialization() throws JsonProcessingException {
        TypeDefinition typeDefinition = objectMapper.readValue("""
                {
                    "id": "foo",
                    "name": "bar",
                    "type": { "name": "string" }
                }
                """, TypeDefinition.class);
        assertEquals("foo", typeDefinition.getId());
        assertEquals("bar", typeDefinition.getName());
        assertTrue(typeDefinition.getType() instanceof StringType);
    }

    @Test
    void testArrayTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
                {
                    "name": "array",
                    "itemType": { "name": "string" }
                }
                """, DataType.class);
        assertTrue(type instanceof ArrayType);
        assertTrue(((ArrayType) type).getItemType() instanceof StringType);
        assertTrue(((ArrayType) type).getDefinitions().isEmpty());
    }

    @Test
    void testDeserializationOfArrayTypeWithDefinitions() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
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
                """, DataType.class);
        assertTrue(type instanceof ArrayType);
        assertTrue(((ArrayType) type).getItemType() instanceof StringType);
        assertEquals(1, ((ArrayType) type).getDefinitions().size());

        TypeDefinition typeDefinition = ((ArrayType) type).getDefinitions().iterator().next();
        assertEquals("foo", typeDefinition.getId());
        assertEquals("bar", typeDefinition.getName());
        assertTrue(typeDefinition.getType() instanceof StringType);
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
        assertTrue(attribute.getType() instanceof StringType);
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
        DataType type = objectMapper.readValue("""
                {
                    "name": "object",
                    "schema": { "id": "foo", "attributes": [] }
                }
                """, DataType.class);
        assertTrue(type instanceof ObjectType);
        assertEquals("foo", ((ObjectType) type).getSchema().getId());
    }

    @Test
    void testReferenceTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
                {
                    "name": "reference",
                    "definitionId": "foo"
                }
                """, DataType.class);
        assertTrue(type instanceof ReferenceType);
        assertEquals("foo", ((ReferenceType) type).getDefinitionId());
    }

    @Test
    void testAnyOfTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
                {
                    "name": "anyOf",
                    "types": [{ "name": "string" }]
                }
                """, DataType.class);
        assertTrue(type instanceof AnyOfType);
        assertEquals(1, ((AnyOfType) type).getTypes().size());
        assertTrue(((AnyOfType) type).getTypes().iterator().next() instanceof StringType);
    }

    @Test
    void testAllOfTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
                {
                    "name": "allOf",
                    "types": [{ "name": "string" }]
                }
                """, DataType.class);
        assertTrue(type instanceof AllOfType);
        assertEquals(1, ((AllOfType) type).getTypes().size());
        assertTrue(((AllOfType) type).getTypes().iterator().next() instanceof StringType);
    }

    @Test
    void testOneOfTypeDeserialization() throws JsonProcessingException {
        DataType type = objectMapper.readValue("""
                {
                    "name": "oneOf",
                    "types": [{ "name": "string" }]
                }
                """, DataType.class);
        assertTrue(type instanceof OneOfType);
        assertEquals(1, ((OneOfType) type).getTypes().size());
        assertTrue(((OneOfType) type).getTypes().iterator().next() instanceof StringType);
    }

    @Test
    void testGivenValueDeserialization() throws JsonProcessingException {
        ValueSupplier supplier = objectMapper.readValue("""
                {
                    "kind": "given",
                    "value": "foo"
                }
                """, ValueSupplier.class);
        assertTrue(supplier instanceof ValueSupplier);
        assertEquals("foo", ((GivenValue) supplier).getValue());
    }

    @Test
    void testValueGeneratorDeserialization() throws JsonProcessingException {
        ValueGenerator generator = objectMapper.readValue("""
                {
                    "name": "foo",
                    "parameters": ["bar", "baz", "biz"]
                }
                """, ValueGenerator.class);
        assertEquals("foo", generator.getName());
        assertEquals(List.of("bar", "baz", "biz"), generator.getParameters());
    }

    @Test
    void testGeneratedValueDeserialization() throws JsonProcessingException {
        ValueSupplier supplier = objectMapper.readValue("""
                {
                    "kind": "generated",
                    "generator": { "name": "foo", "parameters": [] }
                }
                """, ValueSupplier.class);
        assertTrue(supplier instanceof GeneratedValue);
        assertEquals("foo", ((GeneratedValue) supplier).getGenerator().getName());
    }

    @Test
    void testConstantDeserialization() throws JsonProcessingException {
        Constant constant = objectMapper.readValue("""
                {
                    "id": "foo",
                    "name": "bar",
                    "type": { "name":  "string" },
                    "valueSupplier": { "kind": "given", "value": "baz" }
                }
                """, Constant.class);
        assertEquals("foo", constant.getId());
        assertEquals("bar", constant.getName());
        assertTrue(constant.getType() instanceof StringType);
        assertTrue(constant.getValueSupplier() instanceof GivenValue);
        assertEquals("baz", ((GivenValue) constant.getValueSupplier()).getValue());
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
        assertTrue(messageSchema.getBody() instanceof IntegerType);
    }

    @Test
    void testTransformationDeserialization() throws JsonProcessingException {
        Transformation transformation = objectMapper.readValue("""
                {
                    "name": "foo",
                    "parameters": ["bar", "baz", "biz"]
                }
                """, Transformation.class);
        assertEquals("foo", transformation.getName());
        assertEquals(List.of("bar", "baz", "biz"), transformation.getParameters());
    }

    @Test
    void testConstantReferenceDeserialization() throws JsonProcessingException {
        ElementReference elementReference = objectMapper.readValue("""
                {
                    "type": "constant",
                    "constantId": "foo"
                }
                """, ElementReference.class);
        assertTrue(elementReference instanceof ConstantReference);
        assertEquals("foo", ((ConstantReference) elementReference).getConstantId());
    }

    @Test
    void testAttributeReferenceDeserialization() throws JsonProcessingException {
        ElementReference elementReference = objectMapper.readValue("""
                {
                    "type": "attribute",
                    "kind": "header",
                    "path": ["foo", "bar"]
                }
                """, ElementReference.class);
        assertTrue(elementReference instanceof AttributeReference);
        assertEquals(AttributeKind.HEADER, ((AttributeReference) elementReference).getKind());
        assertEquals(List.of("foo", "bar"), ((AttributeReference) elementReference).getPath());
    }

    @Test
    void testMappingActionDeserialization() throws JsonProcessingException {
        MappingAction action = objectMapper.readValue("""
                {
                    "id": "fizz",
                    "sources": [{ "type": "constant", "constantId": "foo" }],
                    "target": { "type": "attribute", "kind": "property", "path": ["bar"] },
                    "transformation": { "name": "baz", "parameters": [] }
                }
                """, MappingAction.class);
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
        assertTrue(mappingDescription.getSource().getBody() instanceof NullType);
        assertTrue(mappingDescription.getTarget().getBody() instanceof BooleanType);
    }
}
