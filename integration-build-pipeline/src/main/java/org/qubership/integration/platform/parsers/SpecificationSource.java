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

package org.qubership.integration.platform.parsers;

/**
 * A single specification source handed to a {@link SpecificationParser}.
 *
 * <p>Carries the fields a parser reads: the file name, which some parsers use to select or locate
 * sources; the source text itself; and whether this source is the specification's main source. The
 * WSDL parser uses the main-source flag to pick the root file among several uploaded files; other
 * parsers ignore it. The caller adapts its own storage type to this shape so the parsers stay free
 * of any persistence dependency.
 */
public class SpecificationSource {

    private final String name;
    private final String source;
    private final boolean mainSource;

    public SpecificationSource(String name, String source) {
        this(name, source, false);
    }

    public SpecificationSource(String name, String source, boolean mainSource) {
        this.name = name;
        this.source = source;
        this.mainSource = mainSource;
    }

    public String getName() {
        return name;
    }

    public String getSource() {
        return source;
    }

    public boolean isMainSource() {
        return mainSource;
    }
}
