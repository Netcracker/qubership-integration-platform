package org.qubership.integration.platform.engine.model.controlplane.v3.routes;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HeaderMatcher {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long id;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String name;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long version;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String exactMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String safeRegexMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private RangeMatch rangeMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String presentMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String prefixMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String suffixMatch;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String invertMatch;

}
