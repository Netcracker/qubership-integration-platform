export type BodyFormEntry = {
  fieldName?: string;
  mimeType?: string;
  name: string;
  fileName?: string;
  value: string;
};

const toOptionalString = (value: unknown): string | undefined =>
  typeof value === "string" ? value : undefined;

const toString = (value: unknown): string =>
  typeof value === "string" ? value : "";

export const createEmptyBodyFormEntry = (): BodyFormEntry => ({
  name: "",
  value: "",
  fileName: "",
  mimeType: "text/plain",
});

export const toBodyFormEntry = (entry: unknown): BodyFormEntry => {
  if (!entry || typeof entry !== "object") {
    return createEmptyBodyFormEntry();
  }

  const record = entry as Record<string, unknown>;

  return {
    fieldName: toOptionalString(record.fieldName),
    mimeType: toOptionalString(record.mimeType),
    name: toString(record.name),
    fileName: toOptionalString(record.fileName),
    value: toString(record.value),
  };
};

export const toBodyFormData = (value: unknown): BodyFormEntry[] => {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.map(toBodyFormEntry);
};
