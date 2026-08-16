/** HTTP status codes offered by the status-code picker, with their reason phrases. */
export const HTTP_STATUS_CODES: { code: number; reason: string }[] = [
  { code: 200, reason: "OK" },
  { code: 201, reason: "Created" },
  { code: 202, reason: "Accepted" },
  { code: 204, reason: "No Content" },
  { code: 301, reason: "Moved Permanently" },
  { code: 302, reason: "Found" },
  { code: 304, reason: "Not Modified" },
  { code: 400, reason: "Bad Request" },
  { code: 401, reason: "Unauthorized" },
  { code: 403, reason: "Forbidden" },
  { code: 404, reason: "Not Found" },
  { code: 405, reason: "Method Not Allowed" },
  { code: 406, reason: "Not Acceptable" },
  { code: 408, reason: "Request Timeout" },
  { code: 409, reason: "Conflict" },
  { code: 415, reason: "Unsupported Media Type" },
  { code: 422, reason: "Unprocessable Content" },
  { code: 429, reason: "Too Many Requests" },
  { code: 500, reason: "Internal Server Error" },
  { code: 501, reason: "Not Implemented" },
  { code: 502, reason: "Bad Gateway" },
  { code: 503, reason: "Service Unavailable" },
  { code: 504, reason: "Gateway Timeout" },
];

export const HTTP_STATUS_CODE_OPTIONS = HTTP_STATUS_CODES.map(
  ({ code, reason }) => ({ value: String(code), label: `${code} ${reason}` }),
);
