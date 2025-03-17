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

package org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.xml;

import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.DataType;
import org.w3c.dom.Document;

import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class XmlTemplateBuilder {
    private DataType type;

    public XmlTemplateBuilder setType(DataType type) {
        this.type = type;
        return this;
    }

    public String build() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        Document document = documentBuilder.newDocument();
        XmlTemplateCollectorContext ctx = XmlTemplateCollectorContext.builder()
                .document(document)
                .definitions(Collections.emptyList())
                .namespaces(Collections.emptyMap())
                .build();
        XmlTemplateCollector xmlTemplateCollector = new XmlTemplateCollector();
        type.accept(xmlTemplateCollector, ctx).forEach(document::appendChild);
        return getStringRepresentation(document);
    }

    private String getStringRepresentation(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        Writer out = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toString();
    }
}
