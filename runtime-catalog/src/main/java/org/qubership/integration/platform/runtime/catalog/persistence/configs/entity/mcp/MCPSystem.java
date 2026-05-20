package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldNameConstants;
import lombok.experimental.SuperBuilder;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.AbstractSystemEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static jakarta.persistence.CascadeType.*;

@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@FieldNameConstants
@Entity(name = "mcp_systems")
public class MCPSystem extends AbstractSystemEntity {
    @Column
    private String identifier;

    @Column
    private String instructions;

    @Builder.Default
    @OneToMany(mappedBy = "system",
            orphanRemoval = true,
            cascade = {PERSIST, REMOVE, MERGE}
    )
    private Set<MCPSystemLabel> labels = new LinkedHashSet<>();

    @Transient
    private List<Chain> chains;

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        if (!(o instanceof MCPSystem mcpSystem)) {
            return false;
        }
        return Objects.equals(identifier, mcpSystem.identifier) && Objects.equals(instructions, mcpSystem.instructions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getIdentifier(), getInstructions(), getChains());
    }
}
