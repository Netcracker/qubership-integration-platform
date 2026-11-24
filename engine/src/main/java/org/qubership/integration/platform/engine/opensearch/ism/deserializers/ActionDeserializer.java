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

package org.qubership.integration.platform.engine.opensearch.ism.deserializers;

import org.qubership.integration.platform.engine.opensearch.ism.model.actions.*;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.TreeNode;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.node.ObjectNode;

import java.util.AbstractMap;
import java.util.Map;

import static java.util.Objects.isNull;

public class ActionDeserializer extends ValueDeserializer<Action> {
    private static final Map<String, Class<? extends Action>> CLASS_MAP = Map.ofEntries(
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("force_merge", ForceMergeAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("read_only", ReadOnlyAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("read_write", ReadWriteAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("replica_count", ReplicaCountAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("shrink", ShrinkAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("close", CloseAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("open", OpenAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("delete", DeleteAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("rollover", RolloverAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("notification", NotificationAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("snapshot", SnapshotAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("index_priority", IndexPriorityAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("allocation", AllocationAction.class),
            new AbstractMap.SimpleEntry<String, Class<? extends Action>>("rollup", RollupAction.class)
    );

    @Override
    public Action deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws JacksonException {
        TreeNode node = deserializationContext.readTree(jsonParser);

        if (isNull(node)) {
            return null;
        }

        if (node instanceof ObjectNode objectNode) {
            return deserializeAction(jsonParser, objectNode, deserializationContext);
        } else {
            String message = String.format("Wrong %s field type", jsonParser.currentName());
            throw InvalidFormatException.from(jsonParser, message, node.asToken().asString(), TimeValue.class);
        }
    }

    private Action deserializeAction(JsonParser jsonParser, ObjectNode node, DeserializationContext context) throws JacksonException {
        Map.Entry<String, Class<? extends Action>> entry = CLASS_MAP.entrySet().stream()
                .filter(e -> node.has(e.getKey()))
                .findFirst()
                .orElseThrow(() -> InvalidFormatException.from(
                        jsonParser,
                        "Unsupported action type",
                        node.asToken().asString(),
                        TimeValue.class
                ));

        Action action = context.readTreeAsValue(node.get(entry.getKey()), entry.getValue());

        TimeValue timeout = context.readTreeAsValue(node.get("timeout"), TimeValue.class);
        action.setTimeout(timeout);

        ActionRetry retry = context.readTreeAsValue(node.get("retry"), ActionRetry.class);
        action.setRetry(retry);
        return action;
    }
}
