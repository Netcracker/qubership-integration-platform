import { Form, Tooltip } from "antd";
import { QuestionCircleOutlined } from "@ant-design/icons";
import {
  type FieldTemplateProps,
  type RJSFSchema,
  getTemplate,
  getUiOptions,
} from "@rjsf/utils";
import styles from "./ChainElementModification.module.css";
import type { ColProps } from "antd";
import React from "react";

interface FieldFormContext {
  colon?: boolean;
  labelCol?: ColProps;
  wrapperCol?: ColProps;
  wrapperStyle?: React.CSSProperties;
}

const VERTICAL_LABEL_COL = { span: 24 };
const VERTICAL_WRAPPER_COL = { span: 24 };

const tooltipIconStyle: React.CSSProperties = {
  color: "var(--vscode-descriptionForeground, #9ca3af)",
  cursor: "help",
  fontSize: 14,
  verticalAlign: "-0.125em",
};

const tooltipStyles = {
  root: { maxWidth: 350 } as React.CSSProperties,
};

const tooltipClassNames = {
  root: styles["description-tooltip"],
};

/** For oneOf/anyOf selector fields, find the description of the currently selected option. */
function getSelectedOneOfDescription(
  schema: RJSFSchema,
  formData: unknown,
): string {
  const options = schema.oneOf ?? schema.anyOf;
  if (!Array.isArray(options) || !formData || typeof formData !== "object") {
    return "";
  }
  const data = formData as Record<string, unknown>;
  for (const option of options) {
    if (
      typeof option === "boolean" ||
      !option.description ||
      !option.properties
    )
      continue;
    const match = Object.entries(
      option.properties as Record<string, RJSFSchema>,
    ).some(
      ([key, prop]) => prop.const !== undefined && data[key] === prop.const,
    );
    if (match) return option.description;
  }
  return "";
}

export function DescriptionTooltipIcon({
  description,
  style,
}: {
  description: string;
  style?: React.CSSProperties;
}) {
  return (
    <Tooltip
      title={description}
      styles={tooltipStyles}
      classNames={tooltipClassNames}
    >
      <QuestionCircleOutlined
        style={style ? { ...tooltipIconStyle, ...style } : tooltipIconStyle}
      />
    </Tooltip>
  );
}

export default function DescriptionTooltipFieldTemplate(
  props: FieldTemplateProps,
) {
  const {
    children,
    displayLabel,
    errors,
    help,
    rawHelp,
    hidden,
    id,
    label,
    rawErrors,
    rawDescription,
    registry,
    required,
    schema,
    uiSchema,
  } = props;

  const {
    colon,
    labelCol = VERTICAL_LABEL_COL,
    wrapperCol = VERTICAL_WRAPPER_COL,
    wrapperStyle,
  } = registry.formContext as FieldFormContext;

  const uiOptions = getUiOptions(uiSchema);
  const WrapIfAdditionalTemplate = getTemplate(
    "WrapIfAdditionalTemplate",
    registry,
    uiOptions,
  );

  if (hidden) {
    return <div className="rjsf-field-hidden">{children}</div>;
  }

  const isCheckbox = uiOptions.widget === "checkbox";
  const isBooleanType = schema.type === "boolean";

  const description =
    rawDescription || getSelectedOneOfDescription(schema, props.formData);

  const fieldLabel =
    displayLabel && !isCheckbox && label ? (
      <span>
        {label}
        {description && (
          <DescriptionTooltipIcon
            description={description}
            style={{ marginLeft: 6 }}
          />
        )}
      </span>
    ) : undefined;

  const renderInlineTooltip = !!description && !fieldLabel && isBooleanType;

  const isStructuralType = schema.type === "object" || schema.type === "array";
  const renderBlockTooltip =
    !!description && !fieldLabel && !isBooleanType && !isStructuralType;

  const renderChildren = () => {
    if (renderInlineTooltip) {
      return (
        <span>
          {children}
          <DescriptionTooltipIcon description={description} />
        </span>
      );
    }
    if (renderBlockTooltip) {
      return (
        <div className={styles["description-tooltip-block"]}>
          {children}
          <DescriptionTooltipIcon description={description} />
        </div>
      );
    }
    return children;
  };

  return (
    <WrapIfAdditionalTemplate {...props}>
      <Form.Item
        colon={colon}
        hasFeedback={schema.type !== "array" && schema.type !== "object"}
        help={(!!rawHelp && help) || (rawErrors?.length ? errors : undefined)}
        htmlFor={id}
        label={fieldLabel}
        labelCol={labelCol}
        required={required}
        style={wrapperStyle}
        validateStatus={rawErrors?.length ? "error" : undefined}
        wrapperCol={wrapperCol}
      >
        {renderChildren()}
      </Form.Item>
    </WrapIfAdditionalTemplate>
  );
}
