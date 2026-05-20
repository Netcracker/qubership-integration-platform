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

import io.atlasmap.expression.ExpressionContext;
import io.atlasmap.expression.ExpressionException;
import io.atlasmap.v2.Field;

import static java.util.Objects.isNull;

public class ChainedExpressionContext implements ExpressionContext {
    private final ExpressionContext firstContent;
    private final ExpressionContext secondContent;

    public ChainedExpressionContext(
            ExpressionContext firstContent,
            ExpressionContext secondContent
    ) {
        this.firstContent = firstContent;
        this.secondContent = secondContent;
    }

    @Override
    public Field getVariable(String s) throws ExpressionException {
        Field field = firstContent.getVariable(s);
        return isNull(field) ? secondContent.getVariable(s) : field;
    }
}
