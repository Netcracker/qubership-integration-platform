package org.qubership.integration.platform.io.readers.migrations.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.node.ObjectNode;


@Slf4j
@Component
public class V100MCPServiceImportFileMigration implements MCPServiceImportFileMigration {
    @Override
    public int getVersion() {
        return 100;
    }

    @Override
    public ObjectNode makeMigration(ObjectNode fileNode) throws JacksonException {
        log.debug("Initial MCP service migration V100");
        return fileNode;
    }
}
