lexer grammar ExpressionLexer;

BLOCK_COMMENT: '/*' (~'*' | '*' ~'/')* '*/' -> channel(HIDDEN);
LINE_COMMENT: '--' ~[\r\n]* -> channel(HIDDEN);

WHITESPACE: [ \t\r\n] -> channel(HIDDEN);


STRING: '\'' ('\'\'' | ~'\'')* '\'';

fragment EXPONENT: [eE][-+]?[0-9]+;
FLOAT_NUMBER: ([0-9]+ '.' [0-9]* EXPONENT?) | ([0-9]+ EXPONENT);
OCT_NUMBER: '0' [0-7]*;
HEX_NUMBER: '0' [xX] [0-9a-fA-F]+;
DEC_NUMBER: [1-9][0-9]*[lL]?;

DOT      : '.';
LP       : '(';
RP       : ')';
COMMA    : ',';
PLUS     : '+';
MINUS    : '-';
NEG      : '!';
MUL      : '*';
DIV      : '/';
MOD      : '%';
AND      : '&&';
OR       : '||';
GE       : '>=';
LE       : '<=';
GT       : '>';
LT       : '<';
EQ       : '==';
NEQ      : '!=';
TRUE     : 'true';
FALSE    : 'false';
NULL     : 'null';

HEADER   : 'header';
BODY     : 'body';
PROPERTY : 'property';
CONSTANT : 'constant';

IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;
PATH_ELEMENT: ( ~[ .\t\r\n\\+\-*!><,=%()|&/] | '\\' [ .\t\r\n\\+\-*!><,=%()|&/_] )+;
