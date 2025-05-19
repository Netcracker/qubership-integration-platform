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

package org.qubership.integration.platform.sessions.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.sessions.dto.Session;
import org.qubership.integration.platform.sessions.dto.SessionElement;
import org.qubership.integration.platform.sessions.dto.SessionSearchResponse;
import org.qubership.integration.platform.sessions.dto.filter.FilterRequestAndSearchDTO;
import org.qubership.integration.platform.sessions.exception.SessionsNotFoundException;
import org.qubership.integration.platform.sessions.service.CatalogInternalService;
import org.qubership.integration.platform.sessions.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/v1/sessions")
@Tag(name = "session-controller", description = "Session Controller")
public class SessionController {

    private final SessionService sessionService;
    private final CatalogInternalService catalogInternalService;

    @Autowired
    public SessionController(SessionService sessionService,
        CatalogInternalService catalogInternalService) {
        this.sessionService = sessionService;
        this.catalogInternalService = catalogInternalService;
    }

    @Operation(extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}),
    description = "Get session by specified external id")
    @GetMapping("/external-id/{externalSessionId}")
    public ResponseEntity<Session> findByExternalId(@PathVariable @Parameter(description = "External id that was specified during chain execution") String externalSessionId,
                                                    @RequestParam(required = false, defaultValue = "false") @Parameter(description = "Whether we need to include session elements(steps) in the response") boolean includeDetails) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find session by external id {}", externalSessionId);
        }
        Session session = sessionService.findByExternalSessionId(externalSessionId, includeDetails);
        if (session == null) {
            throw new SessionsNotFoundException("Can't find session by external id " + externalSessionId);
        }
        return ResponseEntity.ok(session);
    }

    @GetMapping("/{sessionId}")
    @Operation(description = "Get session with all elements (steps) by session id")
    public ResponseEntity<Session> findById(@PathVariable @Parameter(description = "Session id") String sessionId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find session by id {}", sessionId);
        }
        Session session = sessionService.findById(sessionId, SessionService.SESSION_ID_KEY,
            true, true);
        if (session == null) {
            throw new SessionsNotFoundException("Can't find session " + sessionId);
        }
        return ResponseEntity.ok(session);
    }

    @RequestMapping(method = RequestMethod.HEAD, value = "/{sessionId}")
    @Operation(description = "Find session by id if it exists", extensions = @Extension(properties = {@ExtensionProperty(name = "x-api-kind", value = "bwc")}))
    public ResponseEntity<Session> findExistingSession(@PathVariable String sessionId) {
        Session session = sessionService.findById(sessionId, SessionService.SESSION_ID_KEY, true, false);

        if (session == null) {
            throw new SessionsNotFoundException("Can't find session " + sessionId);
        }

        return ResponseEntity.ok(session);
    }

    @PostMapping
    @Operation(description = "Get light list of sessions without session elements (steps) with additional parameters")
    public ResponseEntity<SessionSearchResponse> findAllByFilter(
        @RequestParam(required = false, defaultValue = "0") @Parameter(description = "Which session number should we start from") int offset,
        @RequestParam(required = false, defaultValue = "20") @Parameter(description = "Amount of sessions received at a time") int count,
        @RequestParam(required = false, defaultValue = "sessionStarted") @Parameter(description = "Name of column we should sort response by") String sortColumn,
        @RequestBody @Parameter(description = "Additional filters request object") FilterRequestAndSearchDTO filterRequest
    ) {
        ResponseEntity<SessionSearchResponse> response = findByFilter(null, offset, count,
            sortColumn, filterRequest);
        SessionSearchResponse body = response.getBody();

        if (body != null) {
            try {
                Set<String> chainIds = body.getSessions().stream()
                    .map(Session::getChainId).collect(Collectors.toSet());

                Map<String, String> chainsNames = catalogInternalService.getChainsNames(chainIds);
                body.getSessions()
                    .forEach(session ->
                        session.setChainName(chainsNames.getOrDefault(session.getChainId(), session.getChainName())));
            } catch (Exception e) {
                log.warn("Failed to receive actual chains names for sessions", e);
            }
        }

        return response;
    }

    @PostMapping("/chains/{chainId}")
    @Operation(description = "Get light list of sessions for specified chain without session elements (steps) with additional parameters")
    public ResponseEntity<SessionSearchResponse> findByFilter(
        @PathVariable() @Nullable @Parameter(description = "Only sessions executed on chain with specified id will be shown") String chainId,
        @RequestParam(required = false, defaultValue = "0") @Parameter(description = "Which session number should we start from") int offset,
        @RequestParam(required = false, defaultValue = "20") @Parameter(description = "Amount of sessions received at a time") int count,
        @RequestParam(required = false, defaultValue = "sessionStarted") @Parameter(description = "Name of column we should sort response by") String sortColumn,
        @RequestBody @Parameter(description = "Additional filters request object") FilterRequestAndSearchDTO filterRequest
    ) {
        if (log.isDebugEnabled()) {
            if (chainId != null) {
                log.debug("Request to find previews by chain id: {}", chainId);
            } else {
                log.debug("Request to find previews");
            }
        }

        return ResponseEntity.ok(
                sessionService.getSessions(chainId, offset, count, sortColumn, filterRequest));
    }

    @GetMapping("/{sessionId}/{elementId}")
    @Operation(description = "Get element(step) with payload (body) for specified element(step)")
    public ResponseEntity<SessionElement> getElementPayloadById(@PathVariable @Parameter(description = "Session id") String sessionId,
                                                                @PathVariable @Parameter(description = "Element (step) id within specified session") String elementId) {
        if (log.isDebugEnabled()) {
            log.debug("Request to find Session Element payload from session {} by id {}", sessionId,
                elementId);
        }
        SessionElement element = sessionService.getElementById(elementId);
        if (element == null) {
            throw new SessionsNotFoundException("Can't find element with id " + elementId);
        }
        return ResponseEntity.ok(element);
    }

    @DeleteMapping("/{sessionId}")
    @Operation(description = "Delete specified session")
    public ResponseEntity<Void> deleteById(@PathVariable @Parameter(description = "Session id") String sessionId) {
        log.info("Request to delete session by id: {}", sessionId);
        sessionService.deleteBySessionId(sessionId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Operation(description = "Delete all sessions for specified chain")
    @DeleteMapping("/chains/{chainId}")
    public ResponseEntity<Void> deleteAllByChainId(@PathVariable @Parameter(description = "Chain id") String chainId) {
        log.info("Request to delete all sessions by chain id: {}", chainId);
        sessionService.deleteByChainId(chainId);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Operation(description = "Bulk delete sessions")
    @PostMapping("/bulk-delete")
    public ResponseEntity<Void> deleteByIds(@RequestBody List<String> sessionIds) {
        log.info("Request to delete sessions by ids: {}", sessionIds);
        sessionService.deleteBySessionIds(sessionIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Operation(description = "Delete all sessions for specified chains")
    @DeleteMapping("/chains")
    public ResponseEntity<Void> deleteAllByChainIds(@RequestParam @Parameter(description = "List of chain ids separated by comma") List<String> chainIds) {
        log.info("Request to delete all sessions by chains ids: {}", chainIds);
        sessionService.deleteAllByChainIds(chainIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Operation(description = "Delete all sessions from all chains")
    @DeleteMapping("")
    public ResponseEntity<Void> deleteAllSessions() {
        log.info("Request to delete all sessions");
        sessionService.deleteAllSessions();
        return new ResponseEntity<>(HttpStatus.OK);
    }
}
