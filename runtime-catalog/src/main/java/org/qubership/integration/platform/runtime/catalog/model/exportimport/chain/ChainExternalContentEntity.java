package org.qubership.integration.platform.runtime.catalog.model.exportimport.chain;

import com.fasterxml.jackson.annotation.*;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.exportimport.remoteimport.ChainCommitRequestAction;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChainExternalContentEntity {
    private String description;
    private Timestamp modifiedWhen;

    private String businessDescription;
    private String assumptions;
    private String outOfScope;
    private String lastImportHash;

    private List<String> labels;
    private boolean maskingEnabled;

    @Builder.Default
    private Set<MaskedFieldExternalEntity> maskedFields = new HashSet<>();

    @JsonProperty
    @JsonAlias({ "default-swimlane-id" })
    private String defaultSwimlaneId;

    @JsonProperty
    @JsonAlias({ "reuse-swimlane-id" })
    private String reuseSwimlaneId;

    @Builder.Default
    private List<ChainElementExternalEntity> elements = new ArrayList<>();

    @Builder.Default
    private List<DependencyExternalEntity> dependencies = new ArrayList<>();

    private FolderExternalEntity folder;

    @Builder.Default
    private List<DeploymentExternalEntity> deployments = new ArrayList<>();

    private ChainCommitRequestAction deployAction;

    private Integer fileVersion;

    private String migrations;

    @JsonIgnore
    private boolean overridden;

    @JsonIgnore
    private String overridesChainId;

    @JsonIgnore
    private String overriddenByChainId;
}
