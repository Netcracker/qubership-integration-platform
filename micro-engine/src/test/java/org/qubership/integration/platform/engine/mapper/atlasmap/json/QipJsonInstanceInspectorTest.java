package org.qubership.integration.platform.engine.mapper.atlasmap.json;

import io.atlasmap.json.inspect.JsonInspectionException;
import io.atlasmap.json.v2.JsonComplexType;
import io.atlasmap.json.v2.JsonDocument;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldStatus;
import io.atlasmap.v2.FieldType;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipJsonInstanceInspectorTest {

    private final QipJsonInstanceInspector inspector = new QipJsonInstanceInspector();

    @Test
    void shouldThrowWhenInspectWithNullInstance() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> inspector.inspect(null)
        );

        assertEquals("JSON instance cannot be null", exception.getMessage());
    }

    @Test
    void shouldThrowWhenInspectWithEmptyInstance() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> inspector.inspect("")
        );

        assertEquals("JSON instance cannot be null", exception.getMessage());
    }

    @Test
    void shouldThrowWhenRootIsScalar() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> inspector.inspect("\"value\"")
        );

        assertEquals("JSON root must be object or array", exception.getMessage());
    }

    @Test
    void shouldWrapIOExceptionWhenJsonIsInvalid() {
        JsonInspectionException exception = assertThrows(
                JsonInspectionException.class,
                () -> inspector.inspect("{")
        );

        assertNotNull(exception.getCause());
    }

    @Test
    void shouldInspectFlatObjectWithSupportedScalarTypes() throws Exception {
        JsonDocument result = inspector.inspect(
                "{\"name\":\"Harry\",\"age\":30,\"active\":true,\"amount\":1.5,\"longValue\":2147483648,\"bigValue\":9223372036854775808}"
        );

        assertNotNull(result);
        assertNotNull(result.getFields());
        assertEquals(6, result.getFields().getField().size());

        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(0)),
                "name",
                "/name",
                FieldType.STRING,
                "Harry"
        );
        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(1)),
                "age",
                "/age",
                FieldType.INTEGER,
                30
        );
        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(2)),
                "active",
                "/active",
                FieldType.BOOLEAN,
                Boolean.TRUE
        );
        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(3)),
                "amount",
                "/amount",
                FieldType.DOUBLE,
                1.5d
        );
        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(4)),
                "longValue",
                "/longValue",
                FieldType.LONG,
                2147483648L
        );
        assertSupportedJsonField(
                MapperTestUtils.jsonField(result.getFields().getField().get(5)),
                "bigValue",
                "/bigValue",
                FieldType.BIG_INTEGER,
                new BigInteger("9223372036854775808")
        );
    }

    @Test
    void shouldInspectNestedObject() throws Exception {
        JsonDocument result = inspector.inspect("{\"customer\":{\"name\":\"Harry\",\"active\":true}}");

        assertNotNull(result);
        assertEquals(1, result.getFields().getField().size());

        JsonComplexType customer = MapperTestUtils.jsonComplexType(result.getFields().getField().get(0));
        assertEquals("customer", customer.getName());
        assertEquals("/customer", customer.getPath());
        assertEquals(FieldStatus.SUPPORTED, customer.getStatus());
        assertNotNull(customer.getJsonFields());
        assertEquals(2, customer.getJsonFields().getJsonField().size());

        JsonField name = MapperTestUtils.jsonField(customer.getJsonFields().getJsonField().get(0));
        JsonField active = MapperTestUtils.jsonField(customer.getJsonFields().getJsonField().get(1));

        assertEquals("name", name.getName());
        assertEquals("/customer/name", name.getPath());
        assertEquals(FieldType.STRING, name.getFieldType());
        assertEquals("Harry", name.getValue());

        assertEquals("active", active.getName());
        assertEquals("/customer/active", active.getPath());
        assertEquals(FieldType.BOOLEAN, active.getFieldType());
        assertEquals(Boolean.TRUE, active.getValue());
    }

    @Test
    void shouldInspectRootArrayOfObjects() throws Exception {
        JsonDocument result = inspector.inspect("[{\"id\":10,\"name\":\"first\"}]");

        assertNotNull(result);
        assertEquals(1, result.getFields().getField().size());

        JsonComplexType item = MapperTestUtils.jsonComplexType(result.getFields().getField().get(0));
        assertEquals("", item.getName());
        assertEquals("/<>", item.getPath());
        assertEquals(CollectionType.LIST, item.getCollectionType());
        assertEquals(FieldStatus.SUPPORTED, item.getStatus());
        assertEquals(2, item.getJsonFields().getJsonField().size());

        JsonField id = MapperTestUtils.jsonField(item.getJsonFields().getJsonField().get(0));
        JsonField name = MapperTestUtils.jsonField(item.getJsonFields().getJsonField().get(1));

        assertEquals("/<>/id", id.getPath());
        assertEquals(FieldType.INTEGER, id.getFieldType());
        assertEquals(10, id.getValue());

        assertEquals("/<>/name", name.getPath());
        assertEquals(FieldType.STRING, name.getFieldType());
        assertEquals("first", name.getValue());
    }

    @Test
    void shouldInspectArrayOfScalarValues() throws Exception {
        JsonDocument result = inspector.inspect("{\"tags\":[\"a\",\"b\"]}");

        assertNotNull(result);
        assertEquals(2, result.getFields().getField().size());

        JsonField first = MapperTestUtils.jsonField(result.getFields().getField().get(0));
        JsonField second = MapperTestUtils.jsonField(result.getFields().getField().get(1));

        assertEquals("tags", first.getName());
        assertEquals("/tags<>", first.getPath());
        assertEquals(CollectionType.LIST, first.getCollectionType());
        assertEquals(FieldType.STRING, first.getFieldType());
        assertEquals(FieldStatus.SUPPORTED, first.getStatus());
        assertEquals("a", first.getValue());

        assertEquals("tags", second.getName());
        assertEquals("/tags<>", second.getPath());
        assertEquals(CollectionType.LIST, second.getCollectionType());
        assertEquals(FieldType.STRING, second.getFieldType());
        assertEquals(FieldStatus.SUPPORTED, second.getStatus());
        assertEquals("b", second.getValue());
    }

    @Test
    void shouldIgnoreEmptyArray() throws Exception {
        JsonDocument result = inspector.inspect("{\"tags\":[]}");

        assertNotNull(result);
        assertNotNull(result.getFields());
        assertTrue(result.getFields().getField().isEmpty());
    }

    @Test
    void shouldThrowWhenNestedArrayDetected() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> inspector.inspect("{\"matrix\":[[1],[2]]}")
        );

        assertEquals("Nested JSON array is not supported", exception.getMessage());
    }

    @Test
    void shouldCreateFieldWithoutTypeWhenValueIsNull() throws Exception {
        JsonDocument result = inspector.inspect("{\"nickname\":null}");

        assertNotNull(result);
        assertEquals(1, result.getFields().getField().size());

        JsonField field = MapperTestUtils.jsonField(result.getFields().getField().getFirst());
        assertEquals("nickname", field.getName());
        assertEquals("/nickname", field.getPath());
        assertNull(field.getFieldType());
        assertNull(field.getStatus());
        assertNull(field.getValue());
    }

    @Test
    void shouldMergeChildrenIntoFirstArrayObjectForCurrentBehavior() throws Exception {
        JsonDocument result = inspector.inspect("{\"items\":[{\"id\":1},{\"name\":\"second\"}]}");

        assertNotNull(result);
        List<Field> rootFields = result.getFields().getField();
        assertEquals(2, rootFields.size());

        JsonComplexType first = MapperTestUtils.jsonComplexType(rootFields.get(0));
        JsonComplexType second = MapperTestUtils.jsonComplexType(rootFields.get(1));

        assertEquals("/items<>", first.getPath());
        assertEquals(CollectionType.LIST, first.getCollectionType());
        assertEquals(2, first.getJsonFields().getJsonField().size());

        JsonField firstId = MapperTestUtils.jsonField(first.getJsonFields().getJsonField().get(0));
        JsonField firstName = MapperTestUtils.jsonField(first.getJsonFields().getJsonField().get(1));

        assertEquals("id", firstId.getName());
        assertEquals(1, firstId.getValue());
        assertEquals("name", firstName.getName());
        assertEquals("second", firstName.getValue());

        assertEquals("/items<>", second.getPath());
        assertEquals(1, second.getJsonFields().getJsonField().size());

        JsonField secondName = MapperTestUtils.jsonField(second.getJsonFields().getJsonField().getFirst());
        assertEquals("name", secondName.getName());
        assertEquals("second", secondName.getValue());
    }

    private static void assertSupportedJsonField(
            JsonField field,
            String expectedName,
            String expectedPath,
            FieldType expectedFieldType,
            Object expectedValue
    ) {
        assertEquals(expectedName, field.getName());
        assertEquals(expectedPath, field.getPath());
        assertEquals(expectedFieldType, field.getFieldType());
        assertEquals(FieldStatus.SUPPORTED, field.getStatus());
        assertEquals(expectedValue, field.getValue());
    }
}
