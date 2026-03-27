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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.atlasmap.api.AtlasException;
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.validate.BaseModuleValidationService;
import io.atlasmap.json.core.JsonFieldWriter;
import io.atlasmap.json.inspect.JsonInspectionException;
import io.atlasmap.json.inspect.JsonInspectionService;
import io.atlasmap.json.module.JsonModule;
import io.atlasmap.json.v2.AtlasJsonModelFactory;
import io.atlasmap.json.v2.JsonComplexType;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.*;
import org.qubership.integration.platform.engine.mapper.atlasmap.json.QipAtlasJsonFieldReader;
import org.qubership.integration.platform.engine.mapper.atlasmap.json.QipJsonInspectionService;
import org.qubership.integration.platform.engine.mapper.atlasmap.json.QipJsonInstanceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;

@AtlasModuleDetail(
        name = "QipJsonAtlasModule",
        uri = "atlas:cip:json",
        modes = { "SOURCE", "TARGET" },
        dataFormats = { "json" },
        configPackages = { "org.qubership.integration.platform.engine.mapper.atlasmap" }
)
public class QipJsonAtlasModule extends ComplexMappingAtlasModule {
    private static final Logger LOG = LoggerFactory.getLogger(QipJsonAtlasModule.class);

    private final JsonInspectionService inspectionService;

    public QipJsonAtlasModule() {
        super(new JsonModule());
        inspectionService = new QipJsonInspectionService(new QipJsonInstanceInspector());
    }

    @Override
    protected BaseModuleValidationService<?> getValidationService() {
        return new QipJsonValidationService(
                this.getClass().getAnnotation(AtlasModuleDetail.class),
                getConversionService(),
                getFieldActionService()
        );
    }

    @Override
    protected BiFunction<AtlasInternalSession, String, Document> getInspectionService() {
        return (session, source) -> {
            try {
                return convertComplexObjectsToFieldGroups(inspectionService.inspectJsonDocument(source));
            } catch (JsonInspectionException exception) {
                AtlasUtil.addAudit(session, getDocId(), exception.getMessage(), AuditStatus.ERROR, "");
                return AtlasJsonModelFactory.createJsonDocument();
            }
        };
    }

    @Override
    public void processPreTargetExecution(AtlasInternalSession session) throws AtlasException {
        super.processPreTargetExecution(session);
        JsonFieldWriter writer = session.getFieldWriter(getDocId(), JsonFieldWriter.class);
        ObjectMapper objectMapper = writer.getObjectMapper();
        objectMapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

    @Override
    public void processPreSourceExecution(AtlasInternalSession session) throws AtlasException {
        super.processPreSourceExecution(session);
        Object sourceDocument = session.getSourceDocument(getDocId());
        String sourceDocumentString = null;
        if (sourceDocument == null || !(sourceDocument instanceof String)) {
            AtlasUtil.addAudit(session, getDocId(), String.format(
                            "Null or non-String source document: docId='%s'", getDocId()),
                    AuditStatus.WARN, null);
        } else {
            sourceDocumentString = String.class.cast(sourceDocument);
        }
        QipAtlasJsonFieldReader fieldReader = new QipAtlasJsonFieldReader(getConversionService());
        fieldReader.setDocument(sourceDocumentString);
        session.setFieldReader(getDocId(), fieldReader);
    }

    @Override
    public void processPostTargetExecution(AtlasInternalSession session) throws AtlasException {
        try {
            JsonFieldWriter writer = session.getFieldWriter(getDocId(), JsonFieldWriter.class);
            if (writer != null && writer.getRootNode() != null) {
                ObjectMapper objectMapper = writer.getObjectMapper();
                QipJsonAtlasModuleOptions options = QipJsonAtlasModuleOptionsDecoder.decode(getUri());
                JsonNode documentNode = writer.getRootNode();
                if (options.isSerializeTargetDocument()) {
                    String outputBody = objectMapper.writeValueAsString(documentNode);
                    session.setTargetDocument(getDocId(), outputBody);
                    if (LOG.isDebugEnabled()) {
                        LOG.debug(String.format("processPostTargetExecution converting JsonNode to string size=%s",
                                outputBody.length()));
                    }
                } else {
                    session.setTargetDocument(getDocId(), documentNode);
                }
            } else {
                AtlasUtil.addAudit(session, getDocId(), String
                                .format("No target document created for DataSource:[id=%s, uri=%s]", getDocId(), this.getUri()),
                        AuditStatus.WARN, null);
            }
            session.removeFieldWriter(getDocId());

            if (LOG.isDebugEnabled()) {
                LOG.debug("{}: processPostTargetExecution completed", getDocId());
            }
        } catch (JsonProcessingException exception) {
            AtlasUtil.addAudit(session, getDocId(), exception.getMessage(), AuditStatus.ERROR, null);
        }
    }

    private Document convertComplexObjectsToFieldGroups(Document document) {
        Fields fields = new Fields();
        document.getFields().getField().stream()
                .map(this::processComplexFields)
                .forEach(fields.getField()::add);
        document.setFields(fields);
        return document;
    }

    private Field processComplexFields(Field field) {
        if (field instanceof JsonComplexType complexType) {
            FieldGroup group = new FieldGroup();
            AtlasModelFactory.copyField(complexType, group, true);
            group.setValue(group.getValue());
            complexType.getJsonFields().getJsonField().stream()
                    .map(this::processComplexFields)
                    .forEach(group.getField()::add);
            return group;
        } else {
            return field;
        }
    }
}

