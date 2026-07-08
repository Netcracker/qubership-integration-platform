package org.qubership.integration.platform.runtime.catalog.adapters;

import org.qubership.integration.platform.chain.model.Folder;

import java.util.Optional;

public class FolderAdapter implements Folder {
    private final org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder folder;

    public FolderAdapter(org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Folder folder) {
        this.folder = folder;
    }

    @Override
    public String getId() {
        return folder.getId();
    }

    @Override
    public String getName() {
        return folder.getName();
    }

    @Override
    public String getDescription() {
        return folder.getDescription();
    }

    @Override
    public Optional<Folder> getParentFolder() {
        return Optional.ofNullable(folder.getParentFolder())
            .map(FolderAdapter::new);
    }
}
