package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.context;

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
@Entity(name = "context_system_labels")
public class ContextSystemLabel extends AbstractLabel {

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "context_system_id")
    private ContextSystem system;

    @Override
    public boolean equals(Object o) {
        if (!super.equals(o)) {
            return false;
        }

        ContextSystemLabel that = (ContextSystemLabel) o;
        return Objects.equals(system == null ? null : system.getId(), that.system == null ? null : that.system.getId());
    }

    @Override
    public int hashCode() {
        return super.hashCode() + Objects.hash(system == null ? null : system.getId());
    }
}
