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

package org.qubership.integration.platform.runtime.catalog.util.paths;


import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class PathIntersectionChecker {
    private final PathParser parser;

    public PathIntersectionChecker(PathParser parser) {
        this.parser = parser;
    }

    public PathIntersectionChecker() {
        this(new PathParser());
    }

    public boolean intersects(String path1, String path2) {
        return intersects(parser.parse(path1), parser.parse(path2));
    }

    private boolean intersects(List<PathElement> path1, List<PathElement> path2) {
        return (path1.size() == path2.size())
                && IntStream.range(0, path1.size()).allMatch(index -> intersects(path1.get(index), path2.get(index)));
    }

    private boolean intersects(PathElement element1, PathElement element2) {
        return Objects.equals(element1.getPattern(), element2.getPattern());
    }
}
