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

import io.atlasmap.api.AtlasContextFactory.Format;
import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasSession;
import io.atlasmap.core.*;
import io.atlasmap.spi.AtlasModule;
import io.atlasmap.spi.FieldDirection;
import io.atlasmap.v2.*;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.mapper.atlasmap.expressions.CustomAtlasExpressionProcessor;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Slf4j
public class CustomAtlasContext extends DefaultAtlasContext {
    private ValidationResult cachedValidationResult;

    public CustomAtlasContext(URI atlasMappingUri) {
        super(atlasMappingUri);
    }

    public CustomAtlasContext(DefaultAtlasContextFactory factory,
        URI atlasMappingUri) {
        super(factory, atlasMappingUri);
    }

    public CustomAtlasContext(DefaultAtlasContextFactory factory, AtlasMapping mapping) {
        super(factory, mapping);
    }

    public CustomAtlasContext(DefaultAtlasContextFactory factory,
        Format format, InputStream stream)
        throws AtlasException {
        super(factory, format, stream);
    }

    public ValidationResult getCachedValidationResult() {
        return cachedValidationResult;
    }

    public void setCachedValidationResult(ValidationResult cachedValidationResult) {
        this.cachedValidationResult = cachedValidationResult;
    }

    @Override
    public void processValidation(AtlasSession userSession) throws AtlasException {
        if (isNull(cachedValidationResult)) {
            processValidationAndSaveResult(userSession);
        } else {
            restoreValidationResult(userSession);
        }
    }

    private void processValidationAndSaveResult(AtlasSession userSession) throws AtlasException {
        ValidationResult.ValidationResultBuilder resultBuilder = ValidationResult.builder();
        try {
            super.processValidation(userSession);
        } catch (AtlasException e) {
            resultBuilder = resultBuilder.exception(e);
            throw e;
        } catch (RuntimeException e) {
            resultBuilder = resultBuilder.runtimeException(e);
            throw e;
        } finally {
            List<Validation> validations = new ArrayList<>(userSession.getValidations().getValidation());
            cachedValidationResult = resultBuilder.validations(validations).build();
        }
    }

    private void restoreValidationResult(AtlasSession userSession) throws AtlasException {
        userSession.getValidations().getValidation().addAll(cachedValidationResult.getValidations());
        if (nonNull(cachedValidationResult.getException())) {
            throw cachedValidationResult.getException();
        }
        if (nonNull(cachedValidationResult.getRuntimeException())) {
            throw cachedValidationResult.getRuntimeException();
        }
    }

    @Override
    protected void processSourceFieldMapping(DefaultAtlasSession session) {
        try {
            Mapping mapping = session.head().getMapping();
            if (mapping.getInputFieldGroup() != null) {
                if (mapping.getExpression() != null) {
                    session.head().setSourceField(mapping.getInputFieldGroup());
                    CustomAtlasExpressionProcessor.processExpression(session, mapping.getExpression());
                } else {
                    processSourceFieldGroup(session, mapping.getInputFieldGroup());
                }
            } else if (mapping.getInputField() != null && !mapping.getInputField().isEmpty()) {
                if (mapping.getExpression() != null) {
                    FieldGroup sourceFieldGroup = new FieldGroup();
                    sourceFieldGroup.getField().addAll(mapping.getInputField());
                    session.head().setSourceField(sourceFieldGroup);
                    CustomAtlasExpressionProcessor.processExpression(session, mapping.getExpression());
                } else {
                    List<Field> sourceFields = mapping.getInputField();
                    applyCopyToActions(sourceFields, mapping);
                    processSourceFields(session, sourceFields);
                }
            } else {
                session.head().addAudit(AuditStatus.WARN, null, String.format(
                    "Mapping does not contain expression or at least one source field: alias=%s desc=%s",
                    mapping.getAlias(), mapping.getDescription()));
            }
        } catch (Exception t) {
            Field sourceField = session.head().getSourceField();
            String docId = sourceField != null ? sourceField.getDocId() : null;
            String path =  sourceField != null ? sourceField.getPath() : null;
            session.head().addAudit(AuditStatus.ERROR, sourceField, String.format(
                "Unexpected exception is thrown while reading source field: %s", t.getMessage()));
            if (log.isDebugEnabled()) {
                log.error("", t);
            }
        }
    }

    @Override
    public AtlasSession createSession() throws AtlasException {
        AtlasSession session = super.createSession();

        // Overriding default target document identifier in order to prevent writing
        // wrong data to one of the target documents that will be declared
        // as a default document by AtlasMap (see DefaultAtlasSession.setTargetDocument
        // and DefaultAtlasSession.getDefaultTargetDocumentId).
        overrideDefaultDocumentId(session);

        return session;
    }

    private void overrideDefaultDocumentId(AtlasSession session) {
        try {
            java.lang.reflect.Field f1 = session.getClass().getDeclaredField("defaultTargetDocumentId");
            f1.setAccessible(true);
            f1.set(session, "");
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException exception) {
            log.error("Failed to override AtlasMap default document ID.", exception);
        }
    }

    private void processSourceFieldGroup(DefaultAtlasSession session, FieldGroup sourceFieldGroup) throws AtlasException {
        processSourceFields(session, sourceFieldGroup.getField());
        session.head().setSourceField(sourceFieldGroup);
        Field processed = applyFieldActions(session, session.head().getSourceField());
        session.head().setSourceField(processed);
    }

    /**
     * Checks for CopyTo actions and correctly sets the path for targetField by setting the indexes specified in each action
     */
    private void applyCopyToActions(List<Field> sourceFields, Mapping mapping) {
        for (Field sourceField : sourceFields) {

            if (sourceField instanceof FieldGroup) {
                applyCopyToActions(((FieldGroup) sourceField).getField(), mapping);
                continue;
            }

            if (sourceField.getActions() == null) {
                continue;
            }

            List<CopyTo> copyTos = sourceField.getActions().stream().filter(a -> a instanceof CopyTo).map(a -> (CopyTo) a).collect(
                Collectors.toList());
            if (copyTos.size() == 0) {
                return;
            }

            if (copyTos.stream().flatMap(c -> c.getIndexes().stream().filter(i -> i < 0)).count() > 0) {
                throw new IllegalArgumentException("Indexes must be >= 0");
            }

            /*
             * For each index present in CopyTo, set the corresponding index in the path.
             * each index of copyTo is supposed to have a counterpart in the path.
             */
            for (CopyTo copyTo : copyTos) {
                for (Field field : mapping.getOutputField()) {
                    AtlasPath path = new AtlasPath(field.getPath());
                    List<AtlasPath.SegmentContext> segments = path.getCollectionSegments(true);
                    for (int i = 0; i < copyTo.getIndexes().size(); i++) {
                        if (i < segments.size()) { // In case there are too many indexes specified
                            path.setCollectionIndex(i + 1, copyTo.getIndexes().get(i)); // +1 since 0 is the root segment
                        }
                    }
                    field.setPath(path.toString());
                }
                // The processor associated to this action is not real. It shall not execute, so remove the action.
                sourceField.getActions().remove(copyTo);
            }
        }
    }

    private void processSourceFields(DefaultAtlasSession session, List<Field> sourceFields)
        throws AtlasException {
        for (int i = 0; i < sourceFields.size(); i++) {
            Field sourceField = sourceFields.get(i);
            session.head().setSourceField(sourceField);
            if (sourceField instanceof FieldGroup) {
                processSourceFields(session, ((FieldGroup) sourceField).getField());
                Field processed = applyFieldActions(session, sourceField);
                session.head().setSourceField(processed);
                continue;
            }

            AtlasModule module = resolveModule(FieldDirection.SOURCE, sourceField);
            if (module == null) {
                AtlasUtil.addAudit(session, sourceField,
                    String.format("Module not found for docId '%s'", sourceField.getDocId()),
                    AuditStatus.ERROR, null);
                return;
            }
            if (!module.isSupportedField(sourceField)) {
                AtlasUtil.addAudit(session, sourceField,
                    String.format("Unsupported source field type '%s' for DataSource '%s'",
                        sourceField.getClass().getName(), module.getUri()),
                    AuditStatus.ERROR, null);
                return;
            }

            module.readSourceValue(session);
            Field processed = applyFieldActions(session, session.head().getSourceField());
            session.head().setSourceField(processed);
            sourceFields.set(i, processed);
        }
    }
}
