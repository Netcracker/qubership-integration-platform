import React from "react";
import { MappingDescription } from "../../mapper/model/model.ts";
import { MappingTableView } from "./MappingTableView.tsx";

export type MappingProps = React.HTMLAttributes<HTMLElement> & {
  elementId: string;
  mapping?: MappingDescription;
  readonlySource?: boolean;
  readonlyTarget?: boolean;
  onChange?: (mapping: MappingDescription) => void;
};

export const Mapping: React.FC<MappingProps> = (props) => {
  return <MappingTableView {...props} />;
}
