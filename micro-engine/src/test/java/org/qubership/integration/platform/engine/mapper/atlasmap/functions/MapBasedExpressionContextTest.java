package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.SimpleField;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MapBasedExpressionContextTest {

    @Test
    void shouldReturnVariableFromMap() throws Exception {
        SimpleField field = MapperTestUtils.simpleFieldWithDocId("doc1", "/root/value", "value");
        MapBasedExpressionContext context = new MapBasedExpressionContext(Map.of("doc1:/root/value", field));

        Field result = context.getVariable("doc1:/root/value");

        assertSame(field, result);
    }

    @Test
    void shouldReturnNullWhenVariableIsMissing() throws Exception {
        MapBasedExpressionContext context = new MapBasedExpressionContext(Map.of());

        Field result = context.getVariable("doc1:/missing");

        assertNull(result);
    }

    @Test
    void shouldCreateContextFromSimpleField() throws Exception {
        SimpleField field = MapperTestUtils.simpleFieldWithDocId("doc1", "/root/value", "value");

        ExpressionContext context = MapBasedExpressionContext.fromField(field, Function.identity());

        Field result = context.getVariable("doc1:/root/value");

        assertSame(field, result);
    }

    @Test
    void shouldCreateFieldMapForFieldGroupRecursively() {
        SimpleField root = MapperTestUtils.simpleFieldWithDocId("doc1", "/group", "root");
        FieldGroup group = new FieldGroup();
        group.setDocId(root.getDocId());
        group.setPath(root.getPath());

        SimpleField child1 = MapperTestUtils.simpleFieldWithDocId("doc1", "/group/a", "A");
        SimpleField child2 = MapperTestUtils.simpleFieldWithDocId("doc1", "/group/b", "B");
        group.getField().add(child1);
        group.getField().add(child2);

        Map<String, Field> result = MapBasedExpressionContext.createFieldMap(group, Function.identity());

        assertEquals(3, result.size());
        assertSame(group, result.get("doc1:/group"));
        assertSame(child1, result.get("doc1:/group/a"));
        assertSame(child2, result.get("doc1:/group/b"));
    }

    @Test
    void shouldCreateFieldMapForComplexFieldRecursively() {
        SimpleField child1 = MapperTestUtils.simpleFieldWithDocId("doc1", "/root/id", "1");
        SimpleField child2 = MapperTestUtils.simpleFieldWithDocId("doc1", "/root/name", "Bob");

        ComplexField complexField = new ComplexField(List.of(child1, child2));
        complexField.setDocId("doc1");
        complexField.setPath("/root");

        Map<String, Field> result = MapBasedExpressionContext.createFieldMap(complexField, Function.identity());

        assertEquals(3, result.size());
        assertSame(complexField, result.get("doc1:/root"));
        assertSame(child1, result.get("doc1:/root/id"));
        assertSame(child2, result.get("doc1:/root/name"));
    }

    @Test
    void shouldApplyPathMapperWhenBuildingVariableNames() {
        SimpleField field = MapperTestUtils.simpleFieldWithDocId("doc1", "/source/value", "value");

        Map<String, Field> result = MapBasedExpressionContext.createFieldMap(field, path -> path.replace("/source", "/target"));

        assertEquals(1, result.size());
        assertSame(field, result.get("doc1:/target/value"));
    }

    @Test
    void shouldApplyPathMapperRecursivelyForNestedFields() {
        FieldGroup group = new FieldGroup();
        group.setDocId("doc1");
        group.setPath("/source");

        SimpleField child = MapperTestUtils.simpleFieldWithDocId("doc1", "/source/value", "value");
        group.getField().add(child);

        Map<String, Field> result = MapBasedExpressionContext.createFieldMap(group, path -> path.replace("/source", "/mapped"));

        assertEquals(2, result.size());
        assertSame(group, result.get("doc1:/mapped"));
        assertSame(child, result.get("doc1:/mapped/value"));
    }
}
