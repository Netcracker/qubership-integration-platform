# QIP catalog PATCH: defaults and optional fields

This note complements YAML under `classpath:qip-schemas/` (from monorepo `schemas/` at build time) and the deterministic tools
`describeElementPatchSchema`, `describeElementProperty`, plus server-side merge and JSON Schema validation inside `updateElement`
(before the runtime-catalog PATCH).

## When the design is silent about a property

1. **Schema shows a `default`**  
   Prefer **omitting** the key from `properties` so the catalog keeps its built-in default, unless
   a catalog `400` or `updateElement` error clearly requires that branch to be filled. If the
   catalog still rejects the patch, add the key with the **exact** `default` from schema-backed
   output (`describeElementPatchSchema`, `describeElementProperty`, or embedded docs).

2. **Schema has no `default` and the field is optional**  
   Leave the key out of `properties`. Keep the object limited to keys the design or validation
   actually needs.

3. **Field is required** (design, `describeElementPatchSchema`, `describeElementProperty`, or catalog error)  
   Supply a value from the design, from schema tools, or from allowed
   `enum` / `const` / `oneOf` variants—use schema-backed literals only.

## Field names and catalog errors

QIP element `properties` follow embedded schemas and docs, not a generic REST shape. Names and structures
come from `describeElementPatchSchema`, `describeElementProperty`, RAG chunks, or `classpath:qip-schemas/`.

The AI service merges **schema `default` values for unconditionally required** `properties.*` keys
that are absent or JSON-null during server-side preparation before PATCH (see merged validation
behavior in the service). Optional properties with defaults may still need explicit keys when the
catalog rejects a branch; follow `updateElement` error text.

After `updateElement`, read `errorMessage` and adjust the fields it names. Extend `properties` only
with keys the error or schema points to (for example add `requestBody` only when that key appears
in the schema path you are fixing).

## RAG sources

Embedded knowledge uses `source=docs` for files under `classpath:docs/` and `source=schema` for
`classpath:qip-schemas/`. Prefer `describeElementPatchSchema` then `describeElementProperty` for PATCH paths; use RAG for prose
and cross-element explanations.
