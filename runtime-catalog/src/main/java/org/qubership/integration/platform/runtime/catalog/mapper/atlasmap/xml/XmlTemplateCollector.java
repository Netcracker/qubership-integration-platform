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

import org.codehaus.plexus.util.StringUtils;
import org.qubership.integration.platform.runtime.catalog.mapper.DataTypeUtils;
import org.qubership.integration.platform.runtime.catalog.mapper.metadata.MetadataUtils;
import org.qubership.integration.platform.runtime.catalog.mapper.metadata.XMLNamespace;
import org.qubership.integration.platform.runtime.catalog.model.mapper.datatypes.*;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.definition.Attribute;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class XmlTemplateCollector implements DataTypeVisitor<Collection<Node>, XmlTemplateCollectorContext, Exception> {
    @Override
    public Collection<Node> visitAllOfType(AllOfType type, XmlTemplateCollectorContext ctx) throws Exception {
        return visitCompoundType(type, ctx);
    }

    @Override
    public Collection<Node> visitAnyOfType(AnyOfType type, XmlTemplateCollectorContext ctx) throws Exception {
        return visitCompoundType(type, ctx);
    }

    @Override
    public Collection<Node> visitOneOfType(OneOfType type, XmlTemplateCollectorContext ctx) throws Exception {
        return visitCompoundType(type, ctx);
    }

    @Override
    public Collection<Node> visitNullType(NullType type, XmlTemplateCollectorContext ctx) throws Exception {
        return Collections.singletonList(ctx.getDocument().createTextNode(""));
    }

    @Override
    public Collection<Node> visitBooleanType(BooleanType type, XmlTemplateCollectorContext ctx) throws Exception {
        return Collections.singletonList(ctx.getDocument().createTextNode(""));
    }

    @Override
    public Collection<Node> visitIntegerType(IntegerType type, XmlTemplateCollectorContext ctx) throws Exception {
        return Collections.singletonList(ctx.getDocument().createTextNode(""));
    }

    @Override
    public Collection<Node> visitStringType(StringType type, XmlTemplateCollectorContext ctx) throws Exception {
        return Collections.singletonList(ctx.getDocument().createTextNode(""));
    }

    @Override
    public Collection<Node> visitArrayType(ArrayType type, XmlTemplateCollectorContext ctx) throws Exception {
        return type.getItemType().accept(this, addDefinitionsToContext(ctx, type.getDefinitions()));
    }

    @Override
    public Collection<Node> visitObjectType(ObjectType type, XmlTemplateCollectorContext ctx) throws Exception {
        List<Node> result = new ArrayList<>();
        for (Attribute attribute : type.getSchema().getAttributes()) {
            if (isXmlTextNode(attribute) || isXmlAttribute(attribute)) {
                continue;
            }
            Node node = buildElementNode(attribute, addDefinitionsToContext(ctx, type.getDefinitions()));
            result.add(node);
        }
        return result;
    }

    @Override
    public Collection<Node> visitReferenceType(ReferenceType type, XmlTemplateCollectorContext ctx) throws Exception {
        Map<String, TypeDefinition> definitionMap = Stream.of(ctx.getDefinitions(), type.getDefinitions())
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(TypeDefinition::getId, Function.identity()));
        DataTypeUtils.ResolveResult resolveResult = DataTypeUtils.resolveType(type, definitionMap);
        return resolveResult.type().accept(this, ctx.toBuilder().definitions(resolveResult.definitionMap().values()).build());
    }

    private Collection<Node> visitCompoundType(CompoundType type, XmlTemplateCollectorContext ctx) throws Exception {
        List<Node> result = new ArrayList<>();
        for (DataType subType : type.getTypes()) {
            result.addAll(subType.accept(this, addDefinitionsToContext(ctx, type.getDefinitions())));
        }
        return result;
    }

    private Node buildElementNode(Attribute attribute, XmlTemplateCollectorContext ctx) throws Exception {
        String namespace = getNamespace(attribute);
        Collection<XMLNamespace> namespaces = getXmlNamespaces(attribute, ctx);
        XmlTemplateCollectorContext subCtx = addNamespacesToContext(ctx, namespaces);
        String namespaceUri = subCtx.getNamespaces().getOrDefault(namespace, "");
        Element element = subCtx.getDocument().createElementNS(namespaceUri, attribute.getName());
        addNamespacesToElement(element, namespaces);
        for (Node node : attribute.getType().accept(this, subCtx)) {
            if (node instanceof Attr) {
                element.setAttributeNodeNS((Attr) node);
            } else {
                element.appendChild(node);
            }
        }
        return element;
    }

    private static XmlTemplateCollectorContext addDefinitionsToContext(
            XmlTemplateCollectorContext ctx,
            Collection<TypeDefinition> definitions
    ) {
        List<TypeDefinition> newDefinitions = new ArrayList<>(ctx.getDefinitions());
        newDefinitions.addAll(definitions);
        return ctx.toBuilder().definitions(newDefinitions).build();
    }

    private static XmlTemplateCollectorContext addNamespacesToContext(
            XmlTemplateCollectorContext ctx,
            Collection<XMLNamespace> namespaces
    ) {
        Map<String, String> namespacesMap = new HashMap<>(ctx.getNamespaces());
        namespaces.forEach(namespace -> namespacesMap.put(namespace.alias(), namespace.uri()));
        return ctx.toBuilder().namespaces(namespacesMap).build();
    }

    private static boolean isXmlAttribute(Attribute attribute) {
        return attribute.getName().startsWith("@");
    }

    private static boolean isXmlTextNode(Attribute attribute) {
        return attribute.getName().equals("#text");
    }

    private static String getNamespace(Attribute attribute) {
        String s = attribute.getName();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        int index = s.indexOf(":");
        return index < 0 ? null : s.substring(0, index);
    }

    private static Collection<XMLNamespace> getXmlNamespaces(Attribute attribute, XmlTemplateCollectorContext ctx) {
        DataType type = attribute.getType();
        Map<String, TypeDefinition> definitionMap = ctx.getDefinitions().stream()
                .collect(Collectors.toMap(TypeDefinition::getId, Function.identity()));
        definitionMap = DataTypeUtils.updateDefinitionMapFromType(definitionMap, type);
        DataTypeUtils.ResolveResult resolveResult = DataTypeUtils.resolveType(type, definitionMap);
        return MetadataUtils.getXmlNamespaces(resolveResult.type().getMetadata());
    }

    private static void addNamespacesToElement(Element node, Collection<XMLNamespace> namespaces) {
        for (XMLNamespace namespace : namespaces) {
            String attributeName = "xmlns";
            if (!StringUtils.isBlank(namespace.alias())) {
                attributeName += ":" + namespace.alias();
            }
            node.setAttributeNS("http://www.w3.org/2000/xmlns/", attributeName, namespace.uri());
        }
    }
}
