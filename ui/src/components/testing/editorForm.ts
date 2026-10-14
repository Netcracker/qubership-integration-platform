import type { FormProps } from "antd";

/** Width of a field holding a number, so it is not stretched to the column. */
export const NUMBER_FIELD_WIDTH = 160;

/**
 * One centred column with the labels beside their fields, as the chain's
 * Logging and Properties tabs lay their forms out.
 */
export const editorFormLayout: FormProps = {
  labelCol: { flex: "150px" },
  wrapperCol: { flex: "auto" },
  labelWrap: true,
};
