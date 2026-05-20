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

package org.qubership.integration.platform.runtime.catalog.mapper.expressions;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.parser.ExpressionLexer;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.parser.ExpressionParser;

import java.util.function.Function;

public class ToAtlasMapExpressionConverter {
    public String convert(
            String expressionText,
            Function<FieldReference, String> fieldIdResolver
    ) {
        ANTLRErrorListener errorListener = buildErrorListener();
        ExpressionLexer lexer = new ExpressionLexer(CharStreams.fromString(expressionText));
        lexer.addErrorListener(errorListener);
        ExpressionParser parser = new ExpressionParser(new CommonTokenStream(lexer));
        parser.addErrorListener(errorListener);
        ExpressionVisitor visitor = new ExpressionVisitor(fieldIdResolver);
        ParseTree tree = parser.expression();
        return visitor.visit(tree);
    }

    private ANTLRErrorListener buildErrorListener() {
        return new BaseErrorListener() {
            @Override
            public void syntaxError(
                    Recognizer<?, ?> recognizer,
                    Object offendingSymbol,
                    int line,
                    int charPositionInLine,
                    String msg,
                    RecognitionException e
            ) {
                String message = String.format("Syntax error at line %d, position %d: %s",
                        line, charPositionInLine, msg);
                throw new IllegalStateException(message, e);
            }
        };
    }
}
