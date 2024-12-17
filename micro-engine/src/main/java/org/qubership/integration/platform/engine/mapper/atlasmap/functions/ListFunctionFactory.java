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

package org.qubership.integration.platform.engine.mapper.atlasmap.functions;

import io.atlasmap.core.AtlasPath;
import io.atlasmap.core.BaseFunctionFactory;
import io.atlasmap.expression.Expression;
import io.atlasmap.expression.parser.ParseException;
import io.atlasmap.v2.CollectionType;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;

import java.util.ArrayList;
import java.util.List;

import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.cloneField;
import static org.qubership.integration.platform.engine.mapper.atlasmap.FieldUtils.replacePathSegments;
import static java.util.Objects.nonNull;

public class ListFunctionFactory extends BaseFunctionFactory {
    @Override
    public String getName() {
        return "list";
    }

    @Override
    public Expression create(List<Expression> args) throws ParseException {
        return ctx -> {
            FieldGroup group = new FieldGroup();
            group.setName("result");
            group.setPath("/result<>");
            group.setCollectionType(CollectionType.LIST);
            List<Field> fields = new ArrayList<>();
            for (Expression expr : args) {
                Field value = expr.evaluate(ctx);
                if (value instanceof FieldGroup fg) {
                    fields.addAll(fg.getField());
                } else {
                    fields.add(value);
                }
            }
            int index = 0;
            for (Field field : fields) {
                Field f = null;
                if (nonNull(field)) {
                    f = cloneField(field);
                    replacePathSegments(
                            f,
                            new AtlasPath(f.getPath()).getSegments(true),
                            new AtlasPath(String.format("/result<%d>", index)).getSegments(true)
                    );
                }
                ++index;
                group.getField().add(f);
            }
            return group;
        };
    }
}
