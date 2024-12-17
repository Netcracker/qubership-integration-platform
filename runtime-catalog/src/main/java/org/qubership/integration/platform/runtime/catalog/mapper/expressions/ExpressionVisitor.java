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

import org.antlr.v4.runtime.RuleContext;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.parser.ExpressionParser;
import org.qubership.integration.platform.runtime.catalog.mapper.expressions.parser.ExpressionParserBaseVisitor;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class ExpressionVisitor extends ExpressionParserBaseVisitor<String> {
    private final Function<FieldReference, String> fieldIdResolver;

    public ExpressionVisitor(Function<FieldReference, String> fieldIdResolver) {
        this.fieldIdResolver = fieldIdResolver;
    }

    @Override
    public String visitOrExpression(ExpressionParser.OrExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitOrOperator(ExpressionParser.OrOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitAndExpression(ExpressionParser.AndExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitAndOperator(ExpressionParser.AndOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitEqualityExpression(ExpressionParser.EqualityExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitEqualityOperator(ExpressionParser.EqualityOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitComparisonExpression(ExpressionParser.ComparisonExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitComparisonOperator(ExpressionParser.ComparisonOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitAddExpression(ExpressionParser.AddExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitAddOperator(ExpressionParser.AddOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitMultExpression(ExpressionParser.MultExpressionContext ctx) {
        return ctx.children.stream().map(this::visit).collect(Collectors.joining(" "));
    }

    @Override
    public String visitMultOperator(ExpressionParser.MultOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitUnaryExpression(ExpressionParser.UnaryExpressionContext ctx) {
        return isNull(ctx.primaryExpression())
                ? visit(ctx.unaryOperator()) + visit(ctx.unaryExpression())
                : visit(ctx.primaryExpression());
    }

    @Override
    public String visitUnaryOperator(ExpressionParser.UnaryOperatorContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitExpressionInBraces(ExpressionParser.ExpressionInBracesContext ctx) {
        return "(" + visit(ctx.expression()) + ")";
    }

    @Override
    public String visitFunctionCall(ExpressionParser.FunctionCallContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.functionName().getText()).append("(");
        if (nonNull(ctx.functionParameters())) {
            sb.append(this.visit(ctx.functionParameters()));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String visitFunctionName(ExpressionParser.FunctionNameContext ctx) {
        return super.visitFunctionName(ctx);
    }

    @Override
    public String visitFunctionParameters(ExpressionParser.FunctionParametersContext ctx) {
        return ctx.expression().stream().map(this::visit).collect(Collectors.joining(", "));
    }

    @Override
    public String visitAttributeReference(ExpressionParser.AttributeReferenceContext ctx) {
        FieldKind kind = FieldKind.valueOf(ctx.attributeKind().getText().toUpperCase());
        List<String> path = ctx.path().pathElement().stream()
                .map(RuleContext::getText)
                .map(this::unescapePathElement)
                .collect(Collectors.toList());
        FieldReference reference = new FieldReference(kind, path);
        return buildFieldReferenceText(reference);
    }

    @Override
    public String visitConstantReference(ExpressionParser.ConstantReferenceContext ctx) {
        FieldReference reference = new FieldReference(
                FieldKind.CONSTANT,
                Collections.singletonList(unescapeConstantName(ctx.constantName.getText())));
        return buildFieldReferenceText(reference);
    }

    @Override
    public String visitNullLiteral(ExpressionParser.NullLiteralContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitNumberLiteral(ExpressionParser.NumberLiteralContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitStringLiteral(ExpressionParser.StringLiteralContext ctx) {
        return ctx.getText();
    }

    @Override
    public String visitBooleanLiteral(ExpressionParser.BooleanLiteralContext ctx) {
        return ctx.getText();
    }

    private String buildFieldReferenceText(FieldReference reference) {
        return "${" + fieldIdResolver.apply(reference) + "}";
    }

    private String unescapePathElement(String s) {
        return EscapeUtil.unescape(s);
    }

    private String unescapeConstantName(String s) {
        return EscapeUtil.unescape(s);
    }
}
