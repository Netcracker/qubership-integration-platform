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

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.http.common.HttpConsumer;
import org.apache.camel.http.common.HttpRestConsumerPath;
import org.apache.camel.http.common.HttpRestServletResolveConsumerStrategy;
import org.apache.camel.support.RestConsumerContextPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class CustomHttpRestServletResolveConsumerStrategy extends HttpRestServletResolveConsumerStrategy {

    @Override
    protected HttpConsumer doResolve(HttpServletRequest request, String method, Map<String, HttpConsumer> consumers) {
        String path = request.getPathInfo();
        if (path == null) {
            return null;
        }

        List<RestConsumerContextPathMatcher.ConsumerPath<HttpConsumer>> consumerPaths = new ArrayList<>();
        for (final Map.Entry<String, HttpConsumer> entry : consumers.entrySet()) {
            consumerPaths.add(new HttpRestConsumerPath(entry.getValue()));
        }

        RestConsumerContextPathMatcher.ConsumerPath<HttpConsumer> best
                = RestConsumerContextPathCustomMatcher.matchBestPath(method, path, consumerPaths);

        return best != null ? best.getConsumer() : null;
    }
}
