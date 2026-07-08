package org.qubership.integration.platform.chain.impl;

import org.qubership.integration.platform.chain.model.Chain;
import org.qubership.integration.platform.chain.model.Connection;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.chain.model.ServiceEnvironment;

import java.util.*;

public class ElementBuilder {
    private String id;
    private String originalId;
    private String name;
    private String description;
    private String type;
    private Element parent;
    private Element swimlane;
    private Map<String, Object> properties;
    private Collection<Element> children;
    private Collection<Connection> inputConnections;
    private Collection<Connection> outputConnections;
    private Chain chain;
    private ServiceEnvironment serviceEnvironment;
    private boolean container;

    private ElementBuilder() {}

    public static ElementBuilder createNew() {
        return new ElementBuilder();
    }

    public ElementBuilder from(Element element) {
        this.id = element.getId();
        this.originalId = element.getId();
        this.name = element.getName();
        this.description = element.getDescription();
        this.type = element.getType();
        this.parent = element.getParent().orElse(null);
        this.swimlane = element.getSwimlane().orElse(null);
        this.properties = Optional.ofNullable(element.getProperties()).map(HashMap::new).orElseGet(HashMap::new);
        this.children = Optional.ofNullable(element.getChildren()).map(ArrayList::new).orElseGet(ArrayList::new);
        this.inputConnections = Optional.ofNullable(element.getInputConnections()).map(ArrayList::new).orElseGet(ArrayList::new);
        this.outputConnections = Optional.ofNullable(element.getOutputConnections()).map(ArrayList::new).orElseGet(ArrayList::new);
        this.chain = element.getChain();
        this.serviceEnvironment = element.getEnvironment().orElse(null);
        this.container = element.isContainer();
        return this;
    }

    public ElementBuilder id(String id) {
        this.id = id;
        return this;
    }

    public ElementBuilder originalId(String originalId) {
        this.originalId = originalId;
        return this;
    }

    public ElementBuilder name(String name) {
        this.name = name;
        return this;
    }

    public ElementBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ElementBuilder type(String type) {
        this.type = type;
        return this;
    }

    public ElementBuilder parent(Element parent) {
        this.parent = parent;
        return this;
    }

    public ElementBuilder swimlane(Element swimlane) {
        this.swimlane = swimlane;
        return this;
    }

    public ElementBuilder properties(Map<String, Object> properties) {
        this.properties = properties;
        return this;
    }

    public ElementBuilder children(List<? extends Element> children) {
        this.children = new ArrayList<>(children);
        return this;
    }

    public ElementBuilder inputConnections(List<? extends Connection> inputConnections) {
        this.inputConnections = new ArrayList<>(inputConnections);
        return this;
    }

    public ElementBuilder outputConnections(List<? extends Connection> outputConnections) {
        this.outputConnections = new ArrayList<>(outputConnections);
        return this;
    }

    public ElementBuilder chain(Chain chain) {
        this.chain = chain;
        return this;
    }

    public ElementBuilder serviceEnvironment(ServiceEnvironment serviceEnvironment) {
        this.serviceEnvironment = serviceEnvironment;
        return this;
    }

    public ElementBuilder container(boolean container) {
        this.container = container;
        return this;
    }

    public Element build() {
        ElementImpl element = new ElementImpl();
        element.setId(id);
        element.setOriginalId(originalId);
        element.setName(name);
        element.setDescription(description);
        element.setType(type);
        element.setParent(parent);
        element.setSwimlane(swimlane);
        element.setProperties(Optional.ofNullable(properties).orElseGet(HashMap::new));
        element.setChildren(Optional.ofNullable(children).orElseGet(ArrayList::new));
        element.setInputConnections(Optional.ofNullable(inputConnections).orElseGet(ArrayList::new));
        element.setOutputConnections(Optional.ofNullable(outputConnections).orElseGet(ArrayList::new));
        element.setChain(chain);
        element.setServiceEnvironment(serviceEnvironment);
        element.setContainer(container);
        return element;
    }
}
