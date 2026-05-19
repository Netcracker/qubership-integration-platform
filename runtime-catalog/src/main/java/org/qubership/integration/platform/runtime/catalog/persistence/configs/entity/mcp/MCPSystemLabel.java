package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractLabel;

import java.util.Objects;

@Getter
@Setter
@Slf4j
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "mcp_system_labels")
public class MCPSystemLabel extends AbstractLabel {
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mcp_system_id")
    private MCPSystem system;

    public MCPSystemLabel(String name, MCPSystem system) {
        super(name);
        this.system = system;
    }

    public MCPSystemLabel(String name, boolean technical, MCPSystem system) {
        super(name, technical);
        this.system = system;
    }

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }
        MCPSystemLabel that = (MCPSystemLabel) o;
        return Objects.equals(getSystem(), that.getSystem());
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getSystem());
    }
}
