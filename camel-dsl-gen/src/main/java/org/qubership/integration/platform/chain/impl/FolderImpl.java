package org.qubership.integration.platform.chain.impl;

import lombok.Data;
import lombok.Setter;
import org.qubership.integration.platform.chain.model.Folder;

import java.util.Optional;

@Data
public class FolderImpl implements Folder {
    private String id;
    private String name;
    private String description;

    @Setter
    private Folder parentFolder;

    @Override
    public Optional<Folder> getParentFolder() {
        return Optional.ofNullable(parentFolder);
    }
}
