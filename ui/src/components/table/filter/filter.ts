export class FilterCondition {
  public static readonly CONTAINS = { id: 'CONTAINS', name: 'Contains' };
  public static readonly DOES_NOT_CONTAIN = { id: 'DOES_NOT_CONTAIN', name: 'Does not contain' };
  public static readonly STARTS_WITH = { id: 'START_WITH', name: 'Starts with' };
  public static readonly ENDS_WITH = { id: 'ENDS_WITH', name: 'Ends with' };
  public static readonly IN = { id: 'IN', name: 'In' };
  public static readonly NOT_IN = { id: 'NOT_IN', name: 'Not in' };
  public static readonly IS = { id: 'IS', name: 'Is' };
  public static readonly IS_NOT = { id: 'IS_NOT', name: 'Is not' };
  public static readonly EMPTY = { id: 'EMPTY', name: 'Empty', valueRequired: false };
  public static readonly NOT_EMPTY = { id: 'NOT_EMPTY', name: 'Not empty', valueRequired: false };

  private constructor(
    public readonly id: string,
    public readonly name: string,
    public readonly valueRequired?: boolean,
  ) { }
}

export enum FilterValueType {
  LIST = 'LIST',
  STRING = 'STRING',
  DATE = 'DATE',
}

export interface FilterConditions {
  defaultCondition: FilterCondition;
  allowedConditions: FilterCondition[];
  valueType: FilterValueType;
}

export type FilterColumn = {
  id: string;
  name: string;
  conditions: FilterConditions;
  allowedValues?: ListValue[];
}

export type ListValue = {
  value: string;
  label: string;
}

export const StringFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.STARTS_WITH,
    FilterCondition.ENDS_WITH
  ],
  valueType: FilterValueType.STRING,
};

export const AdvancedFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.IS,
    FilterCondition.IS_NOT,
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.STARTS_WITH,
    FilterCondition.ENDS_WITH,
    FilterCondition.EMPTY
  ],
  valueType: FilterValueType.STRING,
};

export const IdFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.IS,
    FilterCondition.IS_NOT,
    FilterCondition.CONTAINS,
  ],
  valueType: FilterValueType.STRING,
};

export const DescriptionFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.EMPTY,
    FilterCondition.NOT_EMPTY
  ],
  valueType: FilterValueType.STRING,
};

export const ListFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.IN,
  allowedConditions: [
    FilterCondition.IN, FilterCondition.NOT_IN
  ],
  valueType: FilterValueType.LIST,
};

export interface EntityFilterModel {
  column: FilterColumn["name"];
  condition: string;
  value?: string;
}
