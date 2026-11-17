/**
 * The error a failed request posts to the webview. `responseBody` has the shape a REST error
 * carries, so the webview shows the same details for both.
 */
export type ErrorPayload = {
  message: string;
  name: string;
  responseBody: {
    serviceName: string;
    errorMessage: string;
    stackTrace: string;
    errorDate: string;
  };
};

const SERVICE_NAME = "CIP Extension";

export function toErrorPayload(error: Error): ErrorPayload {
  return {
    message: error.message,
    name: error.name,
    responseBody: {
      serviceName: SERVICE_NAME,
      errorMessage: error.message,
      stackTrace: error.stack ?? "",
      errorDate: new Date().toISOString(),
    },
  };
}
