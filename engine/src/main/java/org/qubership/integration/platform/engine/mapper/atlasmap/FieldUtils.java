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

import io.atlasmap.core.AtlasPath;
import io.atlasmap.json.v2.AtlasJsonModelFactory;
import io.atlasmap.json.v2.JsonField;
import io.atlasmap.v2.*;
import io.atlasmap.xml.v2.AtlasXmlModelFactory;
import io.atlasmap.xml.v2.XmlField;
import org.qubership.integration.platform.mapper.ComplexField;
import org.qubership.integration.platform.mapper.GeneratedField;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class FieldUtils {
    private static class AtlasPathHelper extends AtlasPath {
        public AtlasPathHelper(List<AtlasPath.SegmentContext> segments) {
            super(segments);
        }
    }

    public static boolean hasNotIndexedCollection(AtlasPath path) {
        return path.getSegments(true).stream().anyMatch(
                s -> nonNull(s.getCollectionType())
                        && !CollectionType.NONE.equals(s.getCollectionType())
                        && isNull(s.getCollectionIndex()));
    }

    public static List<Field> getCollectionElements(Field field) {
        if (field instanceof FieldGroup group) {
            return group.getField();
        } else if (field instanceof ComplexField complexField) {
            AtlasPath path = new AtlasPath(field.getPath());
            if (hasNotIndexedCollection(path)) {
                return complexField.getChildFields();
            } else {
                return Collections.singletonList(complexField);
            }
        } else {
            return Collections.singletonList(field);
        }
    }

    public static String replacePrefixIndex(String s, String from, String to) {
        return isNull(s) ? null : s.startsWith(from) ? to + s.substring(from.length() - 1) : s;
    }

    public static void replacePathPrefixIndex(Field field, String from, String to) {
        field.setPath(replacePrefixIndex(field.getPath(), from, to));
        if (field instanceof FieldGroup group) {
            group.getField().forEach(f -> replacePathPrefixIndex(f, from, to));
        } else if (field instanceof ComplexField complexField) {
            complexField.getChildFields().forEach(f -> replacePathPrefixIndex(f, from, to));
        }
    }

    public static String replacePrefix(String s, String from, String to) {
        return isNull(s) ? null : s.startsWith(from) ? to + s.substring(from.length()) : s;
    }

    public static void replacePathPrefix(Field field, String from, String to) {
        field.setPath(replacePrefix(field.getPath(), from, to));
        if (field instanceof FieldGroup group) {
            group.getField().forEach(f -> replacePathPrefix(f, from, to));
        } else if (field instanceof ComplexField complexField) {
            complexField.getChildFields().forEach(f -> replacePathPrefix(f, from, to));
        }
    }

    public static List<Field> getChildren(Field field) {
        return (field instanceof ComplexField complexField)
                ? complexField.getChildFields()
                : (field instanceof FieldGroup group)
                ? group.getField()
                : Collections.emptyList();
    }

    public static Field cloneField(Field field) {
        switch (field) {
            case ComplexField complexField -> {
                ComplexField complexFieldCopy = new ComplexField();
                AtlasModelFactory.copyField(complexField, complexFieldCopy, true);
                complexFieldCopy.setValue(complexField.getValue());
                for (Field f : complexField.getChildFields()) {
                    complexFieldCopy.getChildFields().add(cloneField(f));
                }
                return complexFieldCopy;
            }
            case FieldGroup group -> {
                FieldGroup groupCopy = AtlasModelFactory.copyFieldGroup(group);
                for (Field f : group.getField()) {
                    groupCopy.getField().add(cloneField(f));
                }
                return groupCopy;
            }
            case SimpleField simpleField -> {
                SimpleField fieldCopy = new SimpleField();
                AtlasModelFactory.copyField(simpleField, fieldCopy, true);
                fieldCopy.setValue(simpleField.getValue());
                return fieldCopy;
            }
            case GeneratedField generatedField -> {
                GeneratedField fieldCopy = new GeneratedField();
                AtlasModelFactory.copyField(generatedField, fieldCopy, true);
                fieldCopy.setValue(generatedField.getValue());
                return fieldCopy;
            }
            case ConstantField constantField -> {
                ConstantField fieldCopy = new ConstantField();
                AtlasModelFactory.copyField(constantField, fieldCopy, true);
                fieldCopy.setValue(constantField.getValue());
                return fieldCopy;
            }
            case PropertyField propertyField -> {
                PropertyField fieldCopy = new PropertyField();
                AtlasModelFactory.copyField(propertyField, fieldCopy, true);
                fieldCopy.setValue(propertyField.getValue());
                fieldCopy.setScope(propertyField.getScope());
                return fieldCopy;
            }
            case JsonField jsonField -> {
                JsonField fieldCopy = new JsonField();
                AtlasJsonModelFactory.copyField(jsonField, fieldCopy, true);
                fieldCopy.setValue(jsonField.getValue());
                return fieldCopy;
            }
            case XmlField xmlField -> {
                XmlField fieldCopy = new XmlField();
                AtlasXmlModelFactory.copyField(xmlField, fieldCopy, true);
                fieldCopy.setValue(xmlField.getValue());
                return fieldCopy;
            }
            case null, default -> {
                return field;
            }
        }
    }

    public static void replacePathSegments(Field field, List<AtlasPath.SegmentContext> from, List<AtlasPath.SegmentContext> to) {
        field.setPath(replaceSegmentContexts(field.getPath(), from, to));
        if (field instanceof FieldGroup group) {
            group.getField().forEach(f -> replacePathSegments(f, from, to));
        } else if (field instanceof ComplexField complexField) {
            complexField.getChildFields().forEach(f -> replacePathSegments(f, from, to));
        }
    }

    private static String replaceSegmentContexts(String path, List<AtlasPath.SegmentContext> from, List<AtlasPath.SegmentContext> to) {
        List<AtlasPath.SegmentContext> segments = new AtlasPath(path).getSegments(true);
        return segmentsStartsWith(segments, from) ? replacePrefixSegments(segments, from, to) : path;
    }

    private static boolean segmentsStartsWith(List<AtlasPath.SegmentContext> segments, List<AtlasPath.SegmentContext> prefix) {
        if (segments.size() < prefix.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            AtlasPath.SegmentContext s = segments.get(i);
            AtlasPath.SegmentContext p = prefix.get(i);
            if (!s.getName().equals(p.getName()) || (s.getCollectionType() != p.getCollectionType())) {
                return false;
            }
        }
        return true;
    }

    private static String replacePrefixSegments(
            List<AtlasPath.SegmentContext> segments,
            List<AtlasPath.SegmentContext> from,
            List<AtlasPath.SegmentContext> to
    ) {
        List<AtlasPath.SegmentContext> resultSegments =
            Stream.concat(to.stream(), segments.subList(from.size(), segments.size()).stream()).toList();
        if (!from.isEmpty() && nonNull(segments.get(from.size() - 1).getCollectionIndex())) {
            Integer index = segments.get(from.size() - 1).getCollectionIndex();
            String segmentExpression = resultSegments.get(to.size() - 1).getExpression();
            if (segmentExpression.endsWith(AtlasPath.PATH_LIST_SUFFIX)) {
                segmentExpression = segmentExpression.substring(0, segmentExpression.length() - AtlasPath.PATH_LIST_SUFFIX.length())
                        + AtlasPath.PATH_LIST_START + index.toString() + AtlasPath.PATH_LIST_END;
            } else if (segmentExpression.endsWith(AtlasPath.PATH_ARRAY_SUFFIX)) {
                segmentExpression = segmentExpression.substring(0, segmentExpression.length() - AtlasPath.PATH_ARRAY_SUFFIX.length())
                        + AtlasPath.PATH_ARRAY_START + index.toString() + AtlasPath.PATH_ARRAY_END;
            }
            resultSegments.set(to.size() - 1, new AtlasPath.SegmentContext(segmentExpression));
        }
        return new AtlasPathHelper(resultSegments).toString();
    }
}
