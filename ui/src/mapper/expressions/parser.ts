import { generate } from "pegjs";
import { ExpressionNode } from "./model.ts";

const transformationExpressionParser = generate(`
    {
        function buildOperation(head, tail) {
            if (tail.length === 0) {
                return head;
            }
            return tail.length
                ? tail.reduce((left, i) => ({ type: 'operation', kind: i[0], arguments: [left, i[2]] }), head)
                : head;
        }

        function unescape(s) {
            let escaped = false;
            let result = '';
            for (const c of s) {
                if (c === '\\\\') {
                    escaped = true;
                } else {
                    if (escaped) {
                        switch (c) {
                            case 'n':
                                result += '\\\\n';
                                break;
                            case 'r':
                                result += '\\\\r';
                            case 't':
                                result += '\\\\t';
                                break;
                            case '_':
                                break;
                            default:
                                result += c;
                                break;
                        }
                    } else {
                        result += c;
                    }
                    escaped = false;
                }
            }
            return result;
        }
    }

    Expression "expression"
        = _ expression:OrExpression _
        { return expression; }

    OrExpression "or expression"
        = _ head:AndExpression _ tail:( OrOperation _ AndExpression _)*
        { return buildOperation(head, tail); }

    OrOperation "or operation"
        = "||"
        { return 'or' }

    AndExpression "and expression"
        = _ head:EqualityExpression _ tail:(AndOperation _ EqualityExpression _)*
        { return buildOperation(head, tail); }

    AndOperation "and operation"
        = "&&"
        { return 'and' }

    EqualityExpression "equality expression"
        = _ head:ComparisonExpression _ tail:(EqualityOperator _ ComparisonExpression _)*
        { return buildOperation(head, tail); }

    EqualityOperator "equality operator"
        = value:("==" / "!=")
        { return value === '==' ? 'equals' : 'not equals'; }

    ComparisonExpression "comparison expression"
        = _ head:AddExpression _ tail:(ComparisonOperator _ AddExpression _)*
        { return buildOperation(head, tail); }

    ComparisonOperator "comparison operator"
        = value:(">=" / "<=" / ">" / "<")
        {
            switch (value) {
                case '>=': return 'ge';
                case '<=': return 'le';
                case '>': return 'greater';
                case '<': return 'less';
                default: return null;
            }
        }

    AddExpression "addictive expression"
        = _ head:MultExpression _ tail:(AddOperator _ MultExpression _)*
        { return buildOperation(head, tail); }

    AddOperator "addictive operator"
        = value:("+" / "-")
        { return value === '+' ? 'add' : 'sub'; }

    MultExpression "multiplicative expression"
        = _ head:UnaryExpression _ tail:(MultOperator _ UnaryExpression _)*
        { return buildOperation(head, tail); }

    MultOperator "multiplicative operator"
        = value:("*" / "/" / "%")
        {
            switch (value) {
                case '*': return 'multiply';
                case '/': return 'divide';
                case '%': return 'modulo';
                default: return null;
            }
        }

    UnaryExpression "unary expression"
        = _ value:( UnaryOperator _ UnaryExpression / PrimaryExpression) _
        { return Array.isArray(value) ? {type: 'operation', kind: value[0], arguments: [value[2]] } : value; }

    UnaryOperator "unary operator"
        = value:("+" / "-" / "!")
        {
            switch (value) {
                case '+': return 'unary plus';
                case '-': return 'unary minus';
                case '!': return 'negate';
                default: return null;
            }
        }

    PrimaryExpression "primary expression"
        = _ value:(Literal / Variable / "(" _ OrExpression _ ")" / FunctionCall) _
        { return Array.isArray(value) ? value[2] : value; }

    FunctionCall "function call"
        = _ name:Identifier _ "(" _ args:(OrExpression _ ("," _ OrExpression _)*)? ")" _
        {
            return {
                type: 'call',
                name,
                arguments: args === null
                    ? []
                    : args.length <= 1
                    ? args
                    : [args[0], ...args[2].map(i => i[2])]
            };
        }

    Variable "variable"
        = _ value:(AttributeReference / ConstantReference) _
        { return value; }

    AttributeReference "attribute reference"
        = _ kind:AttributeKind _ "." _ path:Path _
        { return { type: 'reference', kind, path, location: location() }; }

    AttributeKind "attribute kind"
        = "header" / "body" / "property"

    Path "path"
        = _ head:PathElement _ tail:("." _ PathElement _)*
        { return [head, ...tail.map(i => i[2])]; }

    PathElement "path element"
        = ([^ .\\t\\r\\n\\\\+\\-*!><,=%()|&/] / "\\\\" [ .\\t\\r\\n+\\-*!><,=%()|&/_])+
        { return unescape(text()); }

    ConstantReference "constant reference"
        = _ "constant" _ "." _ name:PathElement
        { return { type: 'reference', kind: 'constant', name, location: location() }; }

    Literal "literal"
        = _ value:(Null / NumberLiteral / StringLiteral / BooleanLiteral) _
        { return value; }

    Null "null"
        = "null"
        { return { type: 'literal', kind: 'null' }; }

    BooleanLiteral "boolean literal"
        = "true" / "false"
        { return { type: 'literal', kind: 'boolean', value: text() }; }

    NumberLiteral "number literal"
        = value:(DecimalLiteral / HexadecimalLiteral / OctalLiteral / FloatingPointLiteral)
        { return { type: 'literal', kind: 'number', value }; }

    DecimalLiteral "decimal literal"
        = [1-9][0-9]*[lL]?
        { return text(); }

    HexadecimalLiteral "hexadecimal literal"
        = "0" [xX] [0-9a-fA-F]+
        { return text(); }

    OctalLiteral "octal literal"
        = "0" [0-7]*
        { return text(); }

    FloatingPointLiteral "floating point literal"
        = ([0-9]+ "." [0-9]* Exponent?) / ([0-9]+ Exponent)
        { return text(); }

    Exponent "exponent"
        = [eE][-+]?[0-9]+
        { return text(); }

    StringLiteral "string literal"
        = "'" value:("''" / [^'])* "'"
        { return { type: 'literal', kind: 'string', value: value.join('') }; }

    Identifier "identifier"
        = [a-zA-Z_][a-zA-Z0-9_]*
        { return text(); }

    _ = LineComment / BlockComment / Whitespace

    Whitespace "whitespace"
        = [ \\t\\n\\r]*

    LineComment "line comment"
        = "--" [^\\r\\n]* [\\r\\n]

    BlockComment "block comment"
        = "/*" (!"*/" .)* "*/"
`);

export function parse(text: string): ExpressionNode {
  return transformationExpressionParser.parse(text) as ExpressionNode;
}
