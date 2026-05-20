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
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.exception.SessionsNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ExportService {
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH_mm_ss");
    private static final String JSON_EXTENSION = ".json";

    private final SessionService sessionService;
    private final ObjectMapper jsonMapper;

    @Autowired
    public ExportService(SessionService sessionService, ObjectMapper jsonMapper) {
        this.sessionService = sessionService;
        this.jsonMapper = jsonMapper;
    }

    public Pair<String, String> exportSessions(List<String> sessionsIds) throws JsonProcessingException {
        List<Session> sessions = sessionsIds.stream().map(id -> sessionService.findById(id,
            SessionService.SESSION_ID_KEY, false, true
        )).collect(Collectors.toList());
        if (sessions.isEmpty()) {
            throw new SessionsNotFoundException("Sessions not found");
        }
        return getExportedSessions(sessions.get(0).getChainId(), sessions);
    }

    private Pair<String, String> getExportedSessions(String chainId, List<Session> sessions) throws JsonProcessingException {
        String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessions);
        return Pair.of("chain-sessions-" + chainId + "-(" + DATE_FORMAT.format(new Date()) + ")" + JSON_EXTENSION, json);
    }
}
