import React, { useCallback, useEffect, useRef, useState } from "react";
import { FieldProps, RJSFSchema } from "@rjsf/utils";
import { FormContext } from "../../ChainElementModificationContext";
import OneOfSelectField from "./OneOfSelectField";
import {
  BEFORE_OPTIONS,
  OneOfOption,
  findBeforeIndexByType,
  inferBeforeType,
} from "./oneof-utils";

/**
 * Service-call `before` (Prepare Request action). Has a oneOf between
 * None / Mapper / Scripting. RJSF's built-in OneOfField can't manage this
 * reliably because the None option uses `anyOf` for its `type` instead of
 * a `const`, which causes `sanitizeDataForNewSchema` to leave stale
 * `mappingDescription` / `script` in place and `componentDidUpdate` to
 * auto-revert the selection via `getClosestMatchingOption`.
 */
const BeforeOneOfField: React.FC<
  FieldProps<Record<string, unknown>, RJSFSchema, FormContext>
> = (props) => {
  const { formData, onChange, fieldPathId, schema } = props;
  const oneOfOptions = schema.oneOf as OneOfOption[];

  const [selectedIndex, setSelectedIndex] = useState(() =>
    findBeforeIndexByType(inferBeforeType(formData)),
  );
  const injectedRef = useRef(false);

  // On first mount, persist the inferred `type` so RJSF's option matcher
  // doesn't snap Action back to None on reload when only mappingDescription
  // (or script) was stored.
  useEffect(() => {
    if (injectedRef.current) return;
    injectedRef.current = true;
    if (!formData || typeof formData !== "object") return;
    const expected = BEFORE_OPTIONS[selectedIndex]?.type;
    if (expected && formData.type !== expected) {
      onChange({ ...formData, type: expected }, fieldPathId?.path);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSwitch = useCallback(
    (newIndex: number) => {
      if (newIndex === selectedIndex) return;
      setSelectedIndex(newIndex);
      const cleaned = { ...formData };
      for (let i = 0; i < BEFORE_OPTIONS.length; i++) {
        if (i === newIndex) continue;
        for (const key of BEFORE_OPTIONS[i].fields) delete cleaned[key];
      }
      const newType = BEFORE_OPTIONS[newIndex]?.type;
      if (newType) cleaned.type = newType;
      else delete cleaned.type;
      onChange(cleaned, fieldPathId?.path);
    },
    [formData, onChange, fieldPathId, selectedIndex],
  );

  return (
    <OneOfSelectField
      {...props}
      options={oneOfOptions}
      selectedIndex={selectedIndex}
      onSwitch={handleSwitch}
    />
  );
};

export default BeforeOneOfField;
