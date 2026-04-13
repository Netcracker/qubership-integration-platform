package org.qubership.integration.platform.engine.mapper.atlasmap.json;

import io.atlasmap.api.AtlasConversionException;
import io.atlasmap.api.AtlasException;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.json.v2.JsonEnumField;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.spi.AtlasConversionService;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.FieldStatus;
import io.atlasmap.v2.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class QipAtlasJsonFieldReaderTest {

    @Mock
    private AtlasConversionService conversionService;
    @Mock
    private AtlasInternalSession session;
    @Mock
    private AtlasInternalSession.Head head;

    private QipAtlasJsonFieldReader reader;

    @BeforeEach
    void setUp() {
        reader = new QipAtlasJsonFieldReader(conversionService);
    }

    @ParameterizedTest
    @MethodSource("nullDocumentStates")
    void shouldAddAuditAndReturnFieldWhenDocumentIsNotAvailable(String initialDocument, String nextDocument)
            throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/name");
        stubSessionField(sourceField);

        if (initialDocument != null) {
            reader.setDocument(initialDocument);
        }
        if (nextDocument != null || initialDocument != null) {
            reader.setDocument(nextDocument);
        }

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            Field result = reader.read(session);

            assertSame(sourceField, result);
            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    eq(sourceField),
                    contains("document is null"),
                    eq(AuditStatus.ERROR),
                    isNull()
            ));
        }
    }

    @Test
    void shouldReadSingleScalarField() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/name");
        stubSessionField(sourceField);
        reader.setDocument("{\"name\":\"Harry\"}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals("Harry", sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldPeelOffRootedObjectWhenPathDoesNotStartWithRootName() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orderId");
        stubSessionField(sourceField);
        reader.setDocument("{\"source\":{\"orderId\":123}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(123, sourceField.getValue());
    }

    @Test
    void shouldSetNotFoundWhenScalarFieldIsMissing() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/missing");
        stubSessionField(sourceField);
        reader.setDocument("{\"name\":\"Harry\"}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(FieldStatus.NOT_FOUND, sourceField.getStatus());
        assertNull(sourceField.getValue());
    }

    @Test
    void shouldReadIndexedCollectionItem() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<1>/id");
        stubSessionField(sourceField);
        reader.setDocument("{\"orders\":[{\"id\":10},{\"id\":20}]}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(20, sourceField.getValue());
    }

    @Test
    void shouldSetNotFoundWhenIndexedCollectionItemIsOutOfRange() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<2>/id");
        stubSessionField(sourceField);
        reader.setDocument("{\"orders\":[{\"id\":10}]}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(FieldStatus.NOT_FOUND, sourceField.getStatus());
        assertNull(sourceField.getValue());
    }

    @Test
    void shouldReturnFieldGroupForNonIndexedCollection() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<>/id");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"orders\":[{\"id\":10},{\"id\":20}]}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup resultGroup = (FieldGroup) result;
        assertEquals(2, resultGroup.getField().size());

        Field first = resultGroup.getField().get(0);
        Field second = resultGroup.getField().get(1);

        assertInstanceOf(JsonField.class, first);
        assertInstanceOf(JsonField.class, second);
        assertEquals(10, first.getValue());
        assertEquals(20, second.getValue());
        assertEquals("/orders<0>/id", first.getPath());
        assertEquals("/orders<1>/id", second.getPath());

        verify(head).setSourceField(resultGroup);
    }

    @Test
    void shouldSetNotFoundForEmptyNonIndexedCollection() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<>/id");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"orders\":[]}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup resultGroup = (FieldGroup) result;
        assertEquals(FieldStatus.NOT_FOUND, resultGroup.getStatus());
        assertTrue(resultGroup.getField().isEmpty());

        verify(head).setSourceField(resultGroup);
    }

    @Test
    void shouldPopulateComplexFieldChildrenAndSkipNullChildren() throws Exception {
        FieldGroup sourceField = MapperTestUtils.fieldGroup(
                "/customer",
                MapperTestUtils.jsonField("/customer/name"),
                MapperTestUtils.jsonField("/customer/nickname"),
                MapperTestUtils.jsonField("/customer/tags<>")
        );
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\",\"nickname\":null,\"tags\":[\"vip\",\"new\"]}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(2, sourceField.getField().size());

        Field nameField = sourceField.getField().get(0);
        assertInstanceOf(JsonField.class, nameField);
        assertEquals("/customer/name", nameField.getPath());
        assertEquals("Alice", nameField.getValue());

        Field tagsField = sourceField.getField().get(1);
        assertInstanceOf(FieldGroup.class, tagsField);
        FieldGroup tagsGroup = (FieldGroup) tagsField;
        assertEquals(2, tagsGroup.getField().size());
        assertEquals("vip", tagsGroup.getField().get(0).getValue());
        assertEquals("new", tagsGroup.getField().get(1).getValue());
    }

    @Test
    void shouldConvertValueWhenFieldTypeIsProvided() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/age", FieldType.INTEGER);
        stubSessionField(sourceField);
        reader.setDocument("{\"age\":\"42\"}");
        when(conversionService.convertType(eq("42"), isNull(), eq(FieldType.INTEGER), isNull())).thenReturn(42);

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(42, sourceField.getValue());
    }

    @Test
    void shouldAddAuditAndReturnNullWhenTypeConversionFails() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/age", FieldType.INTEGER);
        stubSessionField(sourceField);
        reader.setDocument("{\"age\":\"abc\"}");
        when(conversionService.convertType(eq("abc"), isNull(), eq(FieldType.INTEGER), isNull()))
                .thenThrow(new AtlasConversionException("boom"));

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            Field result = reader.read(session);

            assertSame(sourceField, result);
            assertNull(sourceField.getValue());
            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    any(JsonField.class),
                    contains("Failed to convert field value"),
                    eq(AuditStatus.ERROR),
                    eq("abc")
            ));
        }
    }

    @Test
    void shouldTreatJsonEnumFieldAsStringWhenFieldTypeIsComplex() throws Exception {
        JsonEnumField sourceField = MapperTestUtils.jsonEnumField();
        stubSessionField(sourceField);
        reader.setDocument("{\"status\":\"OPEN\"}");
        when(conversionService.convertType(eq("OPEN"), isNull(), eq(FieldType.STRING), isNull())).thenReturn("OPEN");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals("OPEN", sourceField.getValue());
    }

    @Test
    void shouldAddAuditWhenUnexpectedArrayNodeIsReadAsScalar() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/tags");
        stubSessionField(sourceField);
        reader.setDocument("{\"tags\":[\"a\",\"b\"]}");

        try (MockedStatic<AtlasUtil> atlasUtil = mockStatic(AtlasUtil.class)) {
            Field result = reader.read(session);

            assertSame(sourceField, result);
            assertNull(sourceField.getValue());
            atlasUtil.verify(() -> AtlasUtil.addAudit(
                    eq(session),
                    any(JsonField.class),
                    contains("Unexpected array node is detected"),
                    eq(AuditStatus.ERROR),
                    anyString()
            ));
        }
    }

    @Test
    void shouldReadNullValueAsNull() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/nickname");
        stubSessionField(sourceField);
        reader.setDocument("{\"nickname\":null}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertNull(sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldInferFieldTypesForCollectionItems() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/values<>");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"values\":[1,true,\"text\",9223372036854775808]}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertEquals(4, group.getField().size());

        JsonField first = (JsonField) group.getField().get(0);
        JsonField second = (JsonField) group.getField().get(1);
        JsonField third = (JsonField) group.getField().get(2);
        JsonField fourth = (JsonField) group.getField().get(3);

        assertEquals(FieldType.INTEGER, first.getFieldType());
        assertEquals(1, first.getValue());

        assertEquals(FieldType.BOOLEAN, second.getFieldType());
        assertEquals(Boolean.TRUE, second.getValue());

        assertEquals(FieldType.STRING, third.getFieldType());
        assertEquals("text", third.getValue());

        assertEquals(FieldType.BIG_INTEGER, fourth.getFieldType());
        assertEquals("9223372036854775808", fourth.getValue().toString());
    }

    @Test
    void shouldThrowAtlasExceptionWhenDocumentIsInvalidJson() {
        AtlasException exception = assertThrows(AtlasException.class, () -> reader.setDocument("{"));

        assertNotNull(exception.getCause());
    }

    @Test
    void shouldReadNestedScalarField() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/customer/name");
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\"}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals("Alice", sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldReadObjectNodeAsNullValueWithoutNotFoundStatus() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/customer");
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\"}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertNull(sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldReadLongNumber() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/value");
        stubSessionField(sourceField);
        reader.setDocument("{\"value\":2147483648}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(2147483648L, sourceField.getValue());
    }

    @Test
    void shouldReadDoubleNumber() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/value");
        stubSessionField(sourceField);
        reader.setDocument("{\"value\":1.25}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(1.25d, sourceField.getValue());
    }

    @Test
    void shouldReturnNotFoundGroupWhenCollectionPropertyIsMissing() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<>/id");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\"}}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertTrue(group.getField().isEmpty());
        assertEquals(FieldStatus.NOT_FOUND, group.getStatus());

        verify(head).setSourceField(group);
    }

    @Test
    void shouldSkipCollectionItemsWithoutRequestedChildField() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<>/id");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"orders\":[{}, {\"id\":20}]}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertEquals(1, group.getField().size());

        Field onlyField = group.getField().get(0);
        assertInstanceOf(JsonField.class, onlyField);
        assertEquals("/orders<1>/id", onlyField.getPath());
        assertEquals(20, onlyField.getValue());

        verify(head).setSourceField(group);
    }

    @Test
    void shouldPopulateCollectionOfComplexChildren() throws Exception {
        FieldGroup sourceField = MapperTestUtils.fieldGroup(
                "/customer",
                MapperTestUtils.fieldGroup(
                        "/customer/orders<>",
                        MapperTestUtils.jsonField("/customer/orders<>/id"),
                        MapperTestUtils.jsonField("/customer/orders<>/name")
                )
        );
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"orders\":[{\"id\":10,\"name\":\"first\"}]}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(1, sourceField.getField().size());

        Field ordersField = sourceField.getField().get(0);
        assertInstanceOf(FieldGroup.class, ordersField);
        FieldGroup ordersGroup = (FieldGroup) ordersField;

        FieldGroup itemGroup = null;
        for (Field child : ordersGroup.getField()) {
            if (child instanceof FieldGroup) {
                itemGroup = (FieldGroup) child;
                break;
            }
        }

        assertNotNull(itemGroup);
        assertEquals(2, itemGroup.getField().size());

        Field idField = itemGroup.getField().get(0);
        Field nameField = itemGroup.getField().get(1);

        assertInstanceOf(JsonField.class, idField);
        assertInstanceOf(JsonField.class, nameField);

        assertEquals(10, idField.getValue());
        assertEquals("first", nameField.getValue());
    }

    @Test
    void shouldReadBigIntegerNumberAsScalar() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/value");
        stubSessionField(sourceField);
        reader.setDocument("{\"value\":9223372036854775808}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals("9223372036854775808", sourceField.getValue().toString());
    }

    @Test
    void shouldReturnNotFoundWhenNestedFieldIsMissing() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/customer/address/street");
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\"}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(FieldStatus.NOT_FOUND, sourceField.getStatus());
        assertNull(sourceField.getValue());
    }

    @Test
    void shouldReadRootFieldGroupWhenPathHasNoSegments() throws Exception {
        FieldGroup sourceField = MapperTestUtils.fieldGroup(
                "/",
                MapperTestUtils.jsonField("/name"),
                MapperTestUtils.jsonField("/age")
        );
        stubSessionField(sourceField);
        reader.setDocument("{\"name\":\"Alice\",\"age\":30}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(2, sourceField.getField().size());

        Field nameField = sourceField.getField().get(0);
        Field ageField = sourceField.getField().get(1);

        assertInstanceOf(JsonField.class, nameField);
        assertInstanceOf(JsonField.class, ageField);

        assertEquals("Alice", nameField.getValue());
        assertEquals(30, ageField.getValue());
    }

    @Test
    void shouldReadIndexedNestedCollectionItem() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<1>/items<0>/name");
        stubSessionField(sourceField);
        reader.setDocument("{\"orders\":[{\"items\":[{\"name\":\"skip\"}]},{\"items\":[{\"name\":\"target\"}]}]}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals("target", sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldReadOnlyExistingNestedCollectionChildren() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/orders<>/customer/name");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument(
                "{\"orders\":[{\"customer\":{\"name\":\"Alice\"}}, {\"customer\":{}}, {\"customer\":{\"name\":\"Bob\"}}]}"
        );

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertEquals(2, group.getField().size());

        Field first = group.getField().get(0);
        Field second = group.getField().get(1);

        assertInstanceOf(JsonField.class, first);
        assertInstanceOf(JsonField.class, second);

        assertEquals("/orders<0>/customer/name", first.getPath());
        assertEquals("Alice", first.getValue());

        assertEquals("/orders<2>/customer/name", second.getPath());
        assertEquals("Bob", second.getValue());

        verify(head).setSourceField(group);
    }

    @Test
    void shouldPopulateEmptyCollectionChildGroup() throws Exception {
        FieldGroup sourceField = MapperTestUtils.fieldGroup(
                "/customer",
                MapperTestUtils.jsonField("/customer/name"),
                MapperTestUtils.jsonField("/customer/tags<>")
        );
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{\"name\":\"Alice\",\"tags\":[]}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(2, sourceField.getField().size());

        Field nameField = sourceField.getField().get(0);
        Field tagsField = sourceField.getField().get(1);

        assertInstanceOf(JsonField.class, nameField);
        assertEquals("Alice", nameField.getValue());

        assertInstanceOf(FieldGroup.class, tagsField);
        FieldGroup tagsGroup = (FieldGroup) tagsField;
        assertTrue(tagsGroup.getField().isEmpty());
    }

    @Test
    void shouldReadLongAndDoubleValuesInCollection() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/values<>");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("{\"values\":[2147483648,1.5]}");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertEquals(2, group.getField().size());

        JsonField longField = (JsonField) group.getField().get(0);
        JsonField doubleField = (JsonField) group.getField().get(1);

        assertEquals(FieldType.LONG, longField.getFieldType());
        assertEquals(2147483648L, longField.getValue());

        assertEquals(FieldType.DOUBLE, doubleField.getFieldType());
        assertEquals(1.5d, doubleField.getValue());
    }

    @Test
    void shouldReadEmptyComplexObjectAsComplexField() throws Exception {
        FieldGroup sourceField = MapperTestUtils.fieldGroup("/customer");
        stubSessionField(sourceField);
        reader.setDocument("{\"customer\":{}}");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertTrue(sourceField.getField().isEmpty());
        assertNull(sourceField.getStatus());
    }

    @Test
    void shouldReadRootCollectionItems() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/<>/id");
        stubSessionField(sourceField);
        when(head.setSourceField(any(Field.class))).thenReturn(head);
        reader.setDocument("[{\"id\":10},{\"id\":20}]");

        Field result = reader.read(session);

        assertInstanceOf(FieldGroup.class, result);
        FieldGroup group = (FieldGroup) result;
        assertEquals(2, group.getField().size());

        Field first = group.getField().get(0);
        Field second = group.getField().get(1);

        assertInstanceOf(JsonField.class, first);
        assertInstanceOf(JsonField.class, second);

        assertEquals(10, first.getValue());
        assertEquals(20, second.getValue());

        verify(head).setSourceField(group);
    }

    @Test
    void shouldReadIndexedRootCollectionItem() throws Exception {
        JsonField sourceField = MapperTestUtils.jsonField("/<1>/id");
        stubSessionField(sourceField);
        reader.setDocument("[{\"id\":10},{\"id\":20}]");

        Field result = reader.read(session);

        assertSame(sourceField, result);
        assertEquals(20, sourceField.getValue());
        assertNull(sourceField.getStatus());
    }

    private void stubSessionField(Field field) {
        when(session.head()).thenReturn(head);
        when(head.getSourceField()).thenReturn(field);
    }

    private static Stream<Arguments> nullDocumentStates() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("{\"name\":\"Harry\"}", null),
                Arguments.of("{\"name\":\"Harry\"}", "")
        );
    }
}
