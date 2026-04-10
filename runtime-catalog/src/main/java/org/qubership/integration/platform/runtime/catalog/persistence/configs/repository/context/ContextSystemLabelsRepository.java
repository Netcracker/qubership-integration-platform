package org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.context;

import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context.ContextSystemLabel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContextSystemLabelsRepository extends JpaRepository<ContextSystemLabel, String> {

}
