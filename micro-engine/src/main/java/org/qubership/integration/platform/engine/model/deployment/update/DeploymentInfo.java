/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.model.deployment.update;

import javax.annotation.Nullable;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DeploymentInfo {
    private String deploymentId;
    private String chainId;
    private String chainName;
    private String snapshotId;
    private String snapshotName;
    private String chainStatusCode;
    private Long createdWhen;
    private boolean containsCheckpointElements;
    private boolean containsSchedulerElements;
    private List<String> dependencyChainIds;
}
