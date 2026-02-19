export class FilterCondition {
  public static readonly CONTAINS = new FilterCondition("CONTAINS", "Contains");
  public static readonly DOES_NOT_CONTAIN = new FilterCondition(
    "DOES_NOT_CONTAIN",
    "Does not contain",
  );
  public static readonly STARTS_WITH = new FilterCondition(
    "STARTS_WITH",
    "Starts with",
  );
  public static readonly ENDS_WITH = new FilterCondition(
    "ENDS_WITH",
    "Ends with",
  );
  public static readonly IN = new FilterCondition("IN", "In");
  public static readonly NOT_IN = new FilterCondition("NOT_IN", "Not in");
  public static readonly IS = new FilterCondition("IS", "Is");
  public static readonly IS_NOT = new FilterCondition("IS_NOT", "Is not");
  public static readonly EMPTY = new FilterCondition("EMPTY", "Empty", false);
  public static readonly NOT_EMPTY = new FilterCondition(
    "NOT_EMPTY",
    "Not empty",
    false,
  );
  public static readonly IS_BEFORE = new FilterCondition(
    "IS_BEFORE",
    "Is before",
  );
  public static readonly IS_AFTER = new FilterCondition("IS_AFTER", "Is after");
  public static readonly IS_WITHIN = new FilterCondition(
    "IS_WITHIN",
    "Is within",
  );

  private static VALUES: FilterCondition[] = [
    this.CONTAINS,
    this.DOES_NOT_CONTAIN,
    this.STARTS_WITH,
    this.ENDS_WITH,
    this.IN,
    this.NOT_IN,
    this.IS,
    this.IS_NOT,
    this.EMPTY,
    this.NOT_EMPTY,
    this.IS_BEFORE,
    this.IS_AFTER,
    this.IS_WITHIN,
  ];

  public readonly id: string;
  public readonly name: string;
  public readonly valueRequired: boolean = true;

  private constructor(id: string, name: string, valueRequired?: boolean) {
    this.id = id;
    this.name = name;
    if (valueRequired !== undefined) {
      this.valueRequired = valueRequired;
    }
  }

  public static getById(id: string): FilterCondition | undefined {
    for (const value of FilterCondition.VALUES) {
      if (id === value.id) {
        return value;
      }
    }
    return undefined;
  }
}

export enum FilterValueType {
  LIST = "LIST",
  STRING = "STRING",
  DATE = "DATE",
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
};

export type ListValue = {
  value: string;
  label: string;
};

export const StringFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.STARTS_WITH,
    FilterCondition.ENDS_WITH,
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
    FilterCondition.EMPTY,
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
    FilterCondition.NOT_EMPTY,
  ],
  valueType: FilterValueType.STRING,
};

export const ListFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.IN,
  allowedConditions: [FilterCondition.IN, FilterCondition.NOT_IN],
  valueType: FilterValueType.LIST,
};

export const DateFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.IS_BEFORE,
  allowedConditions: [
    FilterCondition.IS_AFTER,
    FilterCondition.IS_BEFORE,
    FilterCondition.IS_WITHIN,
  ],
  valueType: FilterValueType.DATE,
};

export interface EntityFilterModel {
  column: FilterColumn["name"];
  condition: string;
  value?: string;
}
