export interface TransformationParameterInfo {
  name: string;
  minArguments: number;
  maxArguments: number;
}

export interface TransformationInfo {
  title: string;
  name: string;
  parameters: TransformationParameterInfo[];
}

export const TRANSFORMATIONS: TransformationInfo[] = [
  {
    title: "conditional",
    name: "conditional",
    parameters: [
      { name: "condition", minArguments: 1, maxArguments: 1 },
      { name: "true expression", minArguments: 1, maxArguments: 1 },
      { name: "false expression", minArguments: 0, maxArguments: 1 },
    ],
  },
  {
    title: "default value",
    name: "defaultValue",
    parameters: [{ name: "value", minArguments: 1, maxArguments: 1 }],
  },
  {
    title: "dictionary",
    name: "dictionary",
    parameters: [
      { name: "default", minArguments: 1, maxArguments: 1 },
      { name: "rules", minArguments: 0, maxArguments: Infinity },
    ],
  },
  {
    title: "expression",
    name: "expression",
    parameters: [{ name: "expression", minArguments: 1, maxArguments: 1 }],
  },
  {
    title: "format date/time",
    name: "formatDateTime",
    parameters: [
      { name: "input is unix epoch", minArguments: 1, maxArguments: 1 },
      { name: "input format", minArguments: 1, maxArguments: 1 },
      { name: "input locale", minArguments: 1, maxArguments: 1 },
      { name: "input time zone", minArguments: 1, maxArguments: 1 },
      { name: "output is unix epoch", minArguments: 1, maxArguments: 1 },
      { name: "output format", minArguments: 1, maxArguments: 1 },
      { name: "output locale", minArguments: 1, maxArguments: 1 },
      { name: "output time zone", minArguments: 1, maxArguments: 1 },
    ],
  },
  {
    title: "trim",
    name: "trim",
    parameters: [{ name: "side", minArguments: 1, maxArguments: 1 }],
  },
  {
    title: "replace all",
    name: "replaceAll",
    parameters: [
      { name: "regex", minArguments: 1, maxArguments: 1 },
      { name: "replacement", minArguments: 1, maxArguments: 1 },
    ],
  },
];

export function bindParameterValues(
  parameters: TransformationParameterInfo[],
  values: string[],
): [TransformationParameterInfo, string[]][] {
  return parameters.map((parameter) => {
    const args = values.slice(0, parameter.maxArguments);
    values = values.slice(parameter.maxArguments);
    return [parameter, args];
  });
}
