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

import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasValidationException;
import io.atlasmap.core.AtlasPath;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.BaseAtlasModule;
import io.atlasmap.core.validate.BaseModuleValidationService;
import io.atlasmap.spi.AtlasFieldReader;
import io.atlasmap.spi.AtlasFieldWriter;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.*;
import org.qubership.integration.platform.mapper.ComplexField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.*;

public abstract class ComplexMappingAtlasModule extends DelegatingAtlasModule {
    private static final Logger LOG = LoggerFactory.getLogger(ComplexMappingAtlasModule.class);

    private static final String SOURCE_DOCUMENT_PROPERTY_PREFIX = "Atlas.SourceDocument.";

    public ComplexMappingAtlasModule(BaseAtlasModule atlasModule) {
        super(atlasModule);
    }

    protected abstract BaseModuleValidationService<?> getValidationService();
    protected abstract BiFunction<AtlasInternalSession, String, Document> getInspectionService();

    @Override
    public void processPreSourceExecution(AtlasInternalSession session) throws AtlasException {
        Object sourceDocument = session.getSourceDocument(getDocId());
        if (sourceDocument instanceof String text && text.isBlank()) {
            handleSourceLoadError("document is blank.");
        }
        try {
            super.processPreSourceExecution(session);
        } catch (Exception exception) {
            handleSourceLoadError(exception);
        }
        Object source = session.getSourceDocument(getDocId());
        if (source instanceof String sourceText) {
            try {
                session.getSourceProperties().put(
                        SOURCE_DOCUMENT_PROPERTY_PREFIX + getDocId(),
                        getInspectionService().apply(session, sourceText)
                );
            } catch (Exception exception) {
                AtlasUtil.addAudit(session, getDocId(), exception.getMessage(), AuditStatus.ERROR, null);
            }
        }
    }

    private void handleSourceLoadError(String detail) throws AtlasException {
        throw new AtlasException(buildSourceLoadErrorMessage(detail));
    }

    private void handleSourceLoadError(Exception cause) throws AtlasException {
        throw new AtlasException(buildSourceLoadErrorMessage(cause.getMessage()), cause);
    }

    private String buildSourceLoadErrorMessage(String detail) {
        String[] formats = this.getClass().getAnnotation(AtlasModuleDetail.class).dataFormats();
        return String.format("Failed to load source data (%s): %s", String.join(", ", formats), detail);
    }

    @Override
    public void processPreValidation(AtlasInternalSession atlasSession) throws AtlasException {
        if (atlasSession == null || atlasSession.getMapping() == null) {
            throw new AtlasValidationException("Invalid session: Session and AtlasMapping must be specified");
        }

        Validations validations = atlasSession.getValidations();
        BaseModuleValidationService<?> validationService = getValidationService();
        validationService.setMode(getMode());
        validationService.setDocId(getDocId());
        List<Validation> currentModuleValidations = validationService.validateMapping(atlasSession.getMapping());
        if (nonNull(currentModuleValidations) && !currentModuleValidations.isEmpty()) {
            validations.getValidation().addAll(currentModuleValidations);
        }

        if (LOG.isDebugEnabled() && nonNull(currentModuleValidations)) {
            LOG.debug("Detected " + currentModuleValidations.size() + " validation notices");
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("{}: processPreValidation completed", getDocId());
        }
    }

    @Override
    public Boolean isSupportedField(Field field) {
        return (field instanceof ComplexField) || super.isSupportedField(field);
    }

    @Override
    public void readSourceValue(AtlasInternalSession session) throws AtlasException {
        Field sourceField = session.head().getSourceField();
        AtlasFieldReader reader = session.getFieldReader(getDocId());
        if (reader == null) {
            AtlasUtil.addAudit(session, sourceField, String.format(
                            "Source document '%s' doesn't exist", getDocId()),
                    AuditStatus.ERROR, null);
            return;
        }
        Field field = sourceField instanceof ComplexField complexField
                ? readComplexField(session, reader, complexField)
                : reader.read(session);
        session.head().setSourceField(field);

        if (LOG.isDebugEnabled()) {
            LOG.debug("{}: processSourceFieldMapping completed: SourceField:[docId={}, path={}, type={}, value={}]",
                    getDocId(), field.getDocId(), field.getPath(), field.getFieldType(), field.getValue());
        }
    }

    @Override
    public void populateTargetField(AtlasInternalSession session) throws AtlasException {
        Field sourceField = session.head().getSourceField();
        Field targetField = session.head().getTargetField();
        AtlasPath sourcePath = new AtlasPath(sourceField.getPath());
        AtlasPath targetPath = new AtlasPath(targetField.getPath());
        boolean targetHasNotIndexedCollection = hasNotIndexedCollection(targetPath);
        if ((sourceField instanceof ComplexField s) && (targetField instanceof ComplexField t)) {
            AtlasPath.SegmentContext lastSegment = targetPath.getLastSegment();
            if (!hasNotIndexedCollection(sourcePath) && targetHasNotIndexedCollection && t.isEmpty()) {
                while (hasNotIndexedCollection(targetPath)) {
                    targetPath.setVacantCollectionIndex(0);
                }
                replacePathPrefix(t, t.getPath(), targetPath.toString());
            } else if (
                    nonNull(lastSegment.getCollectionType())
                            && !CollectionType.NONE.equals(lastSegment.getCollectionType())
                            && nonNull(lastSegment.getCollectionIndex())
            ) {
                targetPath.setCollectionIndex(targetPath.getSegments(true).size() - 1, null);
                replacePathPrefixIndex(t, targetPath.toString(), t.getPath());
            }
            String prefix = Optional.ofNullable(s.getPath()).map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p).orElse("");
            Field result = t.isEmpty()
                    ? copyFieldWithPathPrefixChange(convertComplexFieldsToGroups(s), prefix, t.getPath())
                    : populateComplexField(session, convertComplexFieldsToGroups(s), convertComplexFieldsToGroups(t));
            session.head().setTargetField(result);
        } else {
            if (targetPath.hasCollection() && targetHasNotIndexedCollection && !(sourceField instanceof FieldGroup)) {
                FieldGroup targetFieldGroup = AtlasModelFactory.createFieldGroupFrom(targetField, true);
                session.head().setTargetField(targetFieldGroup);
                // Attempt to Auto-detect field type based on input value
                if (targetField.getFieldType() == null && sourceField.getValue() != null) {
                    targetField.setFieldType(getConversionService().fieldTypeFromClass(sourceField.getValue().getClass()));
                }
                Field targetSubField = createField();
                AtlasModelFactory.copyField(targetField, targetSubField, false);
                targetPath.setVacantCollectionIndex(0);
                targetSubField.setPath(targetPath.toString());
                session.head().setTargetField(targetSubField);
                populateTargetField(session);
                targetFieldGroup.getField().add(session.head().getTargetField());
                session.head().setTargetField(targetFieldGroup);
            } else if (
                    targetPath.hasCollection()
                            && targetHasNotIndexedCollection
                            && sourceField instanceof FieldGroup group
                            && targetField instanceof ComplexField complexField
            ) {
                FieldGroup targetFieldGroup = AtlasModelFactory.createFieldGroupFrom(targetField, true);
                targetFieldGroup.setFieldType(FieldType.COMPLEX);
                targetFieldGroup.setStatus(sourceField.getStatus());
                Field previousTargetSubField = null;
                for (Field sourceSubField : group.getField()) {
                    Field targetSubField = cloneField(targetField);
                    getCollectionHelper()
                            .copyCollectionIndexes(sourceField, sourceSubField, targetSubField, previousTargetSubField);
                    replacePathPrefixIndex(targetSubField, targetField.getPath(), targetSubField.getPath());
                    previousTargetSubField = targetSubField;
                    session.head().setSourceField(sourceSubField);
                    session.head().setTargetField(targetSubField);
                    populateTargetField(session);
                    targetFieldGroup.getField().add(session.head().getTargetField());
                }
                session.head().setSourceField(sourceField);
                session.head().setTargetField(targetFieldGroup);
            } else {
                super.populateTargetField(session);
            }
        }
    }

    @Override
    public void writeTargetValue(AtlasInternalSession session) throws AtlasException {
        AtlasFieldWriter writer = session.getFieldWriter(getDocId());
        Field field = session.head().getTargetField();
        writeField(session, writer, field);
    }

    @Override
    public Field cloneField(Field field) throws AtlasException {
        if (field instanceof ComplexField complexField) {
            ComplexField complexFieldCopy = new ComplexField();
            AtlasModelFactory.copyField(complexField, complexFieldCopy, true);
            for (Field f : complexField.getChildFields()) {
                complexFieldCopy.getChildFields().add(cloneField(f));
            }
            return complexFieldCopy;
        } else if (field instanceof FieldGroup group) {
            FieldGroup groupCopy = AtlasModelFactory.copyFieldGroup(group);
            for (Field f : group.getField()) {
                groupCopy.getField().add(cloneField(f));
            }
            return groupCopy;
        } else {
            return super.cloneField(field);
        }
    }

    private Field readComplexField(
            AtlasInternalSession session,
            AtlasFieldReader reader,
            ComplexField complexField
    ) throws AtlasException {
        // Restore field structure
        Field restoredField = buildComplexFieldTree(session, complexField);
        if (!(restoredField instanceof FieldGroup)) {
            if (nonNull(restoredField.getIndex())) {
                restoredField.setCollectionType(CollectionType.NONE);
                restoredField.setIndex(null);
            }
            restoredField.setFieldType(FieldType.ANY);
        }
        session.head().setSourceField(restoredField);
        Field field = reader.read(session);
        return convertGroupsToComplexFields(field);
    }

    private Field buildComplexFieldTree(AtlasInternalSession session, Field field) {
        if (field instanceof FieldGroup group) {
            return buildComplexFieldTreeForGroup(session, group);
        } else if (field instanceof ComplexField complexField) {
            return complexField.isEmpty()
                    ? buildFieldFromSourceDocument(session, complexField.getPath()).orElse(complexField.asFieldGroup())
                    : buildComplexFieldTreeForGroup(session, complexField.asFieldGroup());
        } else {
            return field;
        }
    }

    private Field buildComplexFieldTreeForGroup(AtlasInternalSession session, FieldGroup group) {
        FieldGroup result = new FieldGroup();
        AtlasModelFactory.copyField(group, result, true);
        result.setValue(group.getValue());
        group.getField().stream().map(field -> buildComplexFieldTree(session, field)).forEach(result.getField()::add);
        return result;
    }

    private void writeField(AtlasInternalSession session, AtlasFieldWriter writer, Field field) throws AtlasException {
        if (field instanceof FieldGroup group) {
            for (Field f : group.getField()) {
                writeField(session, writer, f);
            }
        } else {
            // If field is not found but has a value then we are updating the status
            // in order to get field processed by writer properly.
            // This is case of setting the default value.
            if (nonNull(field.getValue()) && FieldStatus.NOT_FOUND.equals(field.getStatus())) {
               field.setStatus(FieldStatus.SUPPORTED);
            }
            session.head().setTargetField(field);
            writer.write(session);
        }
    }

    private Field copyFieldWithPathPrefixChange(Field field, String oldPathPrefix, String newPathPrefix) {
        Field result;
        if (field instanceof FieldGroup group) {
            result = new FieldGroup();
            AtlasModelFactory.copyField(group, result, true);
            group.getField().stream()
                    .map(f -> copyFieldWithPathPrefixChange(f, oldPathPrefix, newPathPrefix))
                    .forEach(((FieldGroup) result).getField()::add);
        } else {
            result = this.createField();
            AtlasModelFactory.copyField(field, result, true);
        }
        result.setValue(field.getValue());
        if (nonNull(result.getPath()) && (oldPathPrefix.isEmpty() || result.getPath().startsWith(oldPathPrefix))) {
            String path = newPathPrefix + result.getPath().substring(oldPathPrefix.length());
            result.setPath(path);
        }
        return result;
    }

    private Optional<Field> buildFieldFromSourceDocument(AtlasInternalSession session, String path) {
        return Optional.ofNullable(session.getSourceProperties().get(SOURCE_DOCUMENT_PROPERTY_PREFIX + getDocId()))
                .map(Document.class::cast)
                .flatMap(document -> findFieldsInDocument(document, path).stream().findFirst());
    }

    Field populateComplexField(AtlasInternalSession session, Field sourceField, Field targetField)
            throws AtlasException {
        if (targetField instanceof ComplexField complexField) {
            targetField = complexField.asFieldGroup();
        }
        if (sourceField instanceof FieldGroup sourceGroup && targetField instanceof FieldGroup targetGroup) {
            FieldGroup group = new FieldGroup();
            AtlasModelFactory.copyField(sourceField, group, true);
            group.setValue(sourceField.getValue());
            group.setPath(targetGroup.getPath());
            for (Field field : targetGroup.getField()) {
                Field matchingSourceField = sourceGroup.getField().stream()
                        .filter(f -> f.getName().equals(field.getName()))
                        .findAny()
                        .orElse(field);
                Field populatedField = populateComplexField(session, matchingSourceField, field);
                group.getField().add(populatedField);
            }
            return group;
        } else {
            session.head().setSourceField(sourceField);
            session.head().setTargetField(targetField);
            super.populateTargetField(session);
            return session.head().getTargetField();
        }
    }

    private List<Field> findFieldsInDocument(Document document, String path) {
        FieldGroup rootField = new FieldGroup();
        rootField.setFieldType(FieldType.COMPLEX);
        rootField.getField().addAll(document.getFields().getField());
        AtlasPath atlasPath = new AtlasPath(path);
        CollectionType collectionType = atlasPath.getRootSegment().getCollectionType();
        boolean rootIsCollection = CollectionType.LIST.equals(collectionType)
                || CollectionType.ARRAY.equals(collectionType);
        List<AtlasPath.SegmentContext> segments = atlasPath.getSegments(rootIsCollection);
        return findFields(rootField, segments);
    }

    private List<Field> findFields(Field field, List<AtlasPath.SegmentContext> path) {
        if (isNull(field) || path.isEmpty()) {
            return Collections.singletonList(field);
        }
        if (!(field instanceof FieldGroup group)) {
            return Collections.emptyList();
        }

        AtlasPath.SegmentContext segment = path.get(0);
        Optional<Field> currentFieldOptional = group.getField().stream()
                .filter(f -> f.getName().equals(segment.getName())).findAny();
        if (currentFieldOptional.isEmpty()) {
            return Collections.emptyList();
        }
        Field currentField = currentFieldOptional.get();

        if (
                (isNull(segment.getCollectionType()) || segment.getCollectionType().equals(CollectionType.NONE))
                && (isNull(currentField.getCollectionType())
                        || currentField.getCollectionType().equals(CollectionType.NONE))
        ) {
            return findFields(currentField, path.subList(1, path.size()));
        } else if (
                nonNull(currentField.getCollectionType())
                        && currentField.getCollectionType().equals(segment.getCollectionType())
        ) {
            if (isNull(segment.getCollectionIndex()) && isNull(currentField.getIndex())) {
                return findFields(currentField, path.subList(1, path.size()));
            } if (
                    nonNull(segment.getCollectionIndex())
                            && segment.getCollectionIndex().equals(currentField.getIndex())
                            && (currentField instanceof FieldGroup g)
            ) {
                int index = currentField.getIndex();
                return (index >= 0) && (index < g.getField().size())
                        ? findFields(g.getField().get(index), path.subList(1, path.size()))
                        : Collections.emptyList();
            } else {
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    private Field convertComplexFieldsToGroups(Field field) {
        if (field instanceof FieldGroup group) {
            FieldGroup g = AtlasModelFactory.createFieldGroupFrom(group, true);
            group.getField().stream().map(this::convertComplexFieldsToGroups).forEachOrdered(g.getField()::add);
            g.setValue(field.getValue());
            return g;
        } else if (field instanceof ComplexField complexField) {
            FieldGroup g = AtlasModelFactory.createFieldGroupFrom(complexField, true);
            g.setFieldType(FieldType.COMPLEX);
            complexField.getChildFields().stream()
                    .map(this::convertComplexFieldsToGroups).forEachOrdered(g.getField()::add);
            g.setValue(field.getValue());
            return g;
        } else {
            return field;
        }
    }

    private Field convertGroupsToComplexFields(Field field) {
        if (field instanceof FieldGroup group) {
            if (
                    FieldType.COMPLEX.equals(group.getFieldType())
                    && !hasNotIndexedCollection(new AtlasPath(group.getPath()))
            ) {
                ComplexField complexField = new ComplexField();
                AtlasModelFactory.copyField(group, complexField, true);
                group.getField().stream().map(this::convertGroupsToComplexFields)
                        .forEachOrdered(complexField.getChildFields()::add);
                complexField.setValue(group.getValue());
                return complexField;
            } else {
                FieldGroup g = AtlasModelFactory.createFieldGroupFrom(group, true);
                group.getField().stream().map(this::convertGroupsToComplexFields).forEachOrdered(g.getField()::add);
                g.setValue(field.getValue());
                return g;
            }
        } else {
            return field;
        }
    }
}
