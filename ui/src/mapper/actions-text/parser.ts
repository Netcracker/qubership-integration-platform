import { generate, Location, LocationRange, PegjsError } from "pegjs";
import {
  LocationAware,
  MappingAction,
  MappingTextProcessError,
} from "./model.ts";

const mappingActionsParser = generate(`
    {
        function unescape(value) {
            return value.map(i => i.length > 1 ? (i[1] === '_' ? '' : i[1]) : i[0]).join('');
        }
    }

    Actions = actions:Action* (NL / WS)*
    { return actions; }

    Action = (NL / WS)* sources:(Source WS)+ "->" WS target:Target
    transformation:(WS ":" WS Transformation)?
        { return { sources: sources.map(i => i[0]), target, transformation: transformation? transformation[3] : null, location: location() }; }

    Source = AttributeReference / Constant

    Target = AttributeReference

    AttributeReference = kind:Kind "." path:Path
    { return { type: "attributeReference", kind, path, location: location() }; }

    Transformation = name:Identifier parameters:(WS ConstantValue)*
    { return { name, parameters: parameters.map(i => i[1]), location: location() }; }

    Kind = "header" / "body" / "property"

    Path = head:Identifier tail:("." Identifier)*
    { return [head, ...tail.map(i => i[1])]; }

    Constant = "constant" "." name:ConstantValue
    { return { type: "constant", name, location: location() }; }

    Identifier "identifier" = value:([^ .\\t\\r\\n\\\\] / "\\\\" [ .\\t\\r\\n\\\\_])+
    { return unescape(value); }

    ConstantValue "constantValue" = value:([^ \\t\\r\\n\\\\] / "\\\\" [ \\t\\r\\n\\\\_])+
    { return unescape(value); }

    NL "newline" = [\\r\\n]

    WS "whitespace" = [ \\t]+
`);

export function isLocation(obj: unknown): obj is Location {
  return (
    obj !== undefined &&
    obj !== null &&
    typeof obj === "object" &&
    "line" in obj &&
    "column" in obj &&
    "offset" in obj &&
    typeof obj.line === "number" &&
    typeof obj.column === "number" &&
    typeof obj.offset === "number"
  );
}

export function isLocationRange(obj: unknown): obj is LocationRange {
  return (
    obj !== undefined &&
    obj !== null &&
    typeof obj === "object" &&
    "start" in obj &&
    "end" in obj &&
    isLocation(obj.start) &&
    isLocation(obj.end)
  );
}

export function isParseError(obj: unknown): obj is PegjsError {
  return (
    obj !== undefined &&
    obj !== null &&
    typeof obj === "object" &&
    "location" in obj &&
    "message" in obj &&
    isLocationRange(obj.location) &&
    typeof obj.message === "string"
  );
}

function shiftLine(location: Location, offset: number): Location {
  return { ...location, line: location.line + offset };
}

function shiftLinesInRange(
  range: LocationRange,
  offset: number,
): LocationRange {
  return {
    start: shiftLine(range.start, offset),
    end: shiftLine(range.end, offset),
  };
}

function shiftLocation<T extends LocationAware>(obj: T, offset: number): T {
  return obj
    ? { ...obj, location: shiftLinesInRange(obj.location, offset) }
    : obj;
}

function shiftLocationsInMappingAction(
  action: MappingAction,
  offset: number,
): MappingAction {
  return {
    sources: action.sources.map((s) => shiftLocation(s, offset)),
    target: shiftLocation(action.target, offset),
    location: shiftLinesInRange(action.location, offset),
    transformation: shiftLocation(action.transformation, offset),
  };
}

export interface MappingActionsParseResult {
  actions: MappingAction[];
  errors: MappingTextProcessError[];
}

interface LineRange {
  startLine: number;
  endLine: number;
}

function parseAsMuchAsPossibleAndCollectErrors(
  text: string,
): MappingActionsParseResult {
  const lines = text.split("\n");
  const actions: MappingAction[] = [];
  const errors: MappingTextProcessError[] = [];
  const fragmentsToParse: LineRange[] = [
    { startLine: 0, endLine: lines.length },
  ];
  while (fragmentsToParse.length !== 0) {
    const fragmentRange = fragmentsToParse.pop()!;
    const fragmentText = lines
      .slice(fragmentRange.startLine, fragmentRange.endLine)
      .join("\n");
    try {
      const fragmentActions = (
        mappingActionsParser.parse(fragmentText) as MappingAction[]
      ).map((a) => shiftLocationsInMappingAction(a, fragmentRange.startLine));
      actions.push(...fragmentActions);
    } catch (exception) {
      if (isParseError(exception)) {
        const offset = exception.location.end.line;
        const location = shiftLinesInRange(
          exception.location,
          fragmentRange.startLine,
        );
        const error = { location, message: exception.message };
        errors.push(error);
        fragmentsToParse.push(
          {
            startLine: fragmentRange.startLine,
            endLine: fragmentRange.startLine + offset - 1,
          },
          {
            startLine: fragmentRange.startLine + offset,
            endLine: fragmentRange.endLine,
          },
        );
      }
    }
  }
  return { actions, errors };
}

export function parse(text: string): MappingActionsParseResult {
  return parseAsMuchAsPossibleAndCollectErrors(text);
}
