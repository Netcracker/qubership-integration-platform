import { LocationRange } from "pegjs";
import { AttributeKind } from "../model/model.ts";

export interface LocationAware {
  location: LocationRange;
}

export interface Transformation extends LocationAware {
  name: string;
  parameters: string[];
}

export interface AttributeReference extends LocationAware {
  type: "attributeReference";
  kind: AttributeKind;
  path: string[];
}

export interface ConstantReference extends LocationAware {
  type: "constant";
  name: string;
}

export interface MappingAction extends LocationAware {
  sources: (ConstantReference | AttributeReference)[];
  target: AttributeReference;
  transformation: Transformation;
}

export interface MappingTextProcessError {
  location: LocationRange;
  message: string;
}

export class MappingUpdateException extends Error {
  public location: LocationRange;

  constructor(message: string, location: LocationRange) {
    super(message);
    Object.setPrototypeOf(this, MappingUpdateException);
    this.location = location;
  }
}
