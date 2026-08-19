package org.qubership.integration.platform.io.readers.migrations.revert;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RevertMigration {

    int getVersion();

    ObjectNode revert(ObjectNode node);

    /**
     * Whether this migration applies to the given document. The shared revert pipeline runs every
     * registered migration over every exported document, so type-specific reverts must opt out of
     * documents they do not own. Defaults to {@code true}.
     */
    default boolean supportsDocument(ObjectNode node) {
        return true;
    }
}
