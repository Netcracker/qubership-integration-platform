package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.dto.deployment.DeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.User;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.data.domain.AuditorAware;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentMapperTest {

    private DeploymentMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new DeploymentMapperImpl();
        ReflectionTestUtils.setField(
                mapper, "auditor", (AuditorAware<User>) () -> Optional.of(new User("42", "deployer")));
    }

    @Test
    @DisplayName("asMicroResponse reports the deployment operation, not the snapshot's audit trail")
    void asMicroResponseReportsTheDeploymentOperationMetadata() {
        Chain chain = new Chain();
        chain.setId("chain-1");
        Snapshot snapshot = new Snapshot();
        snapshot.setId("3f0c7f4e-2b1a-4c9d-8e5f-6a7b8c9d0e1f");
        snapshot.setName("V1");
        snapshot.setChain(chain);
        snapshot.setCreatedBy(new User("7", "snapper"));
        snapshot.setCreatedWhen(new Timestamp(System.currentTimeMillis() - 60_000L));

        DeploymentResponse response = mapper.asMicroResponse("chain-1", "orders", snapshot);

        assertThat(response.getId()).isEqualTo("orders-" + snapshot.getId());
        assertThat(response.getCreatedWhen()).isGreaterThan(snapshot.getCreatedWhen().getTime());
        assertThat(response.getCreatedBy().getUsername()).isEqualTo("deployer");
        assertThat(response.getRuntime().getStates()).isEmpty();
    }
}
