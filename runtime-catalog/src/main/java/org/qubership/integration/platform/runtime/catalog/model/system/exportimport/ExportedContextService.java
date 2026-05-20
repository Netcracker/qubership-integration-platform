package org.qubership.integration.platform.runtime.catalog.model.system.exportimport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer.ExportableObjectWriterVisitor;

import java.io.IOException;
import java.util.zip.ZipOutputStream;

@Getter
@Setter

public class ExportedContextService extends ExportedSystemObject {

    public ExportedContextService(String id, ObjectNode objectNode) {
        super(id, objectNode);
    }

    @Override
    public void accept(ExportableObjectWriterVisitor visitor, ZipOutputStream zipOut, String entryPath) throws IOException {
        visitor.visit(this, zipOut, entryPath);
    }
}
