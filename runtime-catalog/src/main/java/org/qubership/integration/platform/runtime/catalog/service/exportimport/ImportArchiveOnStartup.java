package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Paths;
import java.util.Collections;

@Slf4j
@Component
public class ImportArchiveOnStartup implements ApplicationListener<ContextRefreshedEvent> {

    private final GeneralImportService generalImportService;

    @Autowired
    public ImportArchiveOnStartup(GeneralImportService generalImportService) {
        this.generalImportService = generalImportService;
    }

    @SneakyThrows
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        File archive = Paths.get("/import/data.zip").toFile();
        if (!archive.exists()) {
            log.warn("Didn't find archive");
            return;
        }

        FileInputStream input = new FileInputStream(archive);
        MultipartFile multipartFile = new MockMultipartFile("file",
                archive.getName(), "text/plain", IOUtils.toByteArray(input));

        log.info("Importing archive {}", multipartFile.getOriginalFilename());
        generalImportService.importFileAsync(multipartFile, new ImportRequest(), Collections.emptySet(), false);
    }
}
