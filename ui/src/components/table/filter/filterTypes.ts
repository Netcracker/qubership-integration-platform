export type FilterConditionFunc = (
  filterValue: string | undefined,
  cellValue: string | undefined,
) => boolean;

export class FilterCondition {
  public static readonly CONTAINS = new FilterCondition(
    "CONTAINS",
    "Contains",
    (f, v) => !f || !!v?.toLowerCase().includes(f.toLowerCase()),
  );
  public static readonly DOES_NOT_CONTAIN = new FilterCondition(
    "DOES_NOT_CONTAIN",
    "Does not contain",
    (f, v) => !f || !v?.toLowerCase().includes(f.toLowerCase()),
  );
  public static readonly STARTS_WITH = new FilterCondition(
    "STARTS_WITH",
    "Starts with",
    (f, v) => !f || !!v?.toLowerCase().startsWith(f.toLowerCase()),
  );
  public static readonly ENDS_WITH = new FilterCondition(
    "ENDS_WITH",
    "Ends with",
    (f, v) => !f || !!v?.toLowerCase().endsWith(f.toLowerCase()),
  );
  public static readonly IN = new FilterCondition(
    "IN",
    "In",
    (f, v) =>
      !f || (!!v && f.toLowerCase().split(",").includes(v.toLowerCase())),
  );
  public static readonly NOT_IN = new FilterCondition(
    "NOT_IN",
    "Not in",
    (f, v) => !f || (!!v && !f.toLowerCase().split(",").includes(v.toLowerCase())),
  );
  public static readonly IS = new FilterCondition(
    "IS",
    "Is",
    (f, v) => !f || f.toLowerCase() === v?.toLowerCase(),
  );
  public static readonly IS_NOT = new FilterCondition(
    "IS_NOT",
    "Is not",
    (f, v) => !f || f.toLowerCase() !== v?.toLowerCase(),
  );
  public static readonly EMPTY = new FilterCondition(
    "EMPTY",
    "Empty",
    (_f, v) => !v?.trim(),
    false,
  );
  public static readonly NOT_EMPTY = new FilterCondition(
    "NOT_EMPTY",
    "Not empty",
    (_f, v) => !!v?.trim(),
    false,
  );
  public static readonly IS_BEFORE = new FilterCondition(
    "IS_BEFORE",
    "Is before",
    (f, v) => !f || Number(v) < Number(f),
  );
  public static readonly IS_AFTER = new FilterCondition(
    "IS_AFTER",
    "Is after",
    (f, v) => !f || Number(v) > Number(f),
  );
  public static readonly IS_WITHIN = new FilterCondition(
    "IS_WITHIN",
    "Is within",
    (f, v) => {
      if (!f) return true;
      const [from, to] = f.split(",").map(Number);
      return Number(v) >= from && Number(v) <= to;
    },
  );
  public static readonly LESS_THAN = new FilterCondition(
    "LESS_THAN",
    "Less than",
    (f, v) => !f || Number(v) < Number(f),
  );
  public static readonly GREATER_THAN = new FilterCondition(
    "GREATER_THAN",
    "Greater than",
    (f, v) => !f || Number(v) > Number(f),
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
    this.LESS_THAN,
    this.GREATER_THAN,
  ];

  public readonly id: string;
  public readonly name: string;
  public readonly func: FilterConditionFunc;
  public readonly valueRequired: boolean = true;

  private constructor(
    id: string,
    name: string,
    func: FilterConditionFunc,
    valueRequired?: boolean,
  ) {
    this.id = id;
    this.name = name;
    this.func = func;
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
  NUMBER = "NUMBER",
  BOOLEAN = "BOOLEAN",
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

export const BooleanFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.IS,
  allowedConditions: [FilterCondition.IS],
  valueType: FilterValueType.BOOLEAN,
};

export const NumberFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.GREATER_THAN,
  allowedConditions: [FilterCondition.LESS_THAN, FilterCondition.GREATER_THAN],
  valueType: FilterValueType.NUMBER,
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

export const ContainsAndDoesNotFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
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

export const ExtendedStringFilterConditions: FilterConditions = {
  defaultCondition: FilterCondition.CONTAINS,
  allowedConditions: [
    FilterCondition.IS,
    FilterCondition.IS_NOT,
    FilterCondition.CONTAINS,
    FilterCondition.DOES_NOT_CONTAIN,
    FilterCondition.STARTS_WITH,
    FilterCondition.ENDS_WITH,
  ],
  valueType: FilterValueType.STRING,
};

export interface EntityFilterModel {
  column: FilterColumn["name"];
  condition: string;
  value?: string;
}
