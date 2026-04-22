package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WireTapInfo {
    private List<String> parentIds;

    public WireTapInfo(String wireTapId) {
        parentIds = Arrays.stream(wireTapId.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }
}
