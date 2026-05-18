import { IRange, languages } from "monaco-editor";
import {
  getDotChainPrefix,
  markdown,
  PartialCompletionItem,
  withRange,
} from "./completion-helpers";

export const GROOVY_STATEMENT_KEYWORDS: readonly string[] = [
  "abstract",
  "assert",
  "boolean",
  "break",
  "byte",
  "case",
  "catch",
  "char",
  "class",
  "const",
  "continue",
  "def",
  "default",
  "do",
  "double",
  "else",
  "enum",
  "extends",
  "final",
  "finally",
  "float",
  "for",
  "goto",
  "if",
  "implements",
  "import",
  "int",
  "interface",
  "long",
  "native",
  "package",
  "private",
  "protected",
  "public",
  "return",
  "short",
  "static",
  "strictfp",
  "switch",
  "synchronized",
  "throw",
  "throws",
  "transient",
  "try",
  "void",
  "volatile",
  "while",
];

export const GROOVY_EXPRESSION_KEYWORDS: readonly string[] = [
  "as",
  "false",
  "in",
  "instanceof",
  "new",
  "null",
  "super",
  "this",
  "true",
];

export const GROOVY_KEYWORDS: readonly string[] = [
  ...GROOVY_STATEMENT_KEYWORDS,
  ...GROOVY_EXPRESSION_KEYWORDS,
].sort((a, b) => a.localeCompare(b));

const GROOVY_SNIPPET_DEFINITIONS: {
  label: string;
  insertText: string;
  doc: string;
}[] = [
  {
    label: "if",
    insertText: "if (${1:condition}) {\n\t$0\n}",
    doc: "if statement",
  },
  {
    label: "ifelse",
    insertText: "if (${1:condition}) {\n\t$2\n} else {\n\t$0\n}",
    doc: "if/else statement",
  },
  {
    label: "for",
    insertText: "for (int ${1:i} = 0; $1 < ${2:n}; $1++) {\n\t$0\n}",
    doc: "Classic for loop",
  },
  {
    label: "forin",
    insertText: "for (${1:item} in ${2:collection}) {\n\t$0\n}",
    doc: "for-in loop",
  },
  {
    label: "while",
    insertText: "while (${1:condition}) {\n\t$0\n}",
    doc: "while loop",
  },
  {
    label: "try",
    insertText: "try {\n\t$1\n} catch (${2:Exception} ${3:e}) {\n\t$0\n}",
    doc: "try/catch block",
  },
  {
    label: "tryf",
    insertText:
      "try {\n\t$1\n} catch (${2:Exception} ${3:e}) {\n\t$4\n} finally {\n\t$0\n}",
    doc: "try/catch/finally block",
  },
  {
    label: "closure",
    insertText: "{ ${1:it} -> $0 }",
    doc: "Groovy closure",
  },
  {
    label: "switch",
    insertText:
      "switch (${1:value}) {\n\tcase ${2:option}:\n\t\t$0\n\t\tbreak\n\tdefault:\n\t\tbreak\n}",
    doc: "switch/case statement",
  },
  {
    label: "method",
    insertText: "def ${1:name}(${2:args}) {\n\t$0\n}",
    doc: "Method definition",
  },
  {
    label: "each",
    insertText: "${1:collection}.each { ${2:it} ->\n\t$0\n}",
    doc: "Iterate over collection",
  },
  {
    label: "find",
    insertText: "${1:collection}.find { ${2:it} -> ${3:condition} }",
    doc: "Find first match in collection",
  },
  {
    label: "collect",
    insertText: "${1:collection}.collect { ${2:it} -> $0 }",
    doc: "Transform collection",
  },
];

const EXCHANGE_API_DEFINITIONS: {
  label: string;
  insertText: string;
  detail: string;
  doc: string;
}[] = [
  {
    label: "exchange",
    insertText: "exchange",
    detail: "org.apache.camel.Exchange",
    doc: "Current Camel Exchange (root variable in script)",
  },
  {
    label: "log",
    insertText: "log",
    detail: "org.slf4j.Logger",
    doc: "Logger available in script (use `log.info(...)`)",
  },
];

const EXCHANGE_MEMBER_DEFINITIONS: {
  label: string;
  insertText: string;
  doc: string;
  isSnippet?: boolean;
}[] = [
  {
    label: "getIn",
    insertText: "getIn()",
    doc: "Returns the inbound message of the exchange.",
  },
  {
    label: "getMessage",
    insertText: "getMessage()",
    doc: "Returns the message of the exchange (preferred over `getIn()` in Camel 3+).",
  },
  {
    label: "setMessage",
    insertText: "setMessage($0)",
    doc: "Sets the message of the exchange.",
    isSnippet: true,
  },
  {
    label: "getProperty",
    insertText: "getProperty('${1:name}')$0",
    doc: "Reads an exchange property by name.",
    isSnippet: true,
  },
  {
    label: "setProperty",
    insertText: "setProperty('${1:name}', ${2:value})$0",
    doc: "Sets an exchange property.",
    isSnippet: true,
  },
  {
    label: "getProperties",
    insertText: "getProperties()",
    doc: "Returns all exchange properties as a map.",
  },
  {
    label: "removeProperty",
    insertText: "removeProperty('${1:name}')$0",
    doc: "Removes an exchange property by name.",
    isSnippet: true,
  },
  {
    label: "getException",
    insertText: "getException()",
    doc: "Returns the exception associated with the exchange (if any).",
  },
  {
    label: "getContext",
    insertText: "getContext()",
    doc: "Returns the CamelContext.",
  },
  {
    label: "getFromEndpoint",
    insertText: "getFromEndpoint()",
    doc: "Returns the endpoint that produced the exchange.",
  },
];

const MESSAGE_MEMBER_DEFINITIONS: {
  label: string;
  insertText: string;
  doc: string;
  isSnippet?: boolean;
}[] = [
  {
    label: "getBody",
    insertText: "getBody()",
    doc: "Returns the message body.",
  },
  {
    label: "getBodyAs",
    insertText: "getBody(${1:Type}.class)$0",
    doc: "Returns the message body converted to the given type.",
    isSnippet: true,
  },
  {
    label: "setBody",
    insertText: "setBody($0)",
    doc: "Sets the message body.",
    isSnippet: true,
  },
  {
    label: "getHeader",
    insertText: "getHeader('${1:name}')$0",
    doc: "Returns a message header by name.",
    isSnippet: true,
  },
  {
    label: "getHeaders",
    insertText: "getHeaders()",
    doc: "Returns all message headers as a map.",
  },
  {
    label: "setHeader",
    insertText: "setHeader('${1:name}', ${2:value})$0",
    doc: "Sets a message header.",
    isSnippet: true,
  },
  {
    label: "removeHeader",
    insertText: "removeHeader('${1:name}')$0",
    doc: "Removes a message header by name.",
    isSnippet: true,
  },
  {
    label: "getMessageId",
    insertText: "getMessageId()",
    doc: "Returns the unique message id.",
  },
];

function makeItem(props: {
  label: string;
  insertText: string;
  kind: languages.CompletionItemKind;
  doc?: string;
  detail?: string;
  isSnippet?: boolean;
}): PartialCompletionItem {
  const item: PartialCompletionItem = {
    label: props.label,
    kind: props.kind,
    insertText: props.insertText,
  };
  if (props.doc) item.documentation = markdown(props.doc);
  if (props.detail) item.detail = props.detail;
  if (props.isSnippet) {
    item.insertTextRules =
      languages.CompletionItemInsertTextRule.InsertAsSnippet;
  }
  return item;
}

const { Keyword, Snippet, Method, Variable } = languages.CompletionItemKind;

const GROOVY_SUGGESTION_ITEMS = {
  keywords: GROOVY_KEYWORDS.map((kw) =>
    makeItem({ label: kw, insertText: kw, kind: Keyword }),
  ),
  expressionKeywords: GROOVY_EXPRESSION_KEYWORDS.map((kw) =>
    makeItem({ label: kw, insertText: kw, kind: Keyword }),
  ),
  snippets: GROOVY_SNIPPET_DEFINITIONS.map((d) =>
    makeItem({ ...d, kind: Snippet, isSnippet: true }),
  ),
  exchangeApi: EXCHANGE_API_DEFINITIONS.map((d) =>
    makeItem({ ...d, kind: Variable }),
  ),
  exchangeMembers: EXCHANGE_MEMBER_DEFINITIONS.map((d) =>
    makeItem({ ...d, kind: Method }),
  ),
  messageMembers: MESSAGE_MEMBER_DEFINITIONS.map((d) =>
    makeItem({ ...d, kind: Method }),
  ),
} as const satisfies Record<string, readonly PartialCompletionItem[]>;

export type GroovySuggestionGroup = keyof typeof GROOVY_SUGGESTION_ITEMS;

export function groovySuggestionsFor(
  group: GroovySuggestionGroup,
  range: IRange,
): languages.CompletionItem[] {
  return withRange(GROOVY_SUGGESTION_ITEMS[group], range);
}

export function resolveGroovyContextSuggestions(
  textBefore: string,
  range: IRange,
): languages.CompletionItem[] | null {
  const chain = getDotChainPrefix(textBefore);
  if (!chain || chain.length === 0) {
    return null;
  }
  const lastSegment = chain[chain.length - 1];
  if (lastSegment === "exchange") {
    return groovySuggestionsFor("exchangeMembers", range);
  }
  if (lastSegment === "getMessage()" || lastSegment === "getIn()") {
    return groovySuggestionsFor("messageMembers", range);
  }
  return [];
}

const GROOVY_COMMENTS_RE = /\/\*[\s\S]*?\*\/|\/\/[^\n]*/g;
const GROOVY_STRINGS_RE = /'''[\s\S]*?'''|"""[\s\S]*?"""|'[^'\n]*'|"[^"\n]*"/g;

function stripGroovyStringsAndComments(text: string): string {
  // Strings get collapsed to empty quotes so the surrounding code structure
  // (separators outside the literal) is preserved for the scanner that runs
  // next; comments can be removed wholesale.
  return text
    .replace(GROOVY_COMMENTS_RE, "")
    .replace(GROOVY_STRINGS_RE, (match) => (match[0] === "'" ? "''" : '""'));
}

const GROOVY_STATEMENT_SEPARATOR_CHARS = ";{}";

// Characters that, when they end the previous code, signal an unfinished
// expression continuing onto the next line. If we crossed a newline but the
// previous non-whitespace char is one of these, we are still inside an
// expression, not at a new statement.
const GROOVY_CONTINUATION_CHARS = "+-*/<>=!&|,(?:.[";

// Anchored to end of string; matches a single identifier without nested
// quantifiers, so runs linearly. NOSONAR
const TRAILING_IDENTIFIER_RE = /[A-Za-z_$][\w$]*$/;

export function isGroovyStatementStart(textBefore: string): boolean {
  const beforeWord = stripGroovyStringsAndComments(textBefore).replace(
    TRAILING_IDENTIFIER_RE,
    "",
  );
  let i = beforeWord.length - 1;
  let crossedNewline = false;
  while (i >= 0 && /\s/.test(beforeWord[i])) {
    if (beforeWord[i] === "\n") {
      crossedNewline = true;
    }
    i--;
  }
  if (i < 0) {
    return true;
  }
  const prev = beforeWord[i];
  if (GROOVY_STATEMENT_SEPARATOR_CHARS.includes(prev)) {
    return true;
  }
  return crossedNewline && !GROOVY_CONTINUATION_CHARS.includes(prev);
}

export function getGroovyCompletionItems(
  textBefore: string,
  range: IRange,
): languages.CompletionItem[] {
  const contextual = resolveGroovyContextSuggestions(textBefore, range);
  if (contextual !== null) {
    return contextual;
  }
  if (isGroovyStatementStart(textBefore)) {
    return [
      ...groovySuggestionsFor("keywords", range),
      ...groovySuggestionsFor("snippets", range),
      ...groovySuggestionsFor("exchangeApi", range),
    ];
  }
  return [
    ...groovySuggestionsFor("expressionKeywords", range),
    ...groovySuggestionsFor("exchangeApi", range),
  ];
}
