/**
 * The describe tree and the cases of a spec file, read with the TypeScript parser, and the title
 * rules of the `e2e-spec-authoring` skill (rules 10 and 21) checked over them.
 *
 * The parser sees only code, so a `test(` or a tag quoted in a string or a comment is not a case.
 */
import ts from "typescript";

/** How a title is written: a string literal, a template string, or any other expression. */
export type TitleKind = "literal" | "template" | "identifier";

export interface Title {
  kind: TitleKind;
  /** The literal text; the literal parts of a template; the source text of any other expression. */
  text: string;
}

export interface Annotation {
  type?: string;
  /** The literal text, or the source text of an expression that is not a literal. */
  description?: string;
}

export interface Declaration {
  /** Absent on an anonymous describe. */
  title?: Title;
  line: number;
  /** The string-literal tags in the options object. */
  tags: string[];
  /** Whether a tag, or the options object itself, is an expression this parser cannot evaluate. */
  computedTags: boolean;
  annotations: Annotation[];
}

export interface Describe extends Declaration {
  describes: Describe[];
  cases: Declaration[];
}

export interface ParsedSpec {
  /** The top-level describes. */
  describes: Describe[];
  /** The cases outside any describe. */
  cases: Declaration[];
  /** The lines of a `test.fail()` without arguments outside a case body. */
  bareFails: number[];
}

/** Every tag a spec may spell as a literal. */
export const TITLE_TAGS: readonly string[] = [
  "@catalog",
  "@engine",
  "@sessions",
  "@testing-service",
  "@ui",
  "@infra",
  "@schema",
  "@harness",
  "@slow",
  "@classic-only",
  "@kafka",
  "@rabbitmq",
  "@pubsub",
  "@sftp",
  "@v1",
  "@v2",
  "@v3",
];

export const MAX_TITLE_LENGTH = 60;

/** Phrases that carry a reason, which belongs in a comment above the case. */
const REASON_PHRASES = /\b(because|which is why|rather than|so that)\b/i;

const CASE_MODIFIERS = new Set(["only", "fail", "fixme", "skip"]);
const DESCRIBE_MODIFIERS = new Set(["only", "skip", "fixme", "serial", "parallel"]);

/** `test.describe.serial` as `["test", "describe", "serial"]`; undefined unless rooted at an identifier. */
function calleePath(expression: ts.Expression): string[] | undefined {
  if (ts.isIdentifier(expression)) return [expression.text];
  if (ts.isPropertyAccessExpression(expression)) {
    const head = calleePath(expression.expression);
    return head && [...head, expression.name.text];
  }
  return undefined;
}

function isFunction(node: ts.Node | undefined): boolean {
  return node !== undefined && (ts.isArrowFunction(node) || ts.isFunctionExpression(node));
}

type CallKind = "case" | "describe" | "bare-fail" | undefined;

function callKind(call: ts.CallExpression): CallKind {
  const path = calleePath(call.expression);
  if (!path || path[0] !== "test") return undefined;
  const args = call.arguments;
  const [, second, ...rest] = path;
  if (second === undefined) return "case";
  if (second === "describe") return rest.every((each) => DESCRIBE_MODIFIERS.has(each)) ? "describe" : undefined;
  if (rest.length > 0 || !CASE_MODIFIERS.has(second)) return undefined;
  if (second === "fail" && args.length === 0) return "bare-fail";
  // `test.skip(condition, "reason")` and `test.fail(callback, "reason")` end in no body.
  return args.length >= 2 && isFunction(args[args.length - 1]) && !isFunction(args[0]) ? "case" : undefined;
}

function readTitle(node: ts.Expression, source: ts.SourceFile): Title {
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return { kind: "literal", text: node.text };
  if (ts.isTemplateExpression(node)) {
    return { kind: "template", text: node.head.text + node.templateSpans.map((span) => span.literal.text).join("") };
  }
  return { kind: "identifier", text: node.getText(source) };
}

function literalText(node: ts.Expression): string | undefined {
  return ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node) ? node.text : undefined;
}

function property(object: ts.ObjectLiteralExpression, name: string): ts.Expression | undefined {
  for (const each of object.properties) {
    if (ts.isPropertyAssignment(each) && (ts.isIdentifier(each.name) || ts.isStringLiteral(each.name)) && each.name.text === name) {
      return each.initializer;
    }
  }
  return undefined;
}

function readAnnotation(node: ts.Expression, source: ts.SourceFile): Annotation {
  if (!ts.isObjectLiteralExpression(node)) return {};
  const type = property(node, "type");
  const description = property(node, "description");
  return {
    type: type && literalText(type),
    description: description && (literalText(description) ?? description.getText(source)),
  };
}

function declaration(call: ts.CallExpression, source: ts.SourceFile): Declaration {
  const args = call.arguments;
  const line = source.getLineAndCharacterOfPosition(call.getStart(source)).line + 1;
  const found: Declaration = { line, tags: [], computedTags: false, annotations: [] };
  if (args.length > 0 && !isFunction(args[0])) found.title = readTitle(args[0], source);
  if (args.length < 3) return found;

  const options = args[1];
  if (!ts.isObjectLiteralExpression(options)) {
    found.computedTags = true;
    return found;
  }
  const tag = property(options, "tag");
  for (const each of tag && ts.isArrayLiteralExpression(tag) ? tag.elements : tag ? [tag] : []) {
    const text = literalText(each);
    if (text === undefined) found.computedTags = true;
    else found.tags.push(text);
  }
  const annotation = property(options, "annotation");
  for (const each of annotation && ts.isArrayLiteralExpression(annotation) ? annotation.elements : annotation ? [annotation] : []) {
    found.annotations.push(readAnnotation(each, source));
  }
  return found;
}

/** The describe tree of a spec file. A case body is not read, so a `test.step` or a `test.fail()` in it is neither. */
export function parseCases(text: string): ParsedSpec {
  const source = ts.createSourceFile("spec.ts", text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const spec: ParsedSpec = { describes: [], cases: [], bareFails: [] };

  function walk(node: ts.Node, owner: { describes: Describe[]; cases: Declaration[] }): void {
    if (ts.isCallExpression(node)) {
      const kind = callKind(node);
      if (kind === "case") {
        owner.cases.push(declaration(node, source));
        return;
      }
      if (kind === "describe") {
        const describe: Describe = { ...declaration(node, source), describes: [], cases: [] };
        owner.describes.push(describe);
        const body = node.arguments[node.arguments.length - 1];
        if (isFunction(body)) walk(body, describe);
        return;
      }
      if (kind === "bare-fail") spec.bareFails.push(source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1);
    }
    ts.forEachChild(node, (child) => walk(child, owner));
  }

  walk(source, spec);
  return spec;
}

/** Every describe and case of a parsed file, in the order they are declared. */
export function declarations(spec: ParsedSpec): Declaration[] {
  const all: Declaration[] = [...spec.cases];
  const visit = (describe: Describe) => {
    all.push(describe, ...describe.cases);
    describe.describes.forEach(visit);
  };
  spec.describes.forEach(visit);
  return all.sort((a, b) => a.line - b.line);
}

/** The title rules a spec file breaks, each as `file:line: rule`. */
export function titleProblems(text: string, file: string): string[] {
  const spec = parseCases(text);
  const problems: { line: number; rule: string }[] = [];

  for (const each of spec.cases) problems.push({ line: each.line, rule: "a case outside any test.describe" });
  for (const each of spec.describes.slice(1)) problems.push({ line: each.line, rule: "a second top-level test.describe" });
  for (const line of spec.bareFails) problems.push({ line, rule: "a test.fail() with no arguments marks every case in its scope" });

  const cases = new Set<Declaration>(spec.cases);
  const collect = (describe: Describe) => {
    describe.cases.forEach((each) => cases.add(each));
    describe.describes.forEach(collect);
  };
  spec.describes.forEach(collect);

  for (const each of declarations(spec)) {
    for (const tag of each.tags) {
      if (!TITLE_TAGS.includes(tag)) problems.push({ line: each.line, rule: `the tag ${tag} is not in the closed list` });
    }
    const title = each.title;
    if (!cases.has(each) || !title || title.kind === "identifier") continue;
    if (title.text.length > MAX_TITLE_LENGTH) {
      problems.push({ line: each.line, rule: `a title of ${title.text.length} characters, over ${MAX_TITLE_LENGTH}` });
    }
    const phrase = REASON_PHRASES.exec(title.text);
    if (phrase) problems.push({ line: each.line, rule: `"${phrase[1]}" in a title: the reason goes in a comment above the case` });
  }

  return problems.sort((a, b) => a.line - b.line).map((each) => `${file}:${each.line}: ${each.rule}`);
}
