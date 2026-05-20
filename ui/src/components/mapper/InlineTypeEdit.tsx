import { DataType, TypeDefinition } from "../../mapper/model/model.ts";
import { DataTypes } from "../../mapper/util/types.ts";
import { InlineEdit } from "../InlineEdit.tsx";
import { SelectEdit } from "../table/SelectEdit.tsx";
import React, { ReactNode, useEffect, useState } from "react";
import { SelectProps } from "antd";
import { MappingUtil } from "../../mapper/util/mapping.ts";

type InlineTypeEditProps = {
  type?: DataType | null | undefined;
  disableArrayTypes?: boolean;
  disableObjectType?: boolean;
  definitions?: TypeDefinition[];
  readonly?: boolean;
  onSubmit?: (dataType: DataType | null | undefined) => void;
};

export function buildTypeOptions(
  type: DataType | null | undefined,
  typeDefinitions: TypeDefinition[],
  enableArrayTypes: boolean,
  enableObjectType: boolean,
): (NonNullable<SelectProps["options"]>[0] & { type: DataType })[] {
  const baseTypes = [
    DataTypes.stringType(),
    DataTypes.integerType(),
    DataTypes.booleanType(),
    ...(enableObjectType
      ? [
          DataTypes.objectType({
            id: MappingUtil.generateUUID(),
            attributes: [],
          }),
        ]
      : []),
  ];
  const arrayTypes = enableArrayTypes
    ? baseTypes.map((type) => DataTypes.arrayType(type))
    : [];
  const referenceTypes = typeDefinitions.map((typeDefinition) =>
    DataTypes.referenceType(typeDefinition.id),
  );
  const types = [...baseTypes, ...arrayTypes, ...referenceTypes];
  const hasType =
    type && types.some((t) => DataTypes.same(type, t, typeDefinitions));
  return [
    ...(hasType ? [] : [type!]),
    ...baseTypes,
    ...arrayTypes,
    ...referenceTypes,
  ].map((t) => ({
    value: MappingUtil.generateUUID(),
    label: DataTypes.buildTypeName(t, typeDefinitions),
    type: t,
  }));
}

export const InlineTypeEdit: React.FC<InlineTypeEditProps> = ({
  type,
  definitions,
  disableArrayTypes,
  disableObjectType,
  readonly,
  onSubmit,
}) => {
  const [typeName, setTypeName] = useState<ReactNode>("InlineTypeEdit");
  const [options, setOptions] = useState<ReturnType<typeof buildTypeOptions>>(
    [],
  );

  useEffect(() => {
    setTypeName(
      type ? DataTypes.buildTypeName(type, definitions ?? []) : <></>,
    );
  }, [type, definitions]);

  useEffect(() => {
    setOptions(
      buildTypeOptions(
        type,
        definitions ?? [],
        !disableArrayTypes,
        !disableObjectType,
      ),
    );
  }, [type, definitions, disableArrayTypes, disableObjectType]);

  return readonly ? (
    typeName
  ) : (
    <InlineEdit<{ type: string }>
      values={{
        type:
          options
            .find(
              (option) =>
                type && DataTypes.same(type, option.type, definitions ?? []),
            )
            ?.value?.toString() ?? "",
      }}
      editor={<SelectEdit name="type" options={options} />}
      viewer={typeName}
      onSubmit={({ type }) => {
        const dataType = options.find((option) => option.value === type)?.type;
        onSubmit?.(dataType);
      }}
    />
  );
};
