package org.qubership.integration.platform.runtime.catalog.service.exportimport.serializer;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.ServiceExportException;
import org.qubership.integration.platform.runtime.catalog.model.system.exportimport.ExportableObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipOutputStream;

import static org.qubership.integration.platform.io.model.exportimport.ExportImportConstants.ARCH_PARENT_DIR;

@Slf4j
@Component
public class ArchiveWriter {
    private final ExportableObjectWriterVisitor exportableObjectWriterVisitor;

    @Autowired
    public ArchiveWriter(ExportableObjectWriterVisitor exportableObjectWriterVisitor) {
        this.exportableObjectWriterVisitor = exportableObjectWriterVisitor;
    }

    /**
     * One unexportable service costs that service alone. A file name is built here, inside the loop over every service
     * of the archive, so a refusal that escaped it left the operator no way to extract any service at all. The name is
     * built before the first entry of that service is opened, so a skipped service leaves nothing half written.
     */
    public byte[] writeArchive(List<? extends ExportableObject> exportedSystems) {
        try (ByteArrayOutputStream fos = new ByteArrayOutputStream()) {
            try (ZipOutputStream zipOut = new ZipOutputStream(fos)) {
                for (ExportableObject exportedSystem : exportedSystems) {
                    String entryPath = ARCH_PARENT_DIR + File.separator + exportedSystem.getId() + File.separator;
                    try {
                        exportedSystem.accept(exportableObjectWriterVisitor, zipOut, entryPath);
                    } catch (ServiceExportException e) {
                        log.error("Service {} is left out of the archive. {}", exportedSystem.getId(), e.getMessage());
                    }
                }
            }
            return fos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create archive: " + e.getMessage(), e);
        }
    }
}
