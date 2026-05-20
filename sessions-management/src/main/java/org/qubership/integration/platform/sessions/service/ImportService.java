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

package org.qubership.integration.platform.sessions.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.opensearch.SessionElementElastic;
import org.qubership.integration.platform.sessions.exception.ImportConflictException;
import org.qubership.integration.platform.sessions.exception.ImportException;
import org.qubership.integration.platform.sessions.mapper.SessionElementMapper;
import org.qubership.integration.platform.sessions.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.sessions.properties.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.sessions.properties.sessions.SessionsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class ImportService {
    private final String indexName;

    private final int bulkRequestMaxSizeBytes;
    private final int bulkRequestPayloadSizeThresholdBytes;
    private final int bulkRequestElementsCountThreshold;

    private final ObjectMapper objectMapper;
    private final SessionElementMapper elementMapper;
    private final OpenSearchClientSupplier openSearchClientSupplier;
    private final SessionService sessionService;

    @Autowired
    public ImportService(ObjectMapper objectMapper,
                         SessionElementMapper elementMapper,
                         SessionsProperties sessionsProperties,
                         OpenSearchClientSupplier openSearchClientSupplier,
                         OpenSearchProperties openSearchProperties,
                         SessionService sessionService) {
        this.indexName = openSearchProperties.index().elements().name();
        this.objectMapper = objectMapper;
        this.elementMapper = elementMapper;
        this.openSearchClientSupplier = openSearchClientSupplier;
        this.sessionService = sessionService;

        this.bulkRequestMaxSizeBytes = sessionsProperties.bulkRequest().maxSizeKb() * 1024;
        this.bulkRequestPayloadSizeThresholdBytes = sessionsProperties.bulkRequest().payloadSizeThresholdKb() * 1024;
        this.bulkRequestElementsCountThreshold = sessionsProperties.bulkRequest().elementsCountThreshold();
    }

    public List<Session> importSessions(MultipartFile[] files) {
        List<Session> resultSessions = new ArrayList<>();
        for (MultipartFile file : files) {
            List<Session> sessions;
            try {
                sessions = Arrays.asList(objectMapper.readValue(new String(file.getBytes()), Session[].class));
            } catch (IOException error) {
                log.error("Error while reading file: {}", error.getMessage());
                throw new ImportException("Error while reading file " + file.getOriginalFilename(), error);
            }

            checkExisting(file.getOriginalFilename(), sessions, resultSessions);

            log.debug("Found {} sessions in file {}", sessions.size(), file.getName());
            sessions.forEach(session -> session.setChainId(null));
            sessions.forEach(session -> session.setImportedSession(true));
            List<SessionElementElastic> sessionElements = elementMapper.toElements(sessions);

            writeElements(sessionElements);
            resultSessions.addAll(sessions);
        }
        resultSessions.forEach(session -> session.setSessionElements(null));
        return resultSessions;
    }

    private void checkExisting(String filename, List<Session> sessions, List<Session> resultSessions) {
        Set<String> existingIds = new HashSet<>();
        for (Session session : sessions) {
            if (sessionService.findById(session.getId(), SessionService.SESSION_ID_KEY, true, false) != null // Session in db already exists
                    || sessions.stream().filter(s -> s.getId().equals(session.getId())).count() > 1 // Session in current file mentioned twice and more
                    || resultSessions.stream().anyMatch(s -> s.getId().equals(session.getId())) // Session in current import in different file (In case it's not yet written in opensearch)
                    ) {
                existingIds.add(session.getId());
            }
        }
        if (!existingIds.isEmpty()) {
            log.error("File {} can't be imported because of sessions duplicates: {}", filename, existingIds);
            throw new ImportConflictException(String.format("File %s can't be imported because of sessions duplicates: %s", filename, existingIds));
        }
    }

    private void writeElements(List<SessionElementElastic> sessionElements) {
        int bulkRequestSize = 0;

        byte[] payload;
        int payloadSize;
        boolean needToExecuteBulk;
        List<BulkOperation> updateRequests = new ArrayList<>();

        Iterator<SessionElementElastic> iterator = sessionElements.iterator();
        while (iterator.hasNext()) {
            SessionElementElastic element = iterator.next();
            try {
                payload = objectMapper.writeValueAsBytes(element);
            } catch (JsonProcessingException error) {
                log.error("Failed to parse sessions write request. Element skipped");
                throw new ImportException("Failed to parse sessions write request on element "
                        + element.getElementName() + " in chain "
                        + element.getChainName(), error);
            }

            payloadSize = payload.length;
            BulkOperation request = new BulkOperation.Builder()
                    .index(IndexOperation.of(io -> io
                            .index(openSearchClientSupplier.normalize(indexName))
                            .id(element.getId())
                            .requireAlias(true)
                            .document(element)
                    ))
                    .build();

            try {
                if (payloadSize >= bulkRequestPayloadSizeThresholdBytes || sessionElements.size() <= bulkRequestElementsCountThreshold) {
                    executeBulk(new ArrayList<>(List.of(request)));
                } else {
                    updateRequests.add(request);
                    bulkRequestSize += payloadSize;
                }

                needToExecuteBulk =
                        bulkRequestSize >= bulkRequestMaxSizeBytes
                                || (!iterator.hasNext() && !updateRequests.isEmpty());
                if (needToExecuteBulk) {
                    executeBulk(updateRequests);
                    bulkRequestSize = 0;
                }
            } catch (Exception error) {
                log.error("While sessions writing an error has occurred", error);
                throw new ImportException("Import was failed while saving to opensearch", error);
            }
        }
    }

    private void executeBulk(List<BulkOperation> updateRequests) throws IOException {
        BulkRequest bulkRequest = new BulkRequest.Builder()
                .index(openSearchClientSupplier.normalize(indexName))
                .requireAlias(true)
                .operations(updateRequests)
                .build();
        BulkResponse bulk = openSearchClientSupplier.getClient().bulk(bulkRequest);
        updateRequests.clear();
        checkAndLogFailedElements(bulk);
    }

    private void checkAndLogFailedElements(BulkResponse response) {
        String separator = System.getProperty("line.separator");
        StringBuilder errorMessages = new StringBuilder(separator);
        for (BulkResponseItem bulkItemResponse : response.items()) {
            if (bulkItemResponse.error() != null) {
                errorMessages.append(bulkItemResponse.error().reason());
                errorMessages.append(separator);
            }
        }
        if (errorMessages.toString().isBlank()) {
            return;
        }
        errorMessages.insert(0, "Some sessions elements can't be saved to opensearch:");
        throw new ImportException(errorMessages.toString());
    }
}

