package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.runtime.catalog.service.exportimport.ExportImportConstants.ARCH_PARENT_DIR;

@Component
public class ArchiveWriter {
    private final ExportableObjectWriterVisitor exportableObjectWriterVisitor;

    @Autowired
    public ArchiveWriter(ExportableObjectWriterVisitor exportableObjectWriterVisitor) {
        this.exportableObjectWriterVisitor = exportableObjectWriterVisitor;
    }

    public byte[] writeArchive(List<? extends ExportableObject> exportedSystems) {
        try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
            try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                for (ExportableObject exportedSystem : exportedSystems) {
                    String entryPath = ARCH_PARENT_DIR + File.separator + exportedSystem.getId() + File.separator;
                    exportedSystem.accept(exportableObjectWriterVisitor, zipOut, entryPath);
                }
            }
            return fos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create archive: " + e.getMessage(), e);
        }
    }
}
