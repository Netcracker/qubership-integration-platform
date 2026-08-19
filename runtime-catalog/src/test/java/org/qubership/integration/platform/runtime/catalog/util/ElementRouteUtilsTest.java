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

package org.qubership.integration.platform.runtime.catalog.util;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.camelk.model.routes.ElementRoute;
import org.springframework.http.HttpMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ElementRouteUtils#intersects}: two routes intersect only when their paths overlap and
 * they share at least one HTTP method.
 */
class ElementRouteUtilsTest {

    private static ElementRoute route(String path, HttpMethod... methods) {
        return ElementRoute.builder().path(path).methods(Set.of(methods)).build();
    }

    @Test
    void intersectsWhenPathsOverlapAndMethodsShareOne() {
        ElementRoute first = route("/orders/{id}", HttpMethod.GET, HttpMethod.POST);
        ElementRoute second = route("/orders/{orderId}", HttpMethod.POST);

        assertThat(ElementRouteUtils.intersects(first, second)).isTrue();
    }

    @Test
    void doesNotIntersectWhenPathsDiffer() {
        ElementRoute first = route("/orders", HttpMethod.GET);
        ElementRoute second = route("/orders/items", HttpMethod.GET);

        assertThat(ElementRouteUtils.intersects(first, second)).isFalse();
    }

    @Test
    void doesNotIntersectWhenMethodsAreDisjoint() {
        ElementRoute first = route("/orders/{id}", HttpMethod.GET);
        ElementRoute second = route("/orders/{id}", HttpMethod.POST);

        assertThat(ElementRouteUtils.intersects(first, second)).isFalse();
    }
}
