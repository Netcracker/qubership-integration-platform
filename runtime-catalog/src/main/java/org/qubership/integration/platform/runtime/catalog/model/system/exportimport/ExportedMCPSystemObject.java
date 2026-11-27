package org.qubership.integration.platform.runtime.catalog.model.system.exportimport;

import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ExportableObjectWriterVisitor;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.zip.ZipOutputStream;


public class ExportedMCPSystemObject extends ExportedSystemObject {
    public ExportedMCPSystemObject(String id, ObjectNode objectNode) {
        super(id, objectNode);
    }

    @Override
    public void accept(ExportableObjectWriterVisitor visitor, ZipOutputStream zipOut, String entryPath) throws IOException {
        visitor.visit(this, zipOut, entryPath);
    }
}
