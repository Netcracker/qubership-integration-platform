package org.qubership.integration.platform.engine.persistence.shared.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.ZonedDateTime;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "idempotency_records")
public class IdempotencyRecord {
    @Id
    @Column(columnDefinition = "text")
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode data;

    @Column(columnDefinition = "timestamptz")
    private ZonedDateTime createdAt;

    @Column(columnDefinition = "timestamptz")
    private ZonedDateTime expiresAt;
}
