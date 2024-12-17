parser grammar ExpressionParser;

options { tokenVocab=ExpressionLexer; }

expression: orExpression;

orExpression: andExpression (orOperator andExpression)*;

orOperator: OR;

andExpression: equalityExpression (andOperator equalityExpression)*;

andOperator: AND;

equalityExpression: comparisonExpression (equalityOperator comparisonExpression)*;

equalityOperator: EQ | NEQ;

comparisonExpression: addExpression (comparisonOperator addExpression)*;

comparisonOperator: GE | LE | GT | LT;

addExpression: multExpression (addOperator multExpression)*;

addOperator: PLUS | MINUS;

multExpression: unaryExpression (multOperator unaryExpression)*;

multOperator: MUL | DIV | MOD;

unaryExpression: (unaryOperator unaryExpression) | primaryExpression;

unaryOperator: PLUS | MINUS | NEG;

primaryExpression: literal | variable | expressionInBraces | functionCall;

expressionInBraces: LP expression RP;

functionCall: functionName LP functionParameters? RP;

functionName: IDENTIFIER;

functionParameters: expression (COMMA expression)*;

variable: attributeReference | constantReference;

attributeReference: attributeKind DOT path;

attributeKind: HEADER | PROPERTY | BODY;

path: pathElement (DOT pathElement)*;

pathElement: PATH_ELEMENT | IDENTIFIER | HEADER | PROPERTY | BODY | CONSTANT | DEC_NUMBER | HEX_NUMBER | OCT_NUMBER; // wa to not mess with lexer modes

constantReference: CONSTANT DOT constantName=pathElement;

literal: nullLiteral | numberLiteral | stringLiteral | booleanLiteral;

nullLiteral: NULL;

numberLiteral: DEC_NUMBER | HEX_NUMBER | OCT_NUMBER | FLOAT_NUMBER;

stringLiteral: STRING;

booleanLiteral: TRUE | FALSE;
