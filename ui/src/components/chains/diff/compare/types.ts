import { Connection, Element } from "../../../../api/apiTypes.ts";

export type GenericChange<K, T> = {
  id: string;
  kind: K;
  one?: T;
  another?: T;
};

export type PropertyDetails = {
  entityId: string;
  name: string;
  value: unknown;
};
export type ChangedSide = "one" | "another";
export type ChangeKind =
  | "element"
  | "chain-property"
  | "element-property"
  | "connection";
export type ElementChange = GenericChange<"element", Element>;
export type ChainPropertyChange = GenericChange<
  "chain-property",
  PropertyDetails
>;
export type ElementPropertyChange = GenericChange<
  "element-property",
  PropertyDetails
>;
export type ConnectionChange = GenericChange<"connection", Connection>;
export type Change =
  | ElementChange
  | ChainPropertyChange
  | ElementPropertyChange
  | ConnectionChange;
