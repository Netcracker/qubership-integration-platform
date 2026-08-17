/**
 * Form layout the testing editors share with the chain's other settings tabs
 * (see `pages/LoggingSettings.tsx`): the label sits in a fixed column and the
 * control takes the rest of the row, so fields line up with the tables beside
 * them instead of stopping short in a column of their own.
 */
export const EDITOR_FORM_LAYOUT = {
  labelCol: { flex: "150px" },
  wrapperCol: { flex: "auto" },
  labelAlign: "left" as const,
  labelWrap: true,
};

/**
 * Width for a control that holds a short value — a method, a status, a
 * duration. Left to fill the row it would stretch across the whole page.
 */
export const SHORT_CONTROL_WIDTH = 320;
