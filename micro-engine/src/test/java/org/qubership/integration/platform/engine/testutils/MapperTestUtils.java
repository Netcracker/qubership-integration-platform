package org.qubership.integration.platform.engine.testutils;

import io.atlasmap.expression.Expression;
import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.expression.internal.BooleanExpression;
import io.atlasmap.json.v2.JsonComplexType;
import io.atlasmap.json.v2.JsonEnumField;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.*;
import org.qubership.integration.platform.mapper.ComplexField;

import java.util.ArrayList;
import java.util.List;

import static io.atlasmap.v2.AtlasModelFactory.wrapWithField;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class MapperTestUtils {

    public static final String DOC_ID = "doc1";

    public static Expression dummyExpression(String text) {
        return new Expression() {
            @Override
            public Field evaluate(ExpressionContext expressionContext) {
                throw new UnsupportedOperationException("evaluate should not be called");
            }

            @Override
            public String toString() {
                return text;
            }
        };
    }


    public static void assertBooleanResult(ExpressionContext expressionContext, BooleanExpression expression) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);

        assertNotNull(result);
        assertEquals(true, result.getValue());
    }

    public static void assertBooleanResult(ExpressionContext expressionContext, BooleanExpression expression, boolean expected) throws ExpressionException {
        Field result = expression.evaluate(expressionContext);
        assertEquals(expected, result.getValue());
    }

    public static Expression expressionReturning(ExpressionContext expressionContext, Field field) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(field);
        return expression;
    }

    public static Expression expressionReturningWithWrapping(ExpressionContext expressionContext, Field field) throws ExpressionException {
        Expression expression = mock(Expression.class);
        try {
            when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(field));
        } catch (ExpressionException e) {
            throw new RuntimeException(e);
        }
        return expression;
    }

    public static Expression expressionReturningWithWrapping(ExpressionContext expressionContext, Object value) {
        Expression expression = mock(Expression.class);
        try {
            when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        } catch (ExpressionException e) {
            throw new RuntimeException(e);
        }
        return expression;
    }

    public static Expression expressionReturningValue(ExpressionContext expressionContext, Object value) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        return expression;
    }

    public static Expression expressionResolvingPath(String path) throws ExpressionException {
        Expression expression = mock(Expression.class);
        when(expression.evaluate(any(ExpressionContext.class))).thenAnswer(invocation -> {
            ExpressionContext context = invocation.getArgument(0);
            return context.getVariable(path);
        });
        return expression;
    }

    public static Expression expressionReturningField(ExpressionContext expressionContext, Field field) {
        Expression expression = mock(Expression.class);
        try {
            when(expression.evaluate(expressionContext)).thenReturn(field);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return expression;
    }

    public static BooleanExpression booleanExpressionReturning(ExpressionContext expressionContext, Boolean value) throws ExpressionException {
        BooleanExpression expression = mock(BooleanExpression.class);
        when(expression.evaluate(expressionContext)).thenReturn(wrapWithField(value));
        return expression;
    }

    public static JsonField field(String path) {
        JsonField field = new JsonField();
        field.setDocId("doc1");
        field.setPath(path);
        field.setActions(new ArrayList<>());
        return field;
    }

    public static Field field(Object value, FieldType fieldType) {
        Field field = wrapWithField(value);
        field.setFieldType(fieldType);
        return field;
    }

    public static SimpleField simpleField(String path, Object value) {
        SimpleField field = new SimpleField();
        field.setPath(path);
        field.setValue(value);
        return field;
    }

    public static SimpleField simpleField(String path, String name, FieldType fieldType, Object value) {
        SimpleField field = new SimpleField();
        field.setDocId("doc1");
        field.setPath(path);
        field.setName(name);
        field.setFieldType(fieldType);
        field.setValue(value);
        return field;
    }

    public static SimpleField simpleField(String path) {
        SimpleField field = simpleField(path, "value");
        field.setDocId(DOC_ID);
        return field;
    }

    public static SimpleField simpleFieldWithDocId(String docId, String path, Object value) {
        SimpleField field = simpleField(path, value);
        field.setDocId(docId);
        return field;
    }

    public static SimpleField simpleFieldWithName(String path, String name, Object value) {
        SimpleField field = simpleField(path, value);
        field.setName(name);
        return field;
    }

    public static SimpleField sourceField(String path, String name, Object value, CollectionType collectionType) {
        SimpleField field = simpleFieldWithName(path, name, value);
        field.setCollectionType(collectionType);
        return field;
    }

    public static SimpleField child(String path, String name, Object value) {
        SimpleField field = new SimpleField();
        field.setPath(path);
        field.setName(name);
        field.setValue(value);
        return field;
    }

    public static SimpleField child(String name) {
        return child("/", name, "-value");
    }

    public static SimpleField cloneOf(SimpleField source) {
        SimpleField clone = new SimpleField();
        clone.setPath(source.getPath());
        clone.setName(source.getName());
        clone.setValue(source.getValue());
        return clone;
    }

    public static Field complexField(FieldStatus status) {
        Field field = wrapWithField("complex");
        field.setFieldType(FieldType.COMPLEX);
        field.setStatus(status);
        return field;
    }

    public static ComplexField complexField(String path, Field... children) {
        ComplexField field = new ComplexField();
        field.setDocId("doc1");
        field.setPath(path);
        field.getChildFields().addAll(List.of(children));
        return field;
    }

    public static FieldGroup fieldGroupSimple(Field... fields) {
        FieldGroup group = new FieldGroup();
        group.getField().addAll(List.of(fields));
        return group;
    }

    public static FieldGroup fieldGroup(Field... fields) {
        FieldGroup fieldGroup = new FieldGroup();
        fieldGroup.setPath("/items");
        fieldGroup.setFieldType(FieldType.ANY);
        for (Field field : fields) {
            fieldGroup.getField().add(field);
        }
        return fieldGroup;
    }

    public static FieldGroup fieldGroup(String path, Field... children) {
        FieldGroup group = new FieldGroup();
        group.setDocId(DOC_ID);
        group.setPath(path);
        group.setFieldType(FieldType.COMPLEX);
        group.setCollectionType(CollectionType.NONE);
        group.getField().addAll(List.of(children));
        return group;
    }

    public static FieldGroup collection(Field... items) {
        FieldGroup group = new FieldGroup();
        group.setDocId("doc1");
        group.setPath("/items<>");
        group.getField().addAll(List.of(items));
        return group;
    }

    public static FieldGroup itemWithCollectionKey(String path, String name, int firstKey, int secondKey) {
        FieldGroup item = new FieldGroup();
        item.setDocId("doc1");
        item.setPath(path);
        item.setName(name);

        FieldGroup key = new FieldGroup();
        key.setDocId("doc1");
        key.setPath(path + "/key<>");
        key.setName("key");

        key.getField().add(MapperTestUtils.simpleField(path + "/key<0>", "k0", FieldType.INTEGER, firstKey));
        key.getField().add(MapperTestUtils.simpleField(path + "/key<1>", "k1", FieldType.INTEGER, secondKey));

        item.getField().add(key);
        return item;
    }

    public static JsonField jsonField(String path) {
        return jsonField(path, null);
    }

    public static JsonField jsonField(Field field) {
        assertInstanceOf(JsonField.class, field);
        return (JsonField) field;
    }

    public static JsonField jsonField(String path, FieldType fieldType) {
        JsonField field = new JsonField();
        field.setDocId(DOC_ID);
        field.setPath(path);
        field.setFieldType(fieldType);
        return field;
    }

    public static JsonEnumField jsonEnumField() {
        JsonEnumField field = new JsonEnumField();
        field.setDocId(DOC_ID);
        field.setPath("/status");
        field.setFieldType(FieldType.COMPLEX);
        return field;
    }

    public static JsonComplexType jsonComplexType(Field field) {
        assertInstanceOf(JsonComplexType.class, field);
        return (JsonComplexType) field;
    }

    public static JsonField fieldWithNullActions(String path) {
        JsonField field = new JsonField();
        field.setDocId("doc1");
        field.setPath(path);
        field.setActions(null);
        return field;
    }
}
