type OperationPathProps = {
  path: string;
  pathParams?: Record<string, string>;
  queryParams?: Record<string, string>;
};

export const OperationPath: React.FC<OperationPathProps> = ({
  path,
  pathParams,
  queryParams,
}) => {
  const replacePathParams = (): string => {
    let result = path;
    if (pathParams && Object.keys(pathParams).length > 0) {
      Object.entries(pathParams).forEach(([name, value]) => {
        result = result.replace(`{${name}}`, value);
      });
    }

    return result;
  };

  const buildQueryParamString = (): string | undefined => {
    if (!queryParams || Object.keys(queryParams).length === 0) {
      return undefined;
    }

    const query: string = Object.entries(queryParams)
      .filter(([name, value]) => !!name && !!value)
      .map(([name, value]) => `${name}=${value}`)
      .join("&");

    return query ? "?" + query : undefined;
  };

  return replacePathParams() + (buildQueryParamString() ?? "");
};
