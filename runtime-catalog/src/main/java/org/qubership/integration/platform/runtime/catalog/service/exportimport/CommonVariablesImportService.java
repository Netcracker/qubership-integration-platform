/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.service.exportimport;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionsConfig;
import org.qubership.integration.platform.catalog.model.exportimport.instructions.ImportInstructionsDTO;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.instructions.ImportInstructionResult;
import org.qubership.integration.platform.runtime.catalog.model.exportimport.variable.ImportVariablesResult;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.ImportMode;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.variable.ImportVariablePreviewResult;
import org.qubership.integration.platform.runtime.catalog.rest.v3.dto.exportimport.variable.VariablesCommitRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.*;

@Slf4j
@Service
public class CommonVariablesImportService {

    private static final String VARIABLES_ARCH_PARENT_DIR = "variables";
    private static final String COMMON_VARIABLES_FILE_NAME = "common-variables.yaml";
    private static final String COMMON_VARIABLES_IMPORT_ENDPOINT = "/v2/common-variables/import";
    private static final String COMMON_VARIABLES_PREVIEW_ENDPOINT = "/v1/common-variables/preview";
    private static final String COMMON_VARIABLES_IMPORT_INSTRUCTIONS_ENDPOINT = "/v1/common-variables/import-instructions";
    private static final String COMMON_VARIABLES_IMPORT_INSTRUCTIONS_CONFIG_ENDPOINT = COMMON_VARIABLES_IMPORT_INSTRUCTIONS_ENDPOINT + "/config";
    private static final String COMMON_VARIABLES_IMPORT_INSTRUCTIONS_UPLOAD_ENDPOINT = COMMON_VARIABLES_IMPORT_INSTRUCTIONS_ENDPOINT + "/upload";

    private final RestTemplate restTemplate;
    private final String commonVariablesUrl;
    private final ImportSessionService importProgressService;

    @Autowired
    public CommonVariablesImportService(
            @Qualifier("restTemplateMS") RestTemplate restTemplate,
            @Value("${qip.internal-services.variables-management}") String variablesManagementHost,
            ImportSessionService importProgressService
    ) {
        this.restTemplate = restTemplate;
        this.commonVariablesUrl = "http://" + variablesManagementHost + ":8080";
        this.importProgressService = importProgressService;
    }

    public ImportInstructionsDTO getCommonVariablesImportInstructions() {
        return restTemplate.getForEntity(
                commonVariablesUrl + COMMON_VARIABLES_IMPORT_INSTRUCTIONS_ENDPOINT,
                ImportInstructionsDTO.class
        ).getBody();
    }

    public ImportInstructionsConfig getCommonVariablesImportInstructionsConfig() {
        return restTemplate.getForEntity(
                commonVariablesUrl + COMMON_VARIABLES_IMPORT_INSTRUCTIONS_CONFIG_ENDPOINT,
                ImportInstructionsConfig.class
        ).getBody();
    }

    public List<ImportInstructionResult> uploadCommonVariablesImportInstructions(
            String fileName,
            byte[] fileContent,
            Set<String> labels
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (CollectionUtils.isNotEmpty(labels)) {
            headers.put("labels", new ArrayList<>(labels));
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fileContent) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        return restTemplate.exchange(
                commonVariablesUrl + COMMON_VARIABLES_IMPORT_INSTRUCTIONS_UPLOAD_ENDPOINT,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<List<ImportInstructionResult>>() {}
        ).getBody();
    }

    public void deleteCommonVariablesImportInstructions(Collection<String> variableIds) {
        restTemplate.exchange(
                commonVariablesUrl + COMMON_VARIABLES_IMPORT_INSTRUCTIONS_ENDPOINT,
                HttpMethod.DELETE,
                new HttpEntity<>(variableIds),
                Void.class
        );
    }

    public List<ImportVariablePreviewResult> getCommonVariablesImportPreview(File importDirectory) {
        File[] commonVariablesFiles = new File(importDirectory + File.separator + VARIABLES_ARCH_PARENT_DIR)
                .listFiles(file -> COMMON_VARIABLES_FILE_NAME.equals(file.getName()));

        if (ArrayUtils.isEmpty(commonVariablesFiles)) {
            return Collections.emptyList();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.put("file", Collections.singletonList(new FileSystemResource(commonVariablesFiles[0])));

        return restTemplate.exchange(
                commonVariablesUrl + COMMON_VARIABLES_PREVIEW_ENDPOINT,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<List<ImportVariablePreviewResult>>() {}
        ).getBody();
    }

    public ImportVariablesResult importCommonVariables(
            File importDirectory,
            VariablesCommitRequest variablesCommitRequest,
            String importId
    ) {
        if (variablesCommitRequest.getImportMode() == ImportMode.NONE) {
            return new ImportVariablesResult();
        }

        File[] commonVariablesFiles = new File(importDirectory + File.separator + VARIABLES_ARCH_PARENT_DIR)
                .listFiles(file -> COMMON_VARIABLES_FILE_NAME.equals(file.getName()));
        if (ArrayUtils.isEmpty(commonVariablesFiles)) {
            return new ImportVariablesResult();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new FileSystemResource(commonVariablesFiles[0]));
        if (variablesCommitRequest.getImportMode() == ImportMode.PARTIAL && variablesCommitRequest.getVariablesNames() != null) {
            bodyBuilder.part("variablesNames", String.join(",", variablesCommitRequest.getVariablesNames()));
        }

        ImportVariablesResult response = restTemplate.exchange(
                commonVariablesUrl + COMMON_VARIABLES_IMPORT_ENDPOINT,
                HttpMethod.POST,
                new HttpEntity<>(bodyBuilder.build(), headers),
                new ParameterizedTypeReference<ImportVariablesResult>() {}
        ).getBody();

        importProgressService.calculateImportStatus(importId, 1, 1, 0, ImportSessionService.COMMON_VARIABLES_IMPORT_PERCENTAGE_THRESHOLD);

        return response;
    }
}
