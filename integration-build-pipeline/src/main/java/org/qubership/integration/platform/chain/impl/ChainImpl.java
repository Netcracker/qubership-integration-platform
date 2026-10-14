package org.qubership.integration.platform.chain.impl;

import lombok.Data;
import lombok.Setter;
import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Data
public class ChainImpl implements ImportChain {
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

    private String lastImportHash;
    private String overridesChainId;
    private String overriddenByChainId;
    private boolean overridden;
    private List<String> deployments = new ArrayList<>();
    private ChainCommitRequestAction deployAction;

    @Setter
    private Element defaultSwimlane;

    @Setter
    private Element reuseSwimlane;

    @Setter
    private Folder parentFolder;

    public Optional<Element> getDefaultSwimlane() {
        return Optional.ofNullable(defaultSwimlane);
    }

    public Optional<Element> getReuseSwimlane() {
        return Optional.ofNullable(reuseSwimlane);
    }

    @Override
    public Optional<Folder> getParentFolder() {
        return Optional.ofNullable(parentFolder);
    }
}
