import * as peg from "pegjs";
import { MappingActions } from "../../../../mapper/actions-text/util";

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

export function makeString(pairs: Readonly<KeyValuePair[]>): string {
  return pairs
    .map(
      ({ key, value }) =>
        [key, value].map((i) => MappingActions.escape(i, "=;\\")).join("=") +
        ";\n",
    )
    .join("");
}
