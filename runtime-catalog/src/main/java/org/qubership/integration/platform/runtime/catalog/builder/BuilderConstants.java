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

package org.qubership.integration.platform.runtime.catalog.builder;

import java.util.Set;

public final class BuilderConstants {

    public static final String ROUTES = "routes";
    public static final String ROUTE = "route";
    public static final String ID = "id";
    public static final String GROUP = "group";
    public static final String FROM = "from";
    public static final String ON_COMPLETION = "onCompletion";
    public static final String ON_COMPLETION_ID_POSTFIX = "-on-completion";
    public static final String PROCESS = "process";
    public static final String REF = "ref";
    public static final String URI = "uri";
    public static final String MULTICAST = "multicast";
    public static final String DIRECT = "direct:";
    public static final String TO = "toD";
    public static final String SCHEMA = "http://camel.apache.org/schema/spring";
    public static final String SFTP_TRIGGER_PREFIX = "sftp-trigger";
    public static final String REUSE_ELEMENT_TYPE = "reuse";
    public static final String DEPLOYMENT_ID_PLACEHOLDER = "%%{deployment-id-placeholder}";

    public static final String CHAIN_FINISH_PROCESSOR = "chainFinishProcessor";
    public static final String CHAIN_START_PROCESSOR = "chainStartProcessor";
    public static final String SPLIT_ASYNC_PROCESSOR = "splitAsyncProcessor";

    public static final Set<String> ON_COMPLETION_EXCLUDE_TRIGGERS = Set.of("chain-trigger", "chain-trigger-2");

    private BuilderConstants() {}
}
