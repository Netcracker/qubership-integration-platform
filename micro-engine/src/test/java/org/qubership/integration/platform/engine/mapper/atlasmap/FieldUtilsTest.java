package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.core.AtlasPath;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.ConstantField;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.PropertyField;
import io.atlasmap.v2.SimpleField;
import io.atlasmap.xml.v2.XmlField;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;
import org.qubership.integration.platform.mapper.ComplexField;
import org.qubership.integration.platform.mapper.GeneratedField;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class FieldUtilsTest {

    @Test
    void shouldDetectNotIndexedCollectionWhenPathContainsUnindexedCollection() {
        AtlasPath path = new AtlasPath("/orders<>/id");

        assertTrue(FieldUtils.hasNotIndexedCollection(path));
    }

    @Test
    void shouldNotDetectNotIndexedCollectionWhenPathContainsOnlyIndexedCollection() {
        AtlasPath path = new AtlasPath("/orders<0>/id");

        org.junit.jupiter.api.Assertions.assertFalse(FieldUtils.hasNotIndexedCollection(path));
    }

    @Test
    void shouldReturnFieldGroupChildrenWhenGettingCollectionElements() {
        JsonField first = MapperTestUtils.jsonField("/a");
        JsonField second = MapperTestUtils.jsonField("/b");
        FieldGroup group = MapperTestUtils.fieldGroup("/group", first, second);

        List<Field> result = FieldUtils.getCollectionElements(group);

        assertSame(group.getField(), result);
        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertSame(second, result.get(1));
    }

    @Test
    void shouldReturnComplexFieldChildrenWhenComplexFieldHasNotIndexedCollection() {
        JsonField child = MapperTestUtils.jsonField("/orders<>/id");
        ComplexField complexField = MapperTestUtils.complexField("/orders<>", child);

        List<Field> result = FieldUtils.getCollectionElements(complexField);

        assertSame(complexField.getChildFields(), result);
        assertEquals(1, result.size());
        assertSame(child, result.get(0));
    }

    @Test
    void shouldReturnComplexFieldAsSingleElementWhenComplexFieldHasIndexedCollection() {
        ComplexField complexField = MapperTestUtils.complexField("/orders<0>", MapperTestUtils.jsonField("/orders<0>/id"));

        List<Field> result = FieldUtils.getCollectionElements(complexField);

        assertEquals(1, result.size());
        assertSame(complexField, result.getFirst());
    }

    @Test
    void shouldReturnFieldAsSingleElementWhenSimpleFieldProvided() {
        JsonField field = MapperTestUtils.jsonField("/a");

        List<Field> result = FieldUtils.getCollectionElements(field);

        assertEquals(1, result.size());
        assertSame(field, result.getFirst());
    }

    @Test
    void shouldReplacePrefixIndexForNullMatchingAndNonMatchingStrings() {
        assertNull(FieldUtils.replacePrefixIndex(null, "ab", "X"));
        assertEquals("Xbcde", FieldUtils.replacePrefixIndex("abcde", "ab", "X"));
        assertEquals("abcde", FieldUtils.replacePrefixIndex("abcde", "zz", "X"));
    }

    @Test
    void shouldReplacePathPrefixIndexRecursivelyForFieldGroup() {
        JsonField child = MapperTestUtils.jsonField("ab123");
        FieldGroup group = MapperTestUtils.fieldGroup("abcde", child);

        FieldUtils.replacePathPrefixIndex(group, "ab", "X");

        assertEquals("Xbcde", group.getPath());
        assertEquals("Xb123", child.getPath());
    }

    @Test
    void shouldReplacePathPrefixIndexRecursivelyForComplexField() {
        JsonField child = MapperTestUtils.jsonField("ab123");
        ComplexField complexField = MapperTestUtils.complexField("abcde", child);

        FieldUtils.replacePathPrefixIndex(complexField, "ab", "X");

        assertEquals("Xbcde", complexField.getPath());
        assertEquals("Xb123", child.getPath());
    }

    @Test
    void shouldReplacePrefixForNullMatchingAndNonMatchingStrings() {
        assertNull(FieldUtils.replacePrefix(null, "ab", "X"));
        assertEquals("Xcde", FieldUtils.replacePrefix("abcde", "ab", "X"));
        assertEquals("abcde", FieldUtils.replacePrefix("abcde", "zz", "X"));
    }

    @Test
    void shouldReplacePathPrefixRecursivelyForFieldGroup() {
        JsonField child = MapperTestUtils.jsonField("/source/a");
        FieldGroup group = MapperTestUtils.fieldGroup("/source", child);

        FieldUtils.replacePathPrefix(group, "/source", "/target");

        assertEquals("/target", group.getPath());
        assertEquals("/target/a", child.getPath());
    }

    @Test
    void shouldReplacePathPrefixRecursivelyForComplexField() {
        JsonField child = MapperTestUtils.jsonField("/source/a");
        ComplexField complexField = MapperTestUtils.complexField("/source", child);

        FieldUtils.replacePathPrefix(complexField, "/source", "/target");

        assertEquals("/target", complexField.getPath());
        assertEquals("/target/a", child.getPath());
    }

    @Test
    void shouldReturnChildrenForComplexFieldAndFieldGroupAndEmptyForSimpleField() {
        JsonField child = MapperTestUtils.jsonField("/a");
        ComplexField complexField = MapperTestUtils.complexField("/complex", child);
        FieldGroup group = MapperTestUtils.fieldGroup("/group", child);
        JsonField simple = MapperTestUtils.jsonField("/simple");

        assertSame(complexField.getChildFields(), FieldUtils.getChildren(complexField));
        assertSame(group.getField(), FieldUtils.getChildren(group));
        assertTrue(FieldUtils.getChildren(simple).isEmpty());
    }

    @Test
    void shouldCloneComplexFieldRecursively() {
        SimpleField child = MapperTestUtils.simpleField("/complex/child");
        ComplexField original = MapperTestUtils.complexField("/complex", child);
        original.setValue("complex-value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(ComplexField.class, cloned);
        ComplexField copy = (ComplexField) cloned;
        assertEquals("/complex", copy.getPath());
        assertEquals("complex-value", copy.getValue());
        assertEquals(1, copy.getChildFields().size());
        assertInstanceOf(SimpleField.class, copy.getChildFields().getFirst());
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        org.junit.jupiter.api.Assertions.assertNotSame(original.getChildFields().getFirst(), copy.getChildFields().getFirst());
        assertEquals("value", copy.getChildFields().getFirst().getValue());
    }

    @Test
    void shouldCloneFieldGroupRecursively() {
        SimpleField child = MapperTestUtils.simpleField("/group/child");
        FieldGroup original = MapperTestUtils.fieldGroup("/group", child);

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(FieldGroup.class, cloned);
        FieldGroup copy = (FieldGroup) cloned;
        assertEquals("/group", copy.getPath());
        assertEquals(1, copy.getField().size());
        assertInstanceOf(SimpleField.class, copy.getField().getFirst());
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        org.junit.jupiter.api.Assertions.assertNotSame(original.getField().getFirst(), copy.getField().getFirst());
        assertEquals("value", copy.getField().getFirst().getValue());
    }

    @Test
    void shouldCloneSimpleField() {
        SimpleField original = MapperTestUtils.simpleField("/simple");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(SimpleField.class, cloned);
        SimpleField copy = (SimpleField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/simple", copy.getPath());
        assertEquals("value", copy.getValue());
    }

    @Test
    void shouldCloneGeneratedField() {
        GeneratedField original = new GeneratedField();
        original.setDocId("doc1");
        original.setPath("/generated");
        original.setValue("value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(GeneratedField.class, cloned);
        GeneratedField copy = (GeneratedField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/generated", copy.getPath());
        assertEquals("value", copy.getValue());
    }

    @Test
    void shouldCloneConstantField() {
        ConstantField original = new ConstantField();
        original.setDocId("doc1");
        original.setPath("/constant");
        original.setValue("value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(ConstantField.class, cloned);
        ConstantField copy = (ConstantField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/constant", copy.getPath());
        assertEquals("value", copy.getValue());
    }

    @Test
    void shouldClonePropertyField() {
        PropertyField original = new PropertyField();
        original.setDocId("doc1");
        original.setPath("/property");
        original.setValue("value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(PropertyField.class, cloned);
        PropertyField copy = (PropertyField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/property", copy.getPath());
        assertEquals("value", copy.getValue());
        assertEquals(original.getScope(), copy.getScope());
    }

    @Test
    void shouldCloneJsonField() {
        JsonField original = MapperTestUtils.jsonField("/json");
        original.setValue("value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(JsonField.class, cloned);
        JsonField copy = (JsonField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/json", copy.getPath());
        assertEquals("value", copy.getValue());
    }

    @Test
    void shouldCloneXmlField() {
        XmlField original = new XmlField();
        original.setDocId("doc1");
        original.setPath("/xml");
        original.setValue("value");

        Field cloned = FieldUtils.cloneField(original);

        assertInstanceOf(XmlField.class, cloned);
        XmlField copy = (XmlField) cloned;
        org.junit.jupiter.api.Assertions.assertNotSame(original, copy);
        assertEquals("/xml", copy.getPath());
        assertEquals("value", copy.getValue());
    }

    @Test
    void shouldReturnNullWhenCloneFieldWithNull() {
        assertNull(FieldUtils.cloneField(null));
    }

    @Test
    void shouldReplacePathSegmentsAndPreserveIndex() {
        JsonField field = MapperTestUtils.jsonField("/orders<3>/id");
        List<AtlasPath.SegmentContext> from = new AtlasPath("/orders<>").getSegments(true);
        List<AtlasPath.SegmentContext> to = new AtlasPath("/items<>").getSegments(true);

        FieldUtils.replacePathSegments(field, from, to);

        assertEquals("/items<3>/id", field.getPath());
    }

    @Test
    void shouldReplacePathSegmentsRecursivelyForFieldGroup() {
        JsonField child = MapperTestUtils.jsonField("/orders<3>/id");
        FieldGroup group = MapperTestUtils.fieldGroup("/orders<3>", child);
        List<AtlasPath.SegmentContext> from = new AtlasPath("/orders<>").getSegments(true);
        List<AtlasPath.SegmentContext> to = new AtlasPath("/items<>").getSegments(true);

        FieldUtils.replacePathSegments(group, from, to);

        assertEquals("/items<3>", group.getPath());
        assertEquals("/items<3>/id", child.getPath());
    }

    @Test
    void shouldReplacePathSegmentsRecursivelyForComplexField() {
        JsonField child = MapperTestUtils.jsonField("/orders<3>/id");
        ComplexField complexField = MapperTestUtils.complexField("/orders<3>", child);
        List<AtlasPath.SegmentContext> from = new AtlasPath("/orders<>").getSegments(true);
        List<AtlasPath.SegmentContext> to = new AtlasPath("/items<>").getSegments(true);

        FieldUtils.replacePathSegments(complexField, from, to);

        assertEquals("/items<3>", complexField.getPath());
        assertEquals("/items<3>/id", child.getPath());
    }

    @Test
    void shouldNotReplacePathSegmentsWhenPrefixDoesNotMatch() {
        JsonField field = MapperTestUtils.jsonField("/customer/name");
        List<AtlasPath.SegmentContext> from = new AtlasPath("/orders<>").getSegments(true);
        List<AtlasPath.SegmentContext> to = new AtlasPath("/items<>").getSegments(true);

        FieldUtils.replacePathSegments(field, from, to);

        assertEquals("/customer/name", field.getPath());
    }
}
