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

package org.qubership.integration.platform.engine.mapper.atlasmap.xml;

import io.atlasmap.api.AtlasException;
import io.atlasmap.xml.core.XmlFieldWriter;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.LinkedHashMap;
import java.util.Map;

public class QipAtlasXmlFieldWriter extends XmlFieldWriter {
    public QipAtlasXmlFieldWriter(ClassLoader classLoader, Map<String, String> namespaces, String seedDocument) throws AtlasException {
        super(classLoader, namespaces, seedDocument);
    }

    @Override
    protected void seedDocumentNamespaces(Document document) {
        if (namespaces == null) {
            namespaces = new LinkedHashMap<>();
        }
        NodeList nodeList = document.getChildNodes();
        addNamespacesFromNodes(nodeList);
    }

    private void addNamespacesFromNodes(NodeList nodeList) {
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                NamedNodeMap namedNodeMap = node.getAttributes();
                for (int x = 0; x < namedNodeMap.getLength(); x++) {
                    Node attribute = namedNodeMap.item(x);
                    if (attribute.getNamespaceURI() != null) {
                        if (attribute.getLocalName().equals("xmlns")) {
                            namespaces.put("", attribute.getNodeValue());
                        } else {
                            namespaces.put(attribute.getLocalName(), attribute.getNodeValue());
                        }
                    } else if (attribute.getNodeName().equals("xmlns")) {
                        namespaces.put("", attribute.getNodeValue());
                    } else if (attribute.getNodeName().startsWith("xmlns:")) {
                        String alias = attribute.getNodeName().substring("xmlns:".length());
                        namespaces.put(alias, attribute.getNodeValue());
                    }
                }
                addNamespacesFromNodes(node.getChildNodes());
            }
        }
    }
}
