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

package org.qubership.integration.platform.engine.testutils;

import org.apache.camel.CamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.RoutesDefinition;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.apache.camel.xml.jaxb.JaxbHelper.loadRoutesDefinition;

public final class RouteTestHelpers {
    private RouteTestHelpers() {
    }

    public static String entryFromUri(CamelContext ctx, String xml) throws Exception {
        RoutesDefinition def = loadRoutesDefinition(ctx, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        List<RouteDefinition> routes = def.getRoutes();
        if (routes.isEmpty() || routes.getFirst().getInput() == null) {
            throw new IllegalStateException("No <from> found in the first route.");
        }
        return routes.getFirst().getInput().getUri();
    }
}
