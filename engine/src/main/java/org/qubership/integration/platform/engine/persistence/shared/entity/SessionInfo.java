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

package org.qubership.integration.platform.engine.persistence.shared.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.qubership.integration.platform.engine.model.Session;
import org.qubership.integration.platform.engine.service.ExecutionStatus;

import java.sql.Timestamp;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "sessions_info")
public class SessionInfo {

    @Id
    private String id;

    private Timestamp started;

    private Timestamp finished;

    private long duration;

    private ExecutionStatus executionStatus;

    private String chainId;

    private String chainName;

    private String domain;

    private String engineAddress;

    private String loggingLevel;

    private String snapshotName;

    private String correlationId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_session_id", referencedColumnName = "id")
    private SessionInfo parentSession;

    @OneToMany(orphanRemoval = true, mappedBy = "session", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Checkpoint> checkpoints = new LinkedList<>();

    public SessionInfo(Session session) {
        setId(session.getId());
        setStarted(Timestamp.from(new Date().toInstant()));
        setDuration(session.getDuration());
        setExecutionStatus(session.getExecutionStatus());
        setChainId(session.getChainId());
        setChainName(session.getChainName());
        setDomain(session.getDomain());
        setEngineAddress(session.getEngineAddress());
        setLoggingLevel(session.getLoggingLevel());
        setSnapshotName(session.getSnapshotName());
        setCorrelationId(session.getCorrelationId());
    }

    public void assignCheckpoint(Checkpoint checkpoint) {
        checkpoint.setSession(this);
        getCheckpoints().add(checkpoint);
    }
}
