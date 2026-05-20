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

package org.qubership.integration.platform.engine.camel.components.servlet;

import org.apache.camel.Exchange;
import org.apache.camel.http.common.HttpHeaderFilterStrategy;
import org.qubership.integration.platform.engine.camel.components.context.propagation.ContextPropsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.nonNull;

@Component
public class ServletCustomFilterStrategy extends HttpHeaderFilterStrategy {
    private static final Collection<String> FILTERED_HEADERS = List.of(
        "span-id",
        "trace-id",
        "x-requestedsystem"
    );

    private final Optional<ContextPropsProvider> contextPropsProvider;

    @Autowired
    public ServletCustomFilterStrategy(Optional<ContextPropsProvider> contextPropsProvider) {
        this.contextPropsProvider = contextPropsProvider;
        this.getOutFilter().addAll(FILTERED_HEADERS);
    }

    @Override
    protected boolean extendedFilter(Direction direction, String headerName, Object headerValue, Exchange exchange) {
        return (Direction.OUT.equals(direction) && isHeaderInContext(headerName)) == isFilterOnMatch();
    }

    private boolean isHeaderInContext(String name) {
        return this.contextPropsProvider
            .map(ContextPropsProvider::getDownstreamHeaders)
            .map(headers -> nonNull(headers) && headers.contains(name))
            .orElse(false);
    }
}
