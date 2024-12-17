package org.qubership.integration.platform.engine.persistence.configs.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;
import lombok.Builder.Default;

import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
@Entity(name = "sds_job_locks")
public class SdsJobLock {

    @Id
    @Default
    private String id = UUID.randomUUID().toString();

    @NonNull
    private String jobId;

    @NonNull
    private String executionId;

    @Default
    private Timestamp created = Timestamp.from(new Date().toInstant());
}
