import * as peg from "pegjs";
import { MappingActions } from "../mapper/actions-text/util.ts";

export type KeyValuePair = {
  key: string;
  value: string;
};

const keyValueListParser = peg.generate(`
KeyValuePairs = _ pairs:(KeyValuePair _)* {
    return pairs.map(i => i[0]);
}

KeyValuePair = key:Key "=" value:Value ";" {
    return { key, value };
}

Key = Token

Value = Token

Token = value:([^=;\\\\] / "\\\\" [=;\\\\])* {
    return value.map(i => i.length > 1 ? i[1] : i[0]).join('');
}

_ "whitespace"
  = [ \\t\\n\\r]*
`);

export function parse(text: string): KeyValuePair[] {
  return keyValueListParser.parse(text) as KeyValuePair[];
}

export function buildKeyValuePairsFromObject(
  obj: Record<string, unknown>,
): KeyValuePair[] {
  return Object.entries(obj).map(([key, value]) => ({
    key,
    value: String(value),
  }));
}

export function buildObjectFromKeyValuePairs(
  pairs: Readonly<KeyValuePair[]>,
): Record<string, string> {
  return Object.fromEntries(pairs.map((kv) => [kv.key, kv.value]));
}

export function makeArray(pairs: Readonly<KeyValuePair[]>): string[] {
  return pairs.map(({ key, value }) =>
    [key, value].map((i) => MappingActions.escape(i, "=;\\")).join("="),
  );
}

export function sliceParameter(value: string[] | undefined): string {
  if (!value || value.length <= 1) return "";
  return value.slice(1).join(";\n") + ";";
}

export function removeDuplicateKeys(data: KeyValuePair[]): KeyValuePair[] {
  const seenKeys = new Set<string>();
  return data.filter((item) => {
    if (seenKeys.has(item.key)) {
      return false;
    } else {
      seenKeys.add(item.key);
      return true;
    }
  });
}
