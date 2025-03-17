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
import io.atlasmap.core.AtlasUtil;
import io.atlasmap.core.validate.BaseModuleValidationService;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModuleDetail;
import io.atlasmap.v2.*;
import io.atlasmap.xml.core.XmlFieldWriter;
import io.atlasmap.xml.inspect.XmlInspectionException;
import io.atlasmap.xml.inspect.XmlInspectionService;
import io.atlasmap.xml.module.XmlModule;
import io.atlasmap.xml.v2.*;
import org.qubership.integration.platform.engine.mapper.atlasmap.xml.QipAtlasXmlFieldWriter;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

@AtlasModuleDetail(
        name = "QipXmlAtlasModule",
        uri = "atlas:cip:xml",
        modes = { "SOURCE", "TARGET" },
        dataFormats = { "xml" },
        configPackages = { "org.qubership.integration.platform.engine.mapper.atlasmap" }
)
public class QipXmlAtlasModule extends ComplexMappingAtlasModule {
    private final XmlInspectionService inspectionService;

    public QipXmlAtlasModule() {
        super(new XmlModule() {
            protected org.w3c.dom.Document convertToXmlDocument(String source, boolean namespaced) throws AtlasException {
                if (source == null || source.isEmpty()) {
                    return null;
                }

                try {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(namespaced);
                    DocumentBuilder b = dbf.newDocumentBuilder();
                    return b.parse(new ByteArrayInputStream(source.getBytes("UTF-8")));
                } catch (Exception e) {
                    throw new AtlasException("Failed to parse XML document: " + e.getMessage(), e);
                }
            };
        });
        inspectionService = new XmlInspectionService();
    }

    @Override
    protected BaseModuleValidationService<?> getValidationService() {
        return new QipXmlValidationService(
                this.getClass().getAnnotation(AtlasModuleDetail.class),
                getConversionService(),
                getFieldActionService()
        );
    }

    @Override
    protected BiFunction<AtlasInternalSession, String, Document> getInspectionService() {
        return (session, source) -> {
            try {
                return convertComplexObjectsToFieldGroups(inspectionService.inspectXmlDocument(source));
            } catch (XmlInspectionException exception) {
                AtlasUtil.addAudit(session, getDocId(), exception.getMessage(), AuditStatus.ERROR, "");
                return AtlasXmlModelFactory.createXmlDocument();
            }
        };
    }

    @Override
    public void processPreTargetExecution(AtlasInternalSession session) throws AtlasException {
        XmlNamespaces xmlNs = null;
        String template = null;
        for (DataSource ds : session.getMapping().getDataSource()) {
            if (DataSourceType.TARGET.equals(ds.getDataSourceType()) && ds instanceof XmlDataSource
                    && (ds.getId() == null || ds.getId().equals(getDocId()))) {
                xmlNs = ((XmlDataSource) ds).getXmlNamespaces();
                template = ((XmlDataSource) ds).getTemplate();
            }
        }

        Map<String, String> nsMap = new HashMap<String, String>();
        if (xmlNs != null && xmlNs.getXmlNamespace() != null && !xmlNs.getXmlNamespace().isEmpty()) {
            for (XmlNamespace ns : xmlNs.getXmlNamespace()) {
                nsMap.put(ns.getAlias(), ns.getUri());
            }
        }

        XmlFieldWriter writer = new QipAtlasXmlFieldWriter(getClassLoader(), nsMap, template);
        session.setFieldWriter(getDocId(), writer);
    }

    private Document convertComplexObjectsToFieldGroups(Document document) throws XmlInspectionException {
        List<Field> documentFields = document.getFields().getField();
        if (documentFields.isEmpty() || !(documentFields.get(documentFields.size() - 1) instanceof XmlComplexType)) {
            throw new XmlInspectionException("Failed to extract document field");
        }
        Fields fields = new Fields();
        ((XmlComplexType) documentFields.get(documentFields.size() - 1)).getXmlFields().getXmlField().stream()
                .map(this::processComplexFields)
                .forEach(fields.getField()::add);
        document.setFields(fields);
        return document;
    }

    private Field processComplexFields(Field field) {
        if (field instanceof XmlComplexType complexType) {
            FieldGroup group = new FieldGroup();
            AtlasModelFactory.copyField(complexType, group, true);
            group.setValue(group.getValue());
            complexType.getXmlFields().getXmlField().stream()
                    .map(this::processComplexFields)
                    .forEach(group.getField()::add);
            return group;
        } else {
            return field;
        }
    }
}
