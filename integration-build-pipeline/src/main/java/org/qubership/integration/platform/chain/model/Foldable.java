package org.qubership.integration.platform.chain.model;

import java.util.Optional;

public interface Foldable {
    Optional<Folder> getParentFolder();
}
