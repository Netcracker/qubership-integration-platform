# AtlasMap testing

Read this file when Java unit test work involves AtlasMap, mapper processing, mapping definitions, field builders, expression handling, or AtlasMap-related project helpers.

Use this reference for tests around:

* `MapperProcessor`
* AtlasMap mapping model objects
* `AtlasMapping`
* `CustomAtlasContext`
* mapper registry/cache behavior
* mapping field creation
* expression fields
* simple, complex, group, collection, JSON, and enum fields
* AtlasMap actions and null-action behavior
* mapper-related fallback, validation, and error branches

For shared helper names and imports, read `references/project-test-helpers.md`.

## Testing level

* Prefer plain JUnit 5 + Mockito tests for AtlasMap-related unit tests.
* Use real AtlasMap model objects when they make the test clearer.
* Mock only external dependencies, expensive collaborators, cache/registry boundaries, or error branches that are hard to reach with real model objects.

For mapper processors and helpers, focus on observable behavior:

* produced mappings
* transformed fields
* selected expressions
* validation results
* fallback behavior
* registry/cache calls
* deployment ID usage
* exception handling
* output body, headers, or exchange properties when Camel is involved

Avoid tests that only execute AtlasMap model setters without verifying useful behavior.

## Project mapper helpers

Prefer shared helpers from:

```java
import org.qubership.integration.platform.engine.testutils.MapperTestUtils;
```

Use existing helpers for:

* expression helpers
* boolean assertion helpers
* simple field builders
* field group builders
* complex field builders
* JSON field builders
* JSON enum field builders
* collection helpers
* typed casts/assertions
* fields with null actions

Before adding a local helper method:

1. inspect `MapperTestUtils`
2. inspect nearby mapper tests
3. reuse an existing helper if it fits
4. extend `MapperTestUtils` only when the new helper is reusable across tests

Prefer shared helpers over duplicating local builders in each test class.

## Model API safety

Use the actual AtlasMap API available in this repository. Do not assume a method exists based on another AtlasMap version.

Before using an unfamiliar model method:

1. inspect the current model class
2. inspect existing production code
3. inspect nearby tests
4. follow the current project pattern

For data sources, do not use:

```java
mapping.setDataSource(...)
```

unless that method exists in the current project version.

When the project pattern uses the mutable data source list, add data sources through the list:

```java
mapping.getDataSource().add(dataSource);
```

Use the exact object types and enum values present in the current codebase.

## Field builder guidance

Prefer helper-created fields over manual model construction when the helper expresses the intent.

Good examples:

```java
Field source = MapperTestUtils.simpleField("/source");
Field target = MapperTestUtils.simpleField("/target");
```

```java
Field jsonField = MapperTestUtils.jsonField("/payload/name");
```

```java
Field complexField = MapperTestUtils.complexField("/customer");
```

Use local model construction only when the test needs a very specific field shape that no shared helper supports. Keep field setup minimal. 
Build only the fields required for the behavior under test.

## Expression-related tests

For expression mappings, verify behavior at the level of the produced or interpreted mapping.

Prefer assertions on:

* expression value
* selected source fields
* selected target fields
* expression field type
* action list
* mapping type
* fallback behavior for missing or invalid expressions

Use shared expression helpers when available. Avoid verifying incidental object graph details that do not affect the mapper contract.

## Collections and grouped fields

For collection and grouped-field tests, make the collection intent visible in the setup.

Prefer shared helpers for:

* collection fields
* fields with collection keys
* grouped fields
* nested item fields
* typed casts

Assert the behavior that matters:

* collection key is preserved
* nested field path is correct
* grouped fields are included
* item field is attached to the right parent
* output mapping contains expected source and target fields

Avoid large full-object equality assertions unless the complete object graph is the contract.

## JSON and enum fields

For JSON field tests, prefer shared JSON helpers.

Assert the relevant field properties:

* path
* field type
* doc ID
* enum value
* nested structure
* action list
* null-action behavior

* Use real ObjectMapper helpers for normal JSON behavior. 
* Read `references/project-test-helpers.md` when ObjectMapper setup is needed.
* Mock ObjectMapper only for targeted error or fallback branches.

## MapperProcessor tests

Before editing `MapperProcessor` tests, inspect the production class carefully.

Pay attention to:

* CDI annotations such as `@ApplicationScoped`
* `@Named`
* `@ConfigProperty`
* `MetadataService`
* deployment ID lookup
* mapper registry/cache behavior
* `CustomAtlasContext`
* Camel `Exchange` usage
* static helper calls
* constructors or factory calls that may require targeted construction mocking

Prefer real objects for normal mapping behavior. Mock boundaries such as metadata lookup, registry access, or custom context calls when they are not the behavior under test.
When the processor reads or writes a Camel `Exchange`, also read `references/camel-testing.md`.

## CustomAtlasContext tests

For `CustomAtlasContext` or context-related tests, identify the boundary under test.

Useful behaviors to cover:

* context is created with expected mapping data
* mapping lookup succeeds
* mapping lookup fails
* processing delegates to the expected context
* exception branches are handled
* registry/cache entries are used or updated
* missing deployment or mapping metadata is handled

Use constructor mocking only when production code directly creates the context and no cleaner seam exists. Keep construction mocks scoped to one test.

## Static and construction mocking

Use static or construction mocking only for targeted branches.

Before adding static or construction mocking, check whether the same behavior can be verified through:

* real AtlasMap model objects
* shared `MapperTestUtils` helpers
* mocked external dependencies
* registry/cache interactions
* public processor output
* Camel exchange state

When static mocking is necessary:

* use try-with-resources
* keep the scope inside one test
* mock only the required static method
* avoid combining unrelated static mocks in one test
* verify only meaningful behavior

When construction mocking is necessary:

* keep the construction scope inside one test
* assert the externally visible result
* avoid verifying every constructor argument unless constructor selection is the behavior under test

## Error and fallback branches

Good AtlasMap branches to cover:

* missing mapping metadata
* empty mapping definition
* invalid expression
* unsupported field shape
* missing source field
* missing target field
* null actions
* mapper registry miss
* mapper registry hit
* custom context creation failure
* processing failure
* JSON parsing failure
* fallback to default mapper behavior

For exception branches, assert the public contract:

* thrown exception
* fallback result
* unchanged exchange state
* error header/property
* skipped dependency call
* registry/cache not updated
* clear failure state

Avoid weakening assertions just to make a complex object graph pass.

## Coverage guidance

For JaCoCo coverage, prefer behavior-rich branches.

Good coverage targets:

* validation branches
* null and empty mapping branches
* fallback branches
* exception branches
* registry/cache hit and miss branches
* expression selection branches
* field type branches
* collection/group branches
* JSON enum branches
* null-action branches

Avoid tests that only instantiate AtlasMap model objects without verifying mapper behavior.

Avoid full object graph assertions when a few focused property assertions express the behavior better.

## What to inspect before editing

Before adding or changing an AtlasMap-related test:

1. read the target production class
2. inspect existing mapper tests
3. inspect `MapperTestUtils`
4. identify whether Camel `Exchange` is involved
5. identify whether ObjectMapper behavior is involved
6. choose real model objects or mocks intentionally
7. check the actual AtlasMap model API before calling model methods
8. identify observable behavior and missed branches
9. keep the setup as small as possible

If Camel is involved, read `references/camel-testing.md`.

If shared helpers are needed, read `references/project-test-helpers.md`.

If static or construction mocking is needed, consider whether the same behavior can be verified through a public seam or project helper first.

## Common pitfalls

Avoid these patterns unless the branch genuinely requires them:

* duplicating local AtlasMap field builders that already exist in `MapperTestUtils`
* assuming AtlasMap APIs from another version
* testing AtlasMap internals instead of project mapper behavior
* building huge mapping graphs for a small branch
* asserting full object graphs when focused assertions are enough
* using construction mocking for normal happy-path behavior
* using static mocks just to increase line coverage
* mocking ObjectMapper for normal JSON serialization/deserialization
* weakening assertions because setup became too complex

## Output expectations for AtlasMap test work

When reporting AtlasMap-related test changes, mention:

* which mapper behavior or branch was covered
* whether `MapperTestUtils` was reused or extended
* whether real AtlasMap model objects or mocks were used
* whether Camel `Exchange` or ObjectMapper helpers were involved
* whether static or construction mocking was added or avoided
* which command was run
* any mapper behavior that remains untested because it would require a larger refactoring or integration-level test
