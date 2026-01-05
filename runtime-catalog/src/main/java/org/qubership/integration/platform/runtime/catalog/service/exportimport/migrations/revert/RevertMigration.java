package org.qubership.integration.platform.runtime.catalog.service.exportimport.migrations.revert;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface RevertMigration {

    int getVersion();

    ObjectNode revert(ObjectNode node);
}
