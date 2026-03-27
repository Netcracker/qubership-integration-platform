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

package org.qubership.integration.platform.engine.camel.components.graphql;

import org.apache.camel.Endpoint;
import org.apache.camel.component.graphql.GraphqlComponent;
import org.apache.camel.spi.annotations.Component;

import java.net.URI;
import java.util.Map;

@Component("graphql-custom")
public class GraphqlCustomComponent extends GraphqlComponent {
    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        GraphqlCustomEndpoint endpoint = new GraphqlCustomEndpoint(uri, this);
        endpoint.setHttpUri(new URI(remaining));
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
