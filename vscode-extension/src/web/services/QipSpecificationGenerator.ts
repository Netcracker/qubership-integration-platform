export class QipSpecificationGenerator {
  // OpenAPI 3.0/3.1 path-item methods. `trace` matches the backend
  // (`PathItem.readOperations()`). `query` is omitted on purpose: it is a
  // 3.2-only method that the backend drops via swagger-parser 2.1.x.
  private static readonly HTTP_METHODS = [
    "get",
    "post",
    "put",
    "delete",
    "patch",
    "head",
    "options",
    "trace",
  ];

  /**
   * Builds QIP operations from an OpenAPI 3.x or Swagger 2.0 document. The
   * caller wraps them into the api-format model file; this factory produces
   * only the operation objects.
   */
  static buildOperations(openApiSpec: any, specificationId: string): any[] {
    // Determine OpenAPI version and convert Swagger 2.0 to OpenAPI 3.0 if needed.
    // Boolean(...), not the bare `&&`: the chain yields `undefined` rather than
    // `false` when the key is absent, and the helpers below take a required boolean.
    const isOpenApi3 = Boolean(openApiSpec.openapi?.startsWith("3."));
    const isSwagger2 = Boolean(openApiSpec.swagger?.startsWith("2."));

    if (!isOpenApi3 && !isSwagger2) {
      throw new Error("Invalid OpenAPI/Swagger specification");
    }

    // Convert Swagger 2.0 to OpenAPI 3.0 for unified processing
    let processedSpec = openApiSpec;
    if (isSwagger2) {
      processedSpec = this.convertSwagger2ToOpenApi3(openApiSpec);
    }

    const operations: any[] = [];

    if (processedSpec.paths) {
      for (const [path, pathItem] of Object.entries(processedSpec.paths)) {
        const pathItemObj = pathItem as any;
        const pathItemParameters = Array.isArray(pathItemObj.parameters)
          ? pathItemObj.parameters
          : [];

        for (const [method, operation] of Object.entries(pathItemObj)) {
          if (this.HTTP_METHODS.includes(method.toLowerCase())) {
            const operationObj = operation as any;
            const qipOperation = this.createQipOperation(
              operationObj,
              method.toUpperCase(),
              path,
              processedSpec,
              specificationId,
              isOpenApi3,
              pathItemParameters,
            );
            operations.push(qipOperation);
          }
        }
      }
    }

    return operations;
  }

  /**
   * Creates QIP operation from OpenAPI operation. `pathItemParameters` are
   * shared across every operation on the path (OpenAPI Path Item Object) —
   * they fold into the specification's `parameters` list, but backend
   * parity keeps them out of `requestSchema.parameters`, which reflects only
   * the operation's own declared parameters.
   */
  private static createQipOperation(
    operation: any,
    method: string,
    path: string,
    openApiSpec: any,
    specificationId: string,
    isOpenApi3: boolean,
    pathItemParameters: any[],
  ): any {
    const operationId =
      operation.operationId || this.generateOperationId(method, path);
    const { requestSchema, responseSchemas } = this.createOperationSchemas(
      operation,
      openApiSpec,
      isOpenApi3,
    );

    return {
      id: `${specificationId}-${operationId}`,
      name: operationId,
      // Lifted out of the raw operation: the backend stores it on the typed
      // operation and exports it, and the api file drops the raw slice.
      summary: operation.summary,
      method: method,
      path: path,
      specification: this.reorderSpecificationFields(
        operation,
        isOpenApi3,
        pathItemParameters,
      ),
      requestSchema,
      responseSchemas,
    };
  }

  /**
   * Produces the request/response schema pair for a single raw OpenAPI/Swagger
   * operation node, with every `$ref` expanded inline.
   */
  private static createOperationSchemas(
    operation: any,
    openApiSpec: any,
    isOpenApi3: boolean,
  ): { requestSchema: any; responseSchemas: any } {
    return {
      requestSchema: this.createRequestSchema(
        operation,
        openApiSpec,
        isOpenApi3,
      ),
      responseSchemas: this.createResponseSchemas(operation, openApiSpec),
    };
  }

  /**
   * Reorders fields in specification object according to backend order
   */
  private static reorderSpecificationFields(
    operation: any,
    isOpenApi3: boolean,
    pathItemParameters: any[],
  ): any {
    const orderedSpec: any = {};

    // Field order as in backend
    const fieldOrder = [
      "tags",
      "summary",
      "security",
      "responses",
      "operationId",
      "requestBody",
      "x-codegen-request-body-name",
      "description",
      "parameters",
      "deprecated",
    ];

    // Add fields in correct order
    for (const field of fieldOrder) {
      if (field === "parameters") {
        // Parameters combine the operation's own list with any parameters
        // shared at the path-item level — checked separately since a path
        // item can be the only source (empty own list is still `undefined`).
        const merged = [
          ...(Array.isArray(operation.parameters) ? operation.parameters : []),
          ...pathItemParameters,
        ];
        if (merged.length > 0) {
          orderedSpec.parameters = this.processParametersForSpecification(
            merged,
            isOpenApi3,
          );
        }
      } else if (field === "responses") {
        if (operation.responses !== undefined) {
          orderedSpec.responses = this.processResponsesForSpecification(
            operation.responses,
          );
        }
      } else if (field === "requestBody") {
        if (operation.requestBody !== undefined) {
          orderedSpec.requestBody = this.processRequestBodyForSpecification(
            operation.requestBody,
          );
        }
      } else if (operation[field] !== undefined) {
        orderedSpec[field] = operation[field];
      }
    }

    // Add remaining fields not in the list
    for (const key in operation) {
      if (!fieldOrder.includes(key) && operation[key] !== undefined) {
        orderedSpec[key] = operation[key];
      }
    }

    // Backend parity: the Java schema model sorts every `required` array
    // (a Set under the hood) and always types a `propertyNames` schema as
    // "string" — applied once, over the whole specification slice, rather
    // than threading it through every nested field individually.
    return this.normalizeSchemaLikeNode(orderedSpec);
  }

  /**
   * Defaults `style` (and, for headers, `explode`) on an OpenAPI Encoding
   * Object and its nested header map — mirrors the backend's Encoding/Header
   * object model, which always carries these even when the source omits
   * them.
   */
  private static processRequestBodyForSpecification(requestBody: any): any {
    if (
      !requestBody ||
      typeof requestBody !== "object" ||
      typeof requestBody.content !== "object"
    ) {
      return requestBody;
    }

    const content: Record<string, any> = {};
    for (const [mediaType, mediaObj] of Object.entries(
      requestBody.content as Record<string, any>,
    )) {
      if (mediaObj && typeof mediaObj === "object" && mediaObj.encoding) {
        content[mediaType] = {
          ...mediaObj,
          encoding: this.processEncodingForSpecification(mediaObj.encoding),
        };
      } else {
        content[mediaType] = mediaObj;
      }
    }

    return { ...requestBody, content };
  }

  private static processEncodingForSpecification(encoding: any): any {
    const result: Record<string, any> = {};
    for (const [name, enc] of Object.entries(encoding as Record<string, any>)) {
      const encObj = enc as Record<string, any>;
      result[name] = {
        ...encObj,
        style: encObj.style ?? "form",
        ...(encObj.headers
          ? { headers: this.processHeadersForSpecification(encObj.headers) }
          : {}),
      };
    }
    return result;
  }

  /**
   * Recursively sorts every `required` string array and types every
   * `propertyNames` schema as `"string"` when absent. Mirrors artifacts of
   * the backend's typed Schema object model (a `Set<String>` for required
   * fields; `propertyNames` is inherently string-keyed) that a raw
   * parse/passthrough would otherwise never reproduce.
   */
  // Code-unit order, matching the backend's natural String ordering. localeCompare would
  // order by the runtime's locale and break parity on some machines.
  private static compareByCodeUnit(a: string, b: string): number {
    if (a === b) {
      return 0;
    }
    return a < b ? -1 : 1;
  }

  private static normalizeSchemaLikeNode(node: any): any {
    if (Array.isArray(node)) {
      return node.map((item) => this.normalizeSchemaLikeNode(item));
    }
    if (!node || typeof node !== "object") {
      return node;
    }

    const result: Record<string, any> = {};
    for (const [key, value] of Object.entries(node)) {
      if (
        key === "required" &&
        Array.isArray(value) &&
        value.every((item): item is string => typeof item === "string")
      ) {
        result[key] = [...value].sort(this.compareByCodeUnit);
      } else {
        result[key] = this.normalizeSchemaLikeNode(value);
      }
    }

    // Backend parity: a schema fragment with a string enum but no declared
    // type infers "string" — covers `propertyNames` and any ad hoc
    // enum-only fragment (e.g. under `dependentSchemas`).
    if (
      result.type === undefined &&
      Array.isArray(result.enum) &&
      result.enum.length > 0 &&
      result.enum.every((item: any) => typeof item === "string")
    ) {
      result.type = "string";
    }

    return result;
  }

  /**
   * Processes parameters for specification object - wraps in schema.
   * A `$ref` parameter is left untouched (backend parity: parameter refs at
   * the operation level stay unresolved in the specification slice).
   */
  private static processParametersForSpecification(
    parameters: any[],
    isOpenApi3: boolean,
  ): any[] {
    if (!parameters || !Array.isArray(parameters)) {
      return parameters;
    }

    return parameters.map((param: any) =>
      this.isRefObject(param)
        ? param
        : this.processParameter(param, isOpenApi3),
    );
  }

  private static isRefObject(value: any): boolean {
    return (
      !!value && typeof value === "object" && typeof value.$ref === "string"
    );
  }

  // OpenAPI 3.x default `style`/`explode` per parameter location (spec-mandated).
  private static defaultParameterStyle(paramIn: string): string {
    return paramIn === "query" || paramIn === "cookie" ? "form" : "simple";
  }

  private static readonly PARAMETER_PASSTHROUGH_FIELDS = [
    "description",
    "deprecated",
    "allowEmptyValue",
    "example",
    "examples",
  ];

  /**
   * Processes single parameter. `required`/`style`/`explode` are only
   * defaulted for genuine OpenAPI 3.x sources — the real Parameter object
   * model always carries them, while Swagger 2.0 (no such concept) is
   * converted upstream and must not pick up 3.x-only fields it never had.
   */
  private static processParameter(param: any, isOpenApi3: boolean): any {
    const paramObj: any = {
      in: param.in,
      name: param.name,
    };

    for (const field of this.PARAMETER_PASSTHROUGH_FIELDS) {
      if (param[field] !== undefined) {
        paramObj[field] = param[field];
      }
    }

    // If there's a schema, use it, otherwise create from type/format
    if (param.schema) {
      paramObj.schema = param.schema;
    } else if (param.type) {
      paramObj.schema = this.createSchemaFromType(param);
    } else {
      paramObj.schema = {};
    }

    if (isOpenApi3) {
      const style = param.style ?? this.defaultParameterStyle(param.in);
      paramObj.required = param.required ?? false;
      paramObj.style = style;
      paramObj.explode = param.explode ?? style === "form";
    } else {
      paramObj.required = param.required;
    }

    return paramObj;
  }

  /**
   * Creates schema from type and format
   */
  private static createSchemaFromType(param: any): any {
    const schema: any = {
      type: param.type,
      format: param.format,
    };

    // Add additional properties to schema if they exist
    const additionalProps = [
      "minimum",
      "maximum",
      "minLength",
      "maxLength",
      "pattern",
      "enum",
    ];
    for (const prop of additionalProps) {
      if (param[prop] !== undefined) {
        schema[prop] = param[prop];
      }
    }

    return schema;
  }

  /**
   * Creates request schema
   */
  private static createRequestSchema(
    operation: any,
    openApiSpec: any,
    isOpenApi3: boolean,
  ): any {
    const requestSchema: any = {};

    // Handle parameters: the operation's own list only — path-item-inherited
    // parameters never appear here, even though they do in the
    // specification slice. A `$ref` parameter passes through unresolved,
    // same as in the specification.
    if (operation.parameters && operation.parameters.length > 0) {
      requestSchema.parameters = operation.parameters.map((param: any) =>
        this.isRefObject(param)
          ? param
          : this.processParameter(param, isOpenApi3),
      );
    }

    // Handle requestBody (OpenAPI 3.0)
    if (operation.requestBody && operation.requestBody.content) {
      // Sort content types for consistent order
      const sortedContentTypes = Object.keys(
        operation.requestBody.content,
      ).sort();

      for (const contentType of sortedContentTypes) {
        const content = operation.requestBody.content[contentType] as any;
        requestSchema[contentType] = this.buildContentSchema(
          contentType,
          content,
          openApiSpec,
        );
      }
    }

    return requestSchema;
  }

  /**
   * Full `$ref` expansion only fires when the schema reduces to a reference
   * to a single named component — a bare `{ $ref }` (any content type,
   * including e.g. `application/xml`), or an array wrapping one
   * (`{ type: "array", items: { $ref } }`). An inline/anonymous schema with
   * its own `properties` — even one that nests a `$ref` several levels down
   * — is left exactly as declared, unresolved, matching the backend: it
   * only walks a document far enough to answer "is this whole body just a
   * pointer to one component?" A content entry with no `schema` still gets
   * a placeholder `{}` so the media-type key itself is preserved.
   */
  private static buildContentSchema(
    contentType: string,
    content: any,
    openApiSpec: any,
  ): any {
    if (!content?.schema) {
      return {};
    }
    if (this.isExpandableSchemaRoot(content.schema)) {
      return this.normalizeSchemaLikeNode(
        this.expandSchema(content.schema, openApiSpec),
      );
    }
    // Even a schema left otherwise untouched still gets the same
    // required-sort/enum-type normalization (backend parity — see
    // normalizeSchemaLikeNode).
    return this.normalizeSchemaLikeNode(
      JSON.parse(JSON.stringify(content.schema)),
    );
  }

  private static isBareRef(node: any): boolean {
    return (
      !!node &&
      typeof node === "object" &&
      !Array.isArray(node) &&
      typeof node.$ref === "string" &&
      Object.keys(node).length === 1
    );
  }

  private static isExpandableSchemaRoot(schema: any): boolean {
    if (this.isBareRef(schema)) {
      return true;
    }
    return (
      !!schema &&
      typeof schema === "object" &&
      schema.type === "array" &&
      this.isBareRef(schema.items)
    );
  }

  /**
   * Creates response schemas
   */
  private static createResponseSchemas(operation: any, openApiSpec: any): any {
    const responseSchemas: any = {};

    if (operation.responses) {
      // Sort status codes for consistent order
      const sortedStatusCodes = Object.keys(operation.responses).sort(
        (a, b) => {
          // First numeric codes, then default
          if (a === "default") {
            return 1;
          }
          if (b === "default") {
            return -1;
          }
          return parseInt(a) - parseInt(b);
        },
      );

      for (const statusCode of sortedStatusCodes) {
        const response = operation.responses[statusCode] as any;
        responseSchemas[statusCode] = {};

        if (response.content) {
          // Sort content types for consistent order
          const sortedContentTypes = Object.keys(response.content).sort();

          for (const contentType of sortedContentTypes) {
            const content = response.content[contentType] as any;
            responseSchemas[statusCode][contentType] = this.buildContentSchema(
              contentType,
              content,
              openApiSpec,
            );
          }
        }
      }
    }

    return responseSchemas;
  }

  /**
   * Processes the operation's raw `responses` map for the specification
   * slice: defaults each header's `style`/`explode` the way the backend's
   * OpenAPI 3.x Header object model does (default style "simple", explode
   * true only for style "form"), and drops `summary` — an OpenAPI 3.2-only
   * Response Object field the backend's 3.1-based model has no slot for, so
   * it never survives a real parse/reserialize round trip. Response-level
   * `$ref`s and content/schema `$ref`s stay untouched.
   */
  private static processResponsesForSpecification(responses: any): any {
    if (!responses || typeof responses !== "object") {
      return responses;
    }

    const result: any = {};
    for (const [status, response] of Object.entries(responses)) {
      if (
        this.isRefObject(response) ||
        !response ||
        typeof response !== "object"
      ) {
        result[status] = response;
        continue;
      }
      const { summary, ...responseObj } = response as Record<string, any>;
      result[status] = responseObj.headers
        ? {
            ...responseObj,
            headers: this.processHeadersForSpecification(responseObj.headers),
          }
        : responseObj;
    }
    return result;
  }

  private static processHeadersForSpecification(headers: any): any {
    const result: any = {};
    for (const [name, header] of Object.entries(
      headers as Record<string, any>,
    )) {
      if (this.isRefObject(header)) {
        result[name] = header;
        continue;
      }
      const headerObj = header as Record<string, any>;
      const style = headerObj.style ?? "simple";
      result[name] = {
        ...headerObj,
        style,
        explode: headerObj.explode ?? style === "form",
      };
    }
    return result;
  }

  /**
   * Expands schema, resolving references and creating full JSON Schema
   * while collecting referenced definitions for backward compatibility.
   */
  private static expandSchema(
    schema: any,
    openApiSpec: any,
    schemaName?: string,
    visited: Set<string> = new Set(),
    definitions: Record<string, any> = {},
  ): any {
    if (!schema) {
      return {};
    }

    if (schema.$ref) {
      const resolvedSchema = this.resolveRef(schema.$ref, openApiSpec);
      const refSchemaName = this.extractSchemaNameFromRef(schema.$ref);
      const newVisited = new Set(visited);
      newVisited.add(schema.$ref);
      return this.expandSchema(
        resolvedSchema,
        openApiSpec,
        refSchemaName,
        newVisited,
        definitions,
      );
    }

    if (schema.type === "array" && this.isBareRef(schema.items)) {
      return this.expandArrayOfBareRef(
        schema,
        openApiSpec,
        visited,
        definitions,
      );
    }

    const expandedSchema = this.expandSchemaInternal(
      schema,
      openApiSpec,
      schemaName,
      new Set(visited),
      definitions,
      true,
    );

    expandedSchema.definitions =
      Object.keys(definitions).length > 0 ? definitions : {};

    return expandedSchema;
  }

  /**
   * Backend parity for `{ type: "array", items: { $ref } }`: the referenced
   * schema's own body is inlined directly under `items` (not left as a
   * `$ref`/`definitions` pointer), `$id` names the item schema rather than
   * the array, and the item's own nested refs are hoisted into the shared
   * top-level `definitions` map. A schema whose body has no own `type`
   * (e.g. a bare `allOf` composition) gets an explicit `type: null` — the
   * one artifact of the backend's typed Schema model that shows up only in
   * this inlined-item position.
   */
  private static expandArrayOfBareRef(
    schema: any,
    openApiSpec: any,
    visited: Set<string>,
    definitions: Record<string, any>,
  ): any {
    const itemRef = schema.items.$ref as string;
    const itemSchemaName = this.extractSchemaNameFromRef(itemRef);
    const resolvedItemSchema = this.resolveRef(itemRef, openApiSpec);
    const newVisited = new Set(visited);
    newVisited.add(itemRef);

    const expandedItem = this.expandSchemaInternal(
      resolvedItemSchema,
      openApiSpec,
      itemSchemaName,
      newVisited,
      definitions,
      false,
    );
    if (expandedItem.type === undefined) {
      expandedItem.type = null;
    }

    return {
      ...schema,
      $id: `http://system.catalog/schemas/#/components/schemas/${itemSchemaName || "Schema"}`,
      $schema: "http://json-schema.org/draft-07/schema#",
      items: expandedItem,
      definitions,
    };
  }

  private static expandSchemaInternal(
    schema: any,
    openApiSpec: any,
    schemaName: string | undefined,
    visited: Set<string>,
    definitions: Record<string, any>,
    isRoot: boolean,
  ): any {
    if (!schema) {
      return {};
    }

    if (schema.$ref) {
      return this.convertRefToDefinition(
        schema.$ref,
        openApiSpec,
        visited,
        definitions,
      );
    }

    const expanded: any = { ...schema };

    if (isRoot) {
      expanded.$id = `http://system.catalog/schemas/#/components/schemas/${schemaName || schema.title || "Schema"}`;
      expanded.$schema = "http://json-schema.org/draft-07/schema#";
    }

    if (expanded.required && Array.isArray(expanded.required)) {
      // Sorted for backend parity by normalizeSchemaLikeNode, once, at the
      // top of expandSchema's two external call sites.
      expanded.required = expanded.required.map((item: any) =>
        typeof item === "string" ? `${item}` : item,
      );
    }

    if (schema.properties) {
      expanded.properties = {};
      for (const [key, prop] of Object.entries(schema.properties)) {
        expanded.properties[key] = this.expandProperty(
          prop,
          openApiSpec,
          visited,
          definitions,
        );
      }
    }

    if (schema.items) {
      expanded.items = this.expandProperty(
        schema.items,
        openApiSpec,
        visited,
        definitions,
      );
    }

    if (schema.allOf) {
      expanded.allOf = schema.allOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (schema.anyOf) {
      expanded.anyOf = schema.anyOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (schema.oneOf) {
      expanded.oneOf = schema.oneOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (schema.additionalProperties !== undefined) {
      expanded.additionalProperties = this.expandProperty(
        schema.additionalProperties,
        openApiSpec,
        visited,
        definitions,
      );
    }

    return expanded;
  }

  private static expandProperty(
    prop: any,
    openApiSpec: any,
    visited: Set<string>,
    definitions: Record<string, any>,
  ): any {
    if (!prop || typeof prop !== "object") {
      return prop;
    }

    if (prop.$ref) {
      return this.convertRefToDefinition(
        prop.$ref,
        openApiSpec,
        visited,
        definitions,
      );
    }

    const expanded: any = { ...prop };

    if (prop.properties) {
      expanded.properties = {};
      for (const [key, value] of Object.entries(prop.properties)) {
        expanded.properties[key] = this.expandProperty(
          value,
          openApiSpec,
          visited,
          definitions,
        );
      }
    }

    if (prop.items) {
      expanded.items = this.expandProperty(
        prop.items,
        openApiSpec,
        visited,
        definitions,
      );
    }

    if (prop.allOf) {
      expanded.allOf = prop.allOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (prop.anyOf) {
      expanded.anyOf = prop.anyOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (prop.oneOf) {
      expanded.oneOf = prop.oneOf.map((item: any) =>
        this.expandProperty(item, openApiSpec, visited, definitions),
      );
    }

    if (prop.additionalProperties !== undefined) {
      expanded.additionalProperties = this.expandProperty(
        prop.additionalProperties,
        openApiSpec,
        visited,
        definitions,
      );
    }

    return expanded;
  }

  private static convertRefToDefinition(
    ref: string,
    openApiSpec: any,
    visited: Set<string>,
    definitions: Record<string, any>,
  ): { $ref: string } {
    const schemaName = this.extractSchemaNameFromRef(ref);
    if (!schemaName) {
      return { $ref: ref };
    }

    if (!definitions[schemaName]) {
      if (visited.has(ref)) {
        return { $ref: `#/definitions/${schemaName}` };
      }

      const newVisited = new Set(visited);
      newVisited.add(ref);

      const isDefinitionRef = ref.startsWith("#/definitions/");
      const resolutionRef = isDefinitionRef
        ? `#/components/schemas/${schemaName}`
        : ref;
      const resolvedSchema = this.resolveRef(resolutionRef, openApiSpec);

      if (!resolvedSchema || Object.keys(resolvedSchema).length === 0) {
        return { $ref: `#/definitions/${schemaName}` };
      }

      definitions[schemaName] = this.expandSchemaInternal(
        resolvedSchema,
        openApiSpec,
        schemaName,
        newVisited,
        definitions,
        false,
      );
    }

    return { $ref: `#/definitions/${schemaName}` };
  }

  /**
   * Extracts schema name from reference
   */
  private static extractSchemaNameFromRef(ref: string): string | undefined {
    if (!ref.startsWith("#/")) {
      return undefined;
    }

    const path = ref.substring(2).split("/");
    // For components/schemas/SchemaName, return SchemaName
    if (path.length >= 3 && path[0] === "components" && path[1] === "schemas") {
      return path[2];
    }

    if (path.length >= 2 && path[0] === "definitions") {
      return path[1];
    }

    return undefined;
  }

  /**
   * Resolves schema reference
   */
  private static resolveRef(ref: string, openApiSpec: any): any {
    if (!ref.startsWith("#/")) {
      return {};
    }

    const path = ref.substring(2).split("/");
    let current = openApiSpec;

    for (const segment of path) {
      if (current && typeof current === "object" && segment in current) {
        current = current[segment];
      } else {
        return {};
      }
    }

    return current || {};
  }

  /**
   * Generates operation ID
   */
  private static generateOperationId(method: string, path: string): string {
    const pathParts = path
      .split("/")
      .filter((part) => part && !part.startsWith("{"));
    const operationName =
      pathParts.length > 0 ? pathParts[pathParts.length - 1] : "operation";
    return `${method.toLowerCase()}${operationName.charAt(0).toUpperCase()}${operationName.slice(1)}`;
  }

  /**
   * Converts Swagger 2.0 to OpenAPI 3.0
   */
  private static convertSwagger2ToOpenApi3(swagger2Spec: any): any {
    const openApi3Spec = {
      openapi: "3.0.0",
      info: swagger2Spec.info || {},
      servers: this.createServersFromSwagger2(swagger2Spec),
      paths: {},
      components: {
        schemas: {},
        securitySchemes: this.convertSecurityDefinitions(
          swagger2Spec.securityDefinitions,
        ),
      },
    };

    // Convert paths and operations
    if (swagger2Spec.paths) {
      for (const [path, pathItem] of Object.entries(swagger2Spec.paths)) {
        const openApiPathItem: any = {};

        for (const [method, operation] of Object.entries(pathItem as any)) {
          if (this.HTTP_METHODS.includes(method.toLowerCase())) {
            openApiPathItem[method] = this.convertSwagger2Operation(
              operation as any,
              swagger2Spec,
            );
          }
        }

        (openApi3Spec.paths as any)[path] = openApiPathItem;
      }
    }

    // Convert definitions to components/schemas
    if (swagger2Spec.definitions) {
      for (const [name, definition] of Object.entries(
        swagger2Spec.definitions,
      )) {
        const def = JSON.parse(JSON.stringify(definition)); // Deep copy
        this.convertRefsInSchema(def);
        (openApi3Spec.components.schemas as any)[name] = def;
      }
    }

    return openApi3Spec;
  }

  /**
   * Creates servers array from Swagger 2.0 host/schemes/basePath
   */
  private static createServersFromSwagger2(swagger2Spec: any): any[] {
    if (!swagger2Spec.host) {
      return [];
    }

    const scheme = swagger2Spec.schemes?.[0] || "https";
    const basePath = swagger2Spec.basePath || "";
    const url = `${scheme}://${swagger2Spec.host}${basePath}`;

    return [{ url }];
  }

  /**
   * Converts Swagger 2.0 operation to OpenAPI 3.0
   */
  private static convertSwagger2Operation(
    operation: any,
    swagger2Spec: any,
  ): any {
    const openApiOperation: any = {
      ...operation,
      responses: {},
    };

    // Convert responses
    if (operation.responses) {
      for (const [statusCode, response] of Object.entries(
        operation.responses,
      )) {
        const openApiResponse: any = {
          description: (response as any).description || "",
        };

        if ((response as any).schema) {
          const schema = (response as any).schema;
          // Convert #/definitions to #/components/schemas for OpenAPI 3.0,
          // including refs nested inside an array/object wrapper (not just
          // a bare top-level $ref).
          this.convertRefsInSchema(schema);
          openApiResponse.content = {
            "application/json": {
              schema: schema,
            },
          };
        }

        openApiOperation.responses[statusCode] = openApiResponse;
      }
    }

    // Convert parameters to requestBody
    if (operation.parameters) {
      this.convertParametersToRequestBody(
        operation,
        openApiOperation,
        swagger2Spec,
      );
    }

    return openApiOperation;
  }

  /**
   * Converts Swagger 2.0 parameters to OpenAPI 3.0 requestBody
   */
  private static convertParametersToRequestBody(
    operation: any,
    openApiOperation: any,
    swagger2Spec: any,
  ): void {
    const bodyParams = operation.parameters.filter((p: any) => p.in === "body");
    const formParams = operation.parameters.filter(
      (p: any) => p.in === "formData",
    );
    const nonBodyParams = operation.parameters.filter(
      (p: any) => p.in !== "body" && p.in !== "formData",
    );

    if (bodyParams.length > 0) {
      const consumes = operation.consumes ||
        swagger2Spec.consumes || ["application/json"];
      this.convertBodyParameters(bodyParams, openApiOperation, consumes);
    } else if (formParams.length > 0) {
      this.convertFormParameters(formParams, openApiOperation);
    }

    // Keep only non-body parameters; omit the field entirely when there
    // are none, rather than leaving an empty array on the operation.
    if (nonBodyParams.length > 0) {
      openApiOperation.parameters = nonBodyParams;
    } else {
      delete openApiOperation.parameters;
    }
  }

  /**
   * Converts body parameters to requestBody
   */
  private static convertBodyParameters(
    bodyParams: any[],
    openApiOperation: any,
    consumes: string[],
  ): void {
    const bodySchema = bodyParams[0].schema;
    // Convert #/definitions to #/components/schemas for OpenAPI 3.0
    if (
      bodySchema &&
      bodySchema.$ref &&
      bodySchema.$ref.startsWith("#/definitions/")
    ) {
      bodySchema.$ref = bodySchema.$ref.replace(
        "#/definitions/",
        "#/components/schemas/",
      );
    }

    const content: Record<string, any> = {};
    for (const mediaType of consumes) {
      content[mediaType] = { schema: bodySchema };
    }

    openApiOperation.requestBody = {
      content,
      required: bodyParams[0].required || false,
      ...(bodyParams[0].description
        ? { description: bodyParams[0].description }
        : {}),
    };

    // Add x-codegen-request-body-name
    openApiOperation["x-codegen-request-body-name"] = "body";
  }

  /**
   * Converts form parameters to requestBody
   */
  private static convertFormParameters(
    formParams: any[],
    openApiOperation: any,
  ): void {
    const formSchema = {
      type: "object",
      properties: {},
      required: [],
    };

    formParams.forEach((param: any) => {
      const propSchema: any = {
        type: param.type === "file" ? "string" : param.type,
        description: param.description,
      };

      // Add format for file types
      if (param.type === "file") {
        propSchema.format = "binary";
      } else if (param.format) {
        propSchema.format = param.format;
      }

      // Handle array types
      if (param.type === "array") {
        propSchema.type = "array";
        propSchema.items = {
          type: param.items?.type || "string",
        };
        if (param.collectionFormat) {
          propSchema.collectionFormat = param.collectionFormat;
        }
      }

      // Handle enum values
      if (param.enum && param.enum.length > 0) {
        propSchema.enum = param.enum;
      }

      // Handle minimum/maximum values
      if (param.minimum !== undefined) {
        propSchema.minimum = param.minimum;
      }
      if (param.maximum !== undefined) {
        propSchema.maximum = param.maximum;
      }

      (formSchema.properties as any)[param.name] = propSchema;

      if (param.required) {
        (formSchema.required as any[]).push(param.name);
      }
    });

    // Determine content type based on file parameters presence
    const hasFileParams = formParams.some(
      (param: any) => param.type === "file",
    );
    const contentType = hasFileParams
      ? "multipart/form-data"
      : "application/x-www-form-urlencoded";

    // Remove empty required array
    if (formSchema.required.length === 0) {
      delete (formSchema as any).required;
    }

    openApiOperation.requestBody = {
      content: {
        [contentType]: {
          schema: formSchema,
        },
      },
      required: formParams.some((param: any) => param.required),
      description: "Form data",
    };
  }

  /**
   * Converts $ref links in schema
   */
  private static convertRefsInSchema(schema: any): void {
    if (!schema || typeof schema !== "object") {
      return;
    }

    // Convert #/definitions to #/components/schemas for OpenAPI 3.0
    if (schema.$ref && schema.$ref.startsWith("#/definitions/")) {
      schema.$ref = schema.$ref.replace(
        "#/definitions/",
        "#/components/schemas/",
      );
    }

    // Convert #/parameters to #/components/parameters for OpenAPI 3.0
    if (schema.$ref && schema.$ref.startsWith("#/parameters/")) {
      schema.$ref = schema.$ref.replace(
        "#/parameters/",
        "#/components/parameters/",
      );
    }

    // Convert #/responses to #/components/responses for OpenAPI 3.0
    if (schema.$ref && schema.$ref.startsWith("#/responses/")) {
      schema.$ref = schema.$ref.replace(
        "#/responses/",
        "#/components/responses/",
      );
    }

    // Recursively process nested objects
    for (const key in schema) {
      if (schema[key] && typeof schema[key] === "object") {
        this.convertRefsInSchema(schema[key]);
      }
    }
  }

  /**
   * Converts security definitions
   */
  private static convertSecurityDefinitions(securityDefinitions: any): any {
    if (!securityDefinitions) {
      return {};
    }

    const securitySchemes: any = {};
    for (const [name, scheme] of Object.entries(securityDefinitions)) {
      const schemeObj = scheme as any;
      securitySchemes[name] = {
        type: schemeObj.type,
        scheme: schemeObj.scheme,
        bearerFormat: schemeObj.bearerFormat,
        name: schemeObj.name,
        in: schemeObj.in,
      };
    }
    return securitySchemes;
  }
}
