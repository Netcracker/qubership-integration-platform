package org.qubership.integration.platform.runtime.catalog.cr.model.options;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class JvmOptions {
    private String jar;
    private List<String> args;
}
