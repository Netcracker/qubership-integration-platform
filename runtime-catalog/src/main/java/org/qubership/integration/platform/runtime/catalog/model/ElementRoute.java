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

package org.qubership.integration.platform.runtime.catalog.model;

import lombok.*;
import org.qubership.integration.platform.runtime.catalog.util.paths.PathIntersectionChecker;
import org.springframework.http.HttpMethod;

import java.util.Set;

@Getter
@Setter
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ElementRoute {
    private String path;
    private Set<HttpMethod> methods;
    private boolean isExternal;
    private boolean isPrivate;
    private long connectionTimeout;

    public boolean intersectsWith(ElementRoute route) {
        PathIntersectionChecker intersectionChecker = new PathIntersectionChecker();

        return intersectionChecker.intersects(path, route.getPath())
                && methods.stream().anyMatch(route.getMethods()::contains);
    }
}
