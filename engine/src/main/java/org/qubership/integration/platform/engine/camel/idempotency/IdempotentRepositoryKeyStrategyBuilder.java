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

package org.qubership.integration.platform.engine.camel.idempotency;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

public class IdempotentRepositoryKeyStrategyBuilder {
    private static record BuildContext(String idempotencyKey) {}

    private static class IdempotentRepositoryKeyStrategyImpl implements IdempotentRepositoryKeyStrategy {
        private final List<Function<BuildContext, String>> appenders;

        public IdempotentRepositoryKeyStrategyImpl(List<Function<BuildContext, String>> appenders) {
            this.appenders = appenders;
        }

        @Override
        public String buildRepositoryKey(String idempotencyKey) {
            return buildForContext(new BuildContext(idempotencyKey));
        }

        private String buildForContext(BuildContext context) {
            return appenders.stream()
                    .map(appender -> appender.apply(context))
                    .collect(Collectors.joining());
        }
    }

    private List<Function<BuildContext, String>> appenders;

    public IdempotentRepositoryKeyStrategyBuilder reset() {
        appenders = null;
        return this;
    }

    public IdempotentRepositoryKeyStrategyBuilder append(String string) {
        getAppenders().add(context -> string);
        return this;
    }

    public IdempotentRepositoryKeyStrategyBuilder appendIdempotencyKey() {
        getAppenders().add(BuildContext::idempotencyKey);
        return this;
    }

    public IdempotentRepositoryKeyStrategy build() {
        return new IdempotentRepositoryKeyStrategyImpl(getAppenders());
    }

    private List<Function<BuildContext, String>> getAppenders() {
        if (isNull(appenders)) {
            appenders = new ArrayList<>();
        }
        return appenders;
    }
}
