package org.qubership.integration.platform.chain.impl;

import lombok.Data;
import lombok.Setter;
import org.qubership.integration.platform.chain.model.*;

import java.util.Collection;
import java.util.Optional;

@Data
public class ChainImpl implements Chain {
    private String id;
    private String name;
    private String description;
    private String businessDescription;
    private String assumptions;
    private String outOfScope;
    private Collection<Element> elements;
    private Collection<Label> labels;
    private Collection<Connection> connections;
    private Collection<MaskedField> maskedFields;

    @Setter
    private Element defaultSwimlane;

    @Setter
    private Element reuseSwimlane;

    public Optional<Element> getDefaultSwimlane() {
        return Optional.ofNullable(defaultSwimlane);
    }

    public Optional<Element> getReuseSwimlane() {
        return Optional.ofNullable(reuseSwimlane);
    }
}
