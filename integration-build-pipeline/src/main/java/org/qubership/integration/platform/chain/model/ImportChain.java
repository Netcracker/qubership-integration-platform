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

package org.qubership.integration.platform.chain.model;

import org.qubership.integration.platform.io.model.exportimport.chain.ChainCommitRequestAction;

import java.util.List;

/**
 * A chain read from an import archive.
 *
 * <p>Extends the DSL {@link Chain} with fields that only the import pipeline needs. These fields
 * describe how the chain was exported (its content hash, override relationships, and the deploy
 * intent) rather than what the chain does, so they stay off the core {@link Chain} contract.
 */
public interface ImportChain extends Chain {

    /**
     * Content hash of the exported chain directory, used to skip re-importing an unchanged chain.
     */
    String getLastImportHash();

    /**
     * Id of the chain that this chain overrides, or {@code null} when it overrides nothing.
     */
    String getOverridesChainId();

    /**
     * Id of the chain that overrides this chain, or {@code null} when nothing overrides it.
     */
    String getOverriddenByChainId();

    /**
     * Whether another chain overrides this one.
     */
    boolean isOverridden();

    /**
     * Domain names the chain was deployed to at export time.
     */
    List<String> getDeployments();

    /**
     * Action requested for the chain on import: none, build a snapshot, or deploy.
     */
    ChainCommitRequestAction getDeployAction();
}
