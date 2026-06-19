---
name: mapper-element-skill
description: Mapper Element provides an ability to setup a mapping between the message from source element to the target element. Use this skill when performing mapping between two elements, requiring data transformation, the user asks to add mapper/mapper-2.
tags: [mapper element, mapper, mapper-2, mapping]
---
# Mapper Element Instruction

## Purpose
The Mapper is a transformation step in an integration chain. Its job is to take the current message (body, headers, exchange properties) from source element and change it into a different structure to target element. Mapper element performs mapping between two elements of the chain.

## When to use
Use Mapper Element skill when:
- Setting up mapping between two elements in the chain.
- Transforming message from source element to suitable structure of target element.
- Changing Body, Header, exchange Properties from source element to target element by remapping them to different structure.
- User asks to add Mapper element to the chain.
---

# Interaction Workflow (MANDATORY)
## Step 1: Information Collection
**For ANY request involving creation, modification, update, insertion, or deletion of a Mapper:**

**Mandatory inputs:**
Between which elements should Mapper Element be placed
Mapper source
Mapper target
Mapping actions (optional)

**If ANY of these inputs are missing (actions are optional):**
- STOP immediately.
- DO NOT create or modify files.
- DO NOT generate implementation.
- DO NOT infer values from context.
- Ask the missing questions. Wait for user response.

The request itself is NOT considered sufficient evidence.

**Examples:**
User: Asks to add Mapper or Map something without inputs above
Required response:
"I cannot create the mapper yet because the following mandatory inputs are missing:
- Between which elements of the chain, should be Mapper element be added
- Mapper source
- Mapper target
- Mapping actions (optional)
  Please provide them."

**Forbidden behavior:**
- Creating a mapper with assumed source.
- Creating a mapper with assumed target.
- Generating placeholder mappings.
- Modifying files before receiving all mandatory inputs.

## Step 2: Mapping rules — Provided Design Markdown (SKIP IF USER HAVE NOT PROVIDED ANY ATTACHED DESIGN MD)
If user have NOT provided attached Design Markdown, skip reading STEP 2 and go to the STEP 3.
Else:
### A. User provided Markdown mapping table
Format: `Source field | Target field | Transformation Logic`
1. Parse rows into `source`, `target`,`actions`, `constants`, `transformation`
2. Build/extend source and target schemas from referenced paths
3. Apply transformation keywords:
    - copy / direct → no transformation (single source)
    - expression, conditional, dictionary, defaultValue, formatDateTime, replaceAll, trim
    - constant / generated → `constants` + `type: constant` source


## Step 3: Learn `mappingDescription` JSON Creation Rules (Start this Step ONLY IF YOU DID STEP 1 [ASKED USER INPUT])
** if you DIDN'T get information from user, before learning about the structure, RETURN TO STEP 1!**

Else if you get information from user:
### File naming

For large mappings, store as a sidecar file:

```
mappingDescription-<mapper-element-id>.json
```

Chain element references it:

```yaml
properties:
  exportFileExtension: "json"
  propertiesToExportInSeparateFile: "mappingDescription"
  propertiesFilename: "mappingDescription-<mapper-element-id>.json"
```

The JSON file contains **only** the `MappingDescription` object — **not** wrapped in `"mappingDescription": { ... }` unless your import path expects the wrapper. In chain YAML inline form it lives under `properties.mappingDescription`.

### Top-level shape

```json
{
  "source": {},
  "target": {},
  "constants": [],
  "actions": []
}
```

| Field | Role |
|-------|------|
| `source` | **Left side** — message shape **entering** the mapper |
| `target` | **Right side** — message shape **leaving** the mapper |
| `constants` | Reusable literal or generated values |
| `actions` | Connections: sources → target (+ optional transformation) |

All four keys should be present. Use `[]` for empty `constants` / `actions`.

### `source` / `target` (`MessageSchema`)

```json
{
  "headers": [],
  "properties": [],
  "body": null
}
```

| Part | Camel meaning | JSON shape |
|------|----------------|------------|
| `headers` | Message headers | Array of **attributes** |
| `properties` | Exchange properties | Array of **attributes** |
| `body` | Message body | `null` or a **DataType** object (usually `object`) |

**Rules:**

- Use `[]` for empty headers/properties
- Use `body: null` when there is no body
- For JSON/XML payloads, body is typically an `object` with `schema.attributes`

### Body object (typical JSON case)

```json
"body": {
  "name": "object",
  "schema": {
    "id": "657c7c0ae57146bd9dde5edba4d8e6bd",
    "attributes": []
  },
  "metadata": {
    "dataFormat": "JSON"
  },
  "definitions": []
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `name` | Yes | Usually `"object"` for JSON root |
| `schema.id` | Yes | Unique 32-char |
| `schema.attributes` | Yes | Top-level body fields |
| `metadata.dataFormat` | Recommended | `"JSON"` or `"XML"` |
| `definitions` | Optional | Reusable types for `reference` fields |

### Attribute (headers, properties, body fields)

```json
{
  "id": "1411cb3e5ffb4ea59872dee84ad2d86a",
  "name": "customerId",
  "type": { "name": "string" },
  "required": false
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `id` | Yes | **32 hex chars, no dashes** — referenced in `actions.path` |
| `name` | Yes | Logical field name / JSON key |
| `type` | Yes | See types below |
| `required` | Optional | On **target**: if `true`, must have ≥1 action |

**Common `type.name` values:**

```json
{ "name": "null" }
{ "name": "string" }
{ "name": "number" }
{ "name": "boolean" }
```

**Object (nested):**

```json
{
  "name": "object",
  "schema": {
    "id": "nested_schema_id",
    "attributes": []
  }
}
```

**Array:**

```json
{
  "name": "array",
  "itemType": { "name": "string" }
}
```

**Reference (shared definition):**

```json
{
  "name": "reference",
  "definitionId": "534189b5c908413e8794d84e3c2d6e6a"
}
```

### `constants`

**Literal:**

```json
{
  "id": "a277fe00b4bb420e9cdb90d882565237",
  "name": "statusDefault",
  "type": { "name": "string" },
  "valueSupplier": {
    "kind": "given",
    "value": "ACTIVE"
  }
}
```

**Generated:**

```json
{
  "id": "53c34279aab64a2fbbacdf375a1beb67",
  "name": "requestId",
  "type": { "name": "string" },
  "valueSupplier": {
    "kind": "generated",
    "generator": {
      "name": "generateUUID",
      "parameters": []
    }
  }
}
```

**Generators:** `generateUUID`, `currentDate`, `currentTime`, `currentDateTime`

### `actions` — where mapping is defined

Each action connects **one or more sources** to **one target**.

```json
{
  "id": "6b3785bbf49443bda06d1837828980ab",
  "sources": [],
  "target": {},
  "transformation": {}
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `id` | Yes | Unique action ID (32 hex) |
| `sources` | Yes | ≥1 source reference |
| `target` | Yes | Exactly one target attribute |
| `transformation` | No | Omit for 1:1 single-source copy |

**From message field (attribute):**

```json
{
  "type": "attribute",
  "kind": "body",
  "path": ["1411cb3e5ffb4ea59872dee84ad2d86a"]
}
```

**Nested body field:**

```json
{
  "type": "attribute",
  "kind": "body",
  "path": ["parentAttrId", "childAttrId", "leafAttrId"]
}
```

**From constant:**

```json
{
  "type": "constant",
  "constantId": "a277fe00b4bb420e9cdb90d882565237"
}
```

**`kind` values (exact):**

| Value | Maps |
|-------|------|
| `body` | Message body |
| `header` | Headers |
| `property` | Exchange properties (not `properties`) |

**Target reference:**

```json
"target": {
  "type": "attribute",
  "kind": "body",
  "path": ["72904f65fea04d17a013b4301d39614b"]
}
```

> **Important:** `path` contains **attribute IDs from source/target schemas**, not chain element IDs and not field names.

### `transformation` (optional)

```json
"transformation": {
  "name": "expression",
  "parameters": ["'Hello, ' + body.name + '!'"]
}
```

| `name` | `parameters` |
|--------|----------------|
| `expression` | `[ "<expr>" ]` |
| `conditional` | `[ condition, trueExpr, falseExpr? ]` |
| `dictionary` | `[ default, "key=value", ... ]` |
| `defaultValue` | `[ "<fallback>" ]` |
| `formatDateTime` | 8 strings (in/out epoch, format, locale, timezone) |
| `replaceAll` | `[ regex, replacement ]` |
| `trim` | `[ "both" \| "left" \| "right" ]` |

**Rules:**

- Single source + 1:1 copy → no `transformation`
- Multiple sources → one target → **`transformation` required**

### ID generation rules

1. Every attribute, schema, constant, and action needs a unique `id`
2. Format: **32 lowercase hex characters**, no `-`
3. Generate once; **never change** IDs already referenced in `actions.path`
4. Action `path` must only use IDs that exist in `source` / `target` / `constants`

### Fill-out order (mandatory)

```
1. Build source  (headers, properties, body + all attribute IDs)
2. Build target  (same structure + mark required fields)
3. Add constants (if any transformations need literals/generators)
4. Add actions   (wire source IDs → target IDs)
5. Validate      (see checklist below)
```

### Validation checklist before saving JSON

- [ ] `source`, `target`, `constants`, `actions` all present
- [ ] Every `actions[].path` segment exists in schemas
- [ ] Every `constantId` exists in `constants`
- [ ] `kind` is `body` | `header` | `property` (never `properties`)
- [ ] Every target field with `required: true` appears in some action `target.path`
- [ ] Multi-source actions have `transformation`
- [ ] Transformation `name` is from allowed list
- [ ] Body has `metadata.dataFormat` when body is non-null
- [ ] No AtlasMap JSON in this file

### Minimal complete example

```json
{
  "source": {
    "headers": [],
    "properties": [],
    "body": {
      "name": "object",
      "schema": {
        "id": "srcschema001",
        "attributes": [
          {
            "id": "srcname001",
            "name": "name",
            "type": { "name": "string" }
          }
        ]
      },
      "metadata": { "dataFormat": "JSON" },
      "definitions": []
    }
  },
  "target": {
    "headers": [],
    "properties": [],
    "body": {
      "name": "object",
      "schema": {
        "id": "tgtschema001",
        "attributes": [
          {
            "id": "tgtmsg001",
            "name": "message",
            "type": { "name": "string" },
            "required": true
          }
        ]
      },
      "metadata": { "dataFormat": "JSON" },
      "definitions": []
    }
  },
  "constants": [],
  "actions": [
    {
      "id": "actcopy001",
      "sources": [
        {
          "type": "attribute",
          "kind": "body",
          "path": ["srcname001"]
        }
      ],
      "target": {
        "type": "attribute",
        "kind": "body",
        "path": ["tgtmsg001"]
      }
    }
  ]
}
```


# Inputs (ask before adding mapper)
Collect  following information before creating a mapper element unless the user already provided them clearly in the request or context:

## Mapping Source
Before adding source part in mappingDescription file, verify whether the user has provided source information.

Source information may include:
- Message body (JSON, XML, text, etc.)
- Headers
- Properties
- OpenAPI specification
- Sample payloads
- Field definitions

If the source is not present, incomplete, or cannot be understandable from the current conversation context, before adding mappingDescription.source,

Instead, ask:

"What is the source of the Mapper Element? Please provide any available body, headers, properties, sample JSON/XML payload, OpenAPI operation, or field definitions."

## Mapping Target
Before adding target part in mappingDescription, verify whether the user has provided source information.

Target information may include:
- Message body (JSON, XML, text, etc.)
- Headers
- Properties
- OpenAPI specification
- Sample payloads
- Field definitions

If the target is not present, incomplete, or cannot be understandable from the current conversation context, before adding mappingDescription.target,

Instead, ask:

"What is the target of the Mapper Element? Please provide any available body, headers, properties, sample JSON/XML payload, OpenAPI operation, or field definitions."

## Mapping Actions, Constants, Transformation Logic
Before adding actions, constants, transformation logic part in mappingDescription, verify whether the user has provided them.

**Prerequisites:**
- mappingDescription.source is defined
- mappingDescription.target is defined

**Actions / constants / transformation (transformation logic) information may include:**
- Field mapping table — Source field | Target field | Transformation Logic (contains mapping description logic)
- 1:1 occurence rules — which source fields map to which target fields
- Cross-bucket mappings — body, header, properties
- Constants - literal values or generated values (generateUUID, currentDate, currentTime, currentDateTime)
- Transformations (Mapping description)
- Aggregation rules — multiple sources → one target (transformation required)
- Nested paths — parent/child field chains (e.g body.customer.id → body.order.customerId)
- Required target fields - list of mandatory fields that must have actions

If mapping rules are not present, incomplete, or cannot be undestandable from provided source/target schemas, before adding actions, constants, or transformation, ask:
""How should fields be mapped from source to target? Please provide a mapping table or describe transformations."

**IMPORTANT**
If user have not provided mapping logic, constants, transformations, you can create this fields empty.

# Transformation Expressions

## Purpose

Use when user want to:

- Change mapper output values
- Transform input fields
- Build expressions inside Mapper
- Add conditions
- Perform arithmetic calculations
- Concatenate values
---
Verify twice that user mentioned any of the
above points, if user mentioned, read below Supported Transformations carefully, they are placed in skills and called /mapper-element-skill/mapper-element-transformations-skill

# Rules

- Use `body.<fieldName>` to reference input fields.
- Arithmetic operators can be used inside expressions and conditions.
- Conditions must use the `if(condition, trueValue, falseValue)` format.
- String literals must be wrapped in single quotes.
- Transformation expressions are defined using:

```json
{
  "transformation": {
    "name": "expression",
    "parameters": [
      "<expression>"
    ]
  }
}
```

# Decision Guide
Verify twice that user mentioned any of the
above points, if user mentioned, read below Supported Transformations carefully, they are placed in skills /mapper-element-skill/mapper-element-transformations-skill.

If the user asks to:

| Request | Transformation |
|----------|---------------|
| Join fields | Concatenation |
| Add, subtract, multiply, divide | Arithmetic Expression |
| Compare values | IF Condition |
| Validate odd/even numbers | IF + Modulo |
| Build custom formula | Expression |
| Check if a field is empty | IsEmpty Condition |
| Convert string to lowercase | toLower Expression |
| Filter array items by condition | filterBy |
| Get first element from array | getFirst |
| Get first element matching a condition | getFirst + filterBy |
| Get first element from filtered array and extract a field | getFirst + map + filterBy |
| Remove leading/trailing spaces from string | trim |
| Replace parts of a string using a pattern | replaceAll |
| Transform each element in an array | map |
| Sort array in ascending order | sort (ascending) |
| Sort array in descending order | sort (descending) |
| Pick all field names (keys) from object | getKeys |
| Pick all field values from object | getValues |
| Build a date/time from fields or constants | formatDateTime |
| Build a date/time with timezone and locale | formatDateTime with constants |
| Build a date/time skipping time components | formatDateTime with null |
| Build a key/value object from primitives | makeObject |
| Merge multiple objects or arrays into one object | mergeObjects |
| Build a list (array) from primitives, objects, or arrays | list |
