/**
 * What an HTTP header field may carry, mirroring the RFC 9110 rules the testing
 * service enforces in `internal/httpfield`. A header the service refuses comes
 * back as a 400 naming no field, so the editors check it before the save.
 */

/** RFC 9110 token: alphanumerics plus these, and never empty. */
const NAME_PATTERN = /^[A-Za-z0-9!#$%&'*+\-.^_`|~]+$/;

const TAB = 9;
const SPACE = 32;
const DELETE = 127;

export function isHttpFieldName(name: string): boolean {
  return NAME_PATTERN.test(name);
}

/** Space and horizontal tab pass; the other control characters break the line. */
export function isHttpFieldValue(value: string): boolean {
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index);
    if (code !== TAB && (code < SPACE || code === DELETE)) {
      return false;
    }
  }
  return true;
}

/** Message naming what is wrong with a header name, or undefined when it holds. */
export function getHttpFieldNameError(name: string): string | undefined {
  if (name.trim().length === 0) {
    return "Enter a header name.";
  }
  return isHttpFieldName(name)
    ? undefined
    : "A header name may carry letters, digits and !#$%&'*+-.^_`|~ only.";
}

/** Message naming what is wrong with a header value, or undefined when it holds. */
export function getHttpFieldValueError(value: string): string | undefined {
  return isHttpFieldValue(value)
    ? undefined
    : "A header value carries no control characters.";
}
