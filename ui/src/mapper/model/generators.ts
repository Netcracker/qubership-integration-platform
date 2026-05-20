import { TransformationInfo } from "./transformations.ts";
import { DataType } from "./model.ts";

export const GENERATORS: TransformationInfo[] = [
  {
    title: "UUID",
    name: "generateUUID",
    parameters: [],
  },
  {
    title: "current date",
    name: "currentDate",
    parameters: [
      { name: "is unix epoch", minArguments: 1, maxArguments: 1 },
      { name: "format", minArguments: 1, maxArguments: 1 },
      { name: "locale", minArguments: 1, maxArguments: 1 },
      { name: "time zone", minArguments: 1, maxArguments: 1 },
    ],
  },
  {
    title: "current time",
    name: "currentTime",
    parameters: [
      { name: "is unix epoch", minArguments: 1, maxArguments: 1 },
      { name: "format", minArguments: 1, maxArguments: 1 },
      { name: "locale", minArguments: 1, maxArguments: 1 },
      { name: "time zone", minArguments: 1, maxArguments: 1 },
    ],
  },
  {
    title: "current date and time",
    name: "currentDateTime",
    parameters: [
      { name: "is unix epoch", minArguments: 1, maxArguments: 1 },
      { name: "format", minArguments: 1, maxArguments: 1 },
      { name: "locale", minArguments: 1, maxArguments: 1 },
      { name: "time zone", minArguments: 1, maxArguments: 1 },
    ],
  },
];

export function getGeneratorsForType(type: DataType): TransformationInfo[] {
  switch (type?.name) {
    case "string":
      return GENERATORS;
    default:
      return [];
  }
}
