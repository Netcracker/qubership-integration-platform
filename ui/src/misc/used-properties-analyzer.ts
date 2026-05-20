import {
  UsedProperty,
  UsedPropertySource,
  UsedPropertyType,
  UsedPropertyElementOperation,
  UsedPropertyElement,
} from "../api/apiTypes.ts";

export interface AnalyzableElement {
  id: string;
  name: string;
  type: string;
  properties?: Record<string, unknown>;
}

const SCRIPT = "script";
const SERVICE_CALL = "service-call";
const HTTP_TRIGGER = "http-trigger";
const MAPPER_2 = "mapper-2";
const MAPPER = "mapper";
const HEADER_MODIFICATION = "header-modification";

const ELEMENTS_WITH_SCRIPT = new Set([SCRIPT, SERVICE_CALL, HTTP_TRIGGER]);
const ELEMENTS_WITH_MAPPER = new Set([MAPPER_2, SERVICE_CALL, HTTP_TRIGGER]);
const EXCLUDE_MAPPER_ELEMENTS = new Set([
  MAPPER,
  MAPPER_2,
  SERVICE_CALL,
  HTTP_TRIGGER,
]);
const MAPPING_DESCRIPTION = "mappingDescription";

// GET
const GROOVY_GET_HEADERS_PATTERN =
  /exchange\.(message|getMessage\(\))\.(headers|getHeader\(|getHeaders\(\))((\[(['"]))|(\\.?((get\((["']))|(["'])?)))([a-zA-Z0-9_.-]+)(?![^\n\r]{0,10000}[=(])/gi;
const GROOVY_GET_HEADER_GROUPS = [11];

// SET
const GROOVY_SET_HEADERS_PATTERN =
  /exchange\.(message|getMessage\(\))\.((headers([[.]['"]?)([a-zA-Z0-9_.-]+)(['"]]?)?\s+=)|(setHeader\(['"]([a-zA-Z0-9_.-]+)(['")]?))|(((getHeaders\(\)|headers)\.remove|removeHeader)(\(['"])([a-zA-Z0-9_.-]+)))/gi;
const GROOVY_SET_HEADER_GROUPS = [5, 8, 14];

// GET
const GROOVY_GET_PROPERTIES_PATTERN =
  /exchange\.((properties|getProperty)((([[(])|(\.))['"]?)([a-zA-Z0-9_.-]+)(?![^\n\r]{0,10000}[=(]))/gi;
const GROOVY_GET_PROPERTIES_GROUPS = [7];

// SET
const GROOVY_SET_PROPERTIES_PATTERN =
  /exchange\.((properties[[.]['"]?([a-zA-Z0-9_.-]+)(['"]]?)?\s+=)|(setProperty\(['"]([a-zA-Z0-9_.-]+)['"]?\)?)|(properties\.remove|getProperties\(\)\.remove|removeProperty)(\(['"])([a-zA-Z0-9_.-]+))/gi;
const GROOVY_SET_PROPERTIES_GROUPS = [3, 6, 9];

const PROPS_SIMPLE_PATTERN =
  /\$\{exchangeProperty((\.([a-zA-Z0-9_-]+))|([.[]'?([a-zA-Z0-9_.-]+)))[^}]{0,10000}}/gi;
const EX_PROP_GROUPS = [3, 5];

const HEADERS_SIMPLE_PATTERN =
  /\$\{headera?s?((\.([a-zA-Z0-9_-]+))|([.[(]'?([a-zA-Z0-9_.-]+)))[^}]{0,10000}}/gi;
const EX_HEADER_GROUPS = [3, 5];

const TYPE_STRING_MAP: Record<string, UsedPropertyType> = {
  string: UsedPropertyType.STRING,
  number: UsedPropertyType.NUMBER,
  boolean: UsedPropertyType.BOOLEAN,
  object: UsedPropertyType.OBJECT,
};

function typeFromString(type: string | null | undefined): UsedPropertyType {
  if (!type) return UsedPropertyType.UNKNOWN_TYPE;
  return TYPE_STRING_MAP[type] ?? UsedPropertyType.UNKNOWN_TYPE;
}

interface PropertyBuilder {
  name: string;
  source: UsedPropertySource;
  type: UsedPropertyType;
  isArray: boolean;
  relatedElements: Map<string, ElementBuilder>;
}

interface ElementBuilder {
  id: string;
  name: string;
  type: string;
  operations: Set<UsedPropertyElementOperation>;
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

function deepMapTraversal(
  map: Record<string, unknown>,
  callback: (value: unknown) => void,
  keys: string[],
  keyIndex = 0,
): void {
  let value: unknown = map;
  for (let i = keyIndex; i < keys.length; i++) {
    if (isRecord(value)) {
      value = value[keys[i]];
    } else if (Array.isArray(value)) {
      for (const obj of value) {
        if (isRecord(obj) || Array.isArray(obj)) {
          deepTraverse(obj, callback, keys, i);
        }
      }
      return;
    } else {
      return;
    }
  }
  callback(value);
}

function deepTraverse(
  obj: unknown,
  callback: (value: unknown) => void,
  keys: string[],
  keyIndex: number,
): void {
  if (isRecord(obj)) {
    deepMapTraversal(obj, callback, keys, keyIndex);
  } else if (Array.isArray(obj)) {
    for (const el of obj) {
      deepTraverse(el, callback, keys, keyIndex);
    }
  }
}

function buildPropertyKey(name: string, source: UsedPropertySource): string {
  return name + source;
}

function addUsedProperty(
  map: Map<string, PropertyBuilder>,
  name: string,
  source: UsedPropertySource,
  type: UsedPropertyType,
  isArray: boolean,
  element: { id: string; name: string; type: string },
  operation: UsedPropertyElementOperation,
): void {
  const key = buildPropertyKey(name, source);
  let prop = map.get(key);
  if (!prop) {
    prop = { name, source, type, isArray, relatedElements: new Map() };
    map.set(key, prop);
  }
  let rel = prop.relatedElements.get(element.id);
  if (!rel) {
    rel = {
      id: element.id,
      name: element.name,
      type: element.type,
      operations: new Set(),
    };
    prop.relatedElements.set(element.id, rel);
  }
  rel.operations.add(operation);
}

function matchAndAdd(
  text: string,
  pattern: RegExp,
  groups: number[],
  element: AnalyzableElement,
  operation: UsedPropertyElementOperation,
  source: UsedPropertySource,
  map: Map<string, PropertyBuilder>,
): void {
  for (const match of text.matchAll(pattern)) {
    for (const g of groups) {
      const name = match[g];
      if (name) {
        addUsedProperty(
          map,
          name,
          source,
          UsedPropertyType.UNKNOWN_TYPE,
          false,
          element,
          operation,
        );
        break;
      }
    }
  }
}

function findInPropertyValues(
  element: AnalyzableElement,
  properties: Record<string, unknown>,
  map: Map<string, PropertyBuilder>,
): void {
  for (const [key, value] of Object.entries(properties)) {
    if (
      EXCLUDE_MAPPER_ELEMENTS.has(element.type) &&
      key === MAPPING_DESCRIPTION
    ) {
      continue;
    }
    parsePropertyValue(element, value, map);
  }
}

function parsePropertyValue(
  element: AnalyzableElement,
  value: unknown,
  map: Map<string, PropertyBuilder>,
): void {
  if (Array.isArray(value)) {
    for (const item of value) {
      parsePropertyValue(element, item, map);
    }
  }
  if (isRecord(value)) {
    findInPropertyValues(element, value, map);
  }
  if (typeof value === "string") {
    matchAndAdd(
      value,
      PROPS_SIMPLE_PATTERN,
      EX_PROP_GROUPS,
      element,
      UsedPropertyElementOperation.GET,
      UsedPropertySource.EXCHANGE_PROPERTY,
      map,
    );
    matchAndAdd(
      value,
      HEADERS_SIMPLE_PATTERN,
      EX_HEADER_GROUPS,
      element,
      UsedPropertyElementOperation.GET,
      UsedPropertySource.HEADER,
      map,
    );
  }
}

function pushStringIfString(scripts: string[], value: unknown): void {
  if (typeof value === "string") {
    scripts.push(value);
  }
}

function appendScriptsFromAfterArray(scripts: string[], after: unknown): void {
  if (!Array.isArray(after)) {
    return;
  }
  for (const item of after) {
    if (isRecord(item) && typeof item.script === "string") {
      scripts.push(item.script);
    }
  }
}

function appendServiceCallScripts(
  scripts: string[],
  props: Record<string, unknown>,
): void {
  appendScriptsFromAfterArray(scripts, props.after);
  const before = props.before;
  if (isRecord(before) && typeof before.script === "string") {
    scripts.push(before.script);
  }
}

function appendHttpTriggerHandlerScript(
  scripts: string[],
  props: Record<string, unknown>,
): void {
  const handler = props.handlerContainer;
  if (isRecord(handler) && typeof handler.script === "string") {
    scripts.push(handler.script);
  }
}

function collectElementScripts(element: AnalyzableElement): string[] {
  if (!ELEMENTS_WITH_SCRIPT.has(element.type)) {
    return [];
  }
  const props = element.properties ?? {};
  const scripts: string[] = [];

  switch (element.type) {
    case SCRIPT:
      pushStringIfString(scripts, props.script);
      break;
    case SERVICE_CALL:
      appendServiceCallScripts(scripts, props);
      break;
    case HTTP_TRIGGER:
      appendHttpTriggerHandlerScript(scripts, props);
      break;
  }

  return scripts;
}

function findInScript(
  element: AnalyzableElement,
  map: Map<string, PropertyBuilder>,
): void {
  const scripts = collectElementScripts(element);
  if (scripts.length === 0) {
    return;
  }
  const combined = scripts.join("\n");

  matchAndAdd(
    combined,
    GROOVY_GET_HEADERS_PATTERN,
    GROOVY_GET_HEADER_GROUPS,
    element,
    UsedPropertyElementOperation.GET,
    UsedPropertySource.HEADER,
    map,
  );
  matchAndAdd(
    combined,
    GROOVY_SET_HEADERS_PATTERN,
    GROOVY_SET_HEADER_GROUPS,
    element,
    UsedPropertyElementOperation.SET,
    UsedPropertySource.HEADER,
    map,
  );
  matchAndAdd(
    combined,
    GROOVY_GET_PROPERTIES_PATTERN,
    GROOVY_GET_PROPERTIES_GROUPS,
    element,
    UsedPropertyElementOperation.GET,
    UsedPropertySource.EXCHANGE_PROPERTY,
    map,
  );
  matchAndAdd(
    combined,
    GROOVY_SET_PROPERTIES_PATTERN,
    GROOVY_SET_PROPERTIES_GROUPS,
    element,
    UsedPropertyElementOperation.SET,
    UsedPropertySource.EXCHANGE_PROPERTY,
    map,
  );
}

function addMapperProperties(
  value: unknown,
  element: AnalyzableElement,
  operation: UsedPropertyElementOperation,
  source: UsedPropertySource,
  map: Map<string, PropertyBuilder>,
): void {
  if (!Array.isArray(value)) return;
  for (const entry of value) {
    if (!isRecord(entry)) continue;
    const name = entry.name;
    const typeObj = entry.type;
    if (typeof name !== "string" || !name || !isRecord(typeObj)) continue;
    const typeName = typeObj.name;
    if (typeof typeName !== "string" || !typeName) continue;

    if (typeName !== "array") {
      addUsedProperty(
        map,
        name,
        source,
        typeFromString(typeName),
        false,
        element,
        operation,
      );
    } else {
      deepMapTraversal(
        entry,
        (arrayTypeName) => {
          if (typeof arrayTypeName === "string") {
            addUsedProperty(
              map,
              name,
              source,
              typeFromString(arrayTypeName),
              true,
              element,
              operation,
            );
          }
        },
        ["type", "itemType", "name"],
      );
    }
  }
}

function findInMapper(
  element: AnalyzableElement,
  map: Map<string, PropertyBuilder>,
): void {
  if (!ELEMENTS_WITH_MAPPER.has(element.type)) return;
  const props = element.properties ?? {};
  const mappingDescriptions: Record<string, unknown>[] = [];

  switch (element.type) {
    case MAPPER_2:
      deepMapTraversal(
        props,
        (v) => {
          if (isRecord(v)) mappingDescriptions.push(v);
        },
        [MAPPING_DESCRIPTION],
      );
      break;
    case SERVICE_CALL:
      deepMapTraversal(
        props,
        (v) => {
          if (isRecord(v)) mappingDescriptions.push(v);
        },
        ["after", MAPPING_DESCRIPTION],
      );
      deepMapTraversal(
        props,
        (v) => {
          if (isRecord(v)) mappingDescriptions.push(v);
        },
        ["before", MAPPING_DESCRIPTION],
      );
      deepMapTraversal(
        props,
        (v) => {
          if (isRecord(v)) mappingDescriptions.push(v);
        },
        ["handlerContainer", MAPPING_DESCRIPTION],
      );
      break;
    case HTTP_TRIGGER:
      deepMapTraversal(
        props,
        (v) => {
          if (isRecord(v)) mappingDescriptions.push(v);
        },
        ["handlerContainer", MAPPING_DESCRIPTION],
      );
      break;
  }

  for (const md of mappingDescriptions) {
    deepMapTraversal(
      md,
      (v) => {
        addMapperProperties(
          v,
          element,
          UsedPropertyElementOperation.GET,
          UsedPropertySource.HEADER,
          map,
        );
      },
      ["source", "headers"],
    );
    deepMapTraversal(
      md,
      (v) => {
        addMapperProperties(
          v,
          element,
          UsedPropertyElementOperation.GET,
          UsedPropertySource.EXCHANGE_PROPERTY,
          map,
        );
      },
      ["source", "properties"],
    );
    deepMapTraversal(
      md,
      (v) => {
        addMapperProperties(
          v,
          element,
          UsedPropertyElementOperation.SET,
          UsedPropertySource.HEADER,
          map,
        );
      },
      ["target", "headers"],
    );
    deepMapTraversal(
      md,
      (v) => {
        addMapperProperties(
          v,
          element,
          UsedPropertyElementOperation.SET,
          UsedPropertySource.EXCHANGE_PROPERTY,
          map,
        );
      },
      ["target", "properties"],
    );
  }
}

function findInHeaderModification(
  element: AnalyzableElement,
  map: Map<string, PropertyBuilder>,
): void {
  if (element.type !== HEADER_MODIFICATION) return;
  const props = element.properties ?? {};
  const toAdd = props.headerModificationToAdd;
  const toRemove = props.headerModificationToRemove;

  if (isRecord(toAdd)) {
    for (const key of Object.keys(toAdd)) {
      addUsedProperty(
        map,
        key,
        UsedPropertySource.HEADER,
        UsedPropertyType.UNKNOWN_TYPE,
        false,
        element,
        UsedPropertyElementOperation.SET,
      );
    }
  }

  if (Array.isArray(toRemove)) {
    for (const key of toRemove) {
      if (typeof key === "string") {
        addUsedProperty(
          map,
          key,
          UsedPropertySource.HEADER,
          UsedPropertyType.UNKNOWN_TYPE,
          false,
          element,
          UsedPropertyElementOperation.SET,
        );
      }
    }
  }
}

function toUsedProperties(map: Map<string, PropertyBuilder>): UsedProperty[] {
  const result: UsedProperty[] = [];
  for (const builder of map.values()) {
    const relatedElements: Record<string, UsedPropertyElement> = {};
    for (const [id, el] of builder.relatedElements) {
      relatedElements[id] = {
        id: el.id,
        name: el.name,
        type: el.type,
        operations: Array.from(el.operations),
      };
    }
    result.push({
      name: builder.name,
      source: builder.source,
      type: builder.type,
      isArray: builder.isArray,
      relatedElements,
    });
  }
  return result;
}

export function analyzeUsedProperties(
  elements: AnalyzableElement[],
): UsedProperty[] {
  const map = new Map<string, PropertyBuilder>();
  for (const element of elements) {
    const props = element.properties ?? {};
    findInPropertyValues(element, props, map);
    findInScript(element, map);
    findInMapper(element, map);
    findInHeaderModification(element, map);
  }
  return toUsedProperties(map);
}
