# Service file naming corpus

`service-file-names.yaml` states how a service file is named, once, for the two implementations that have to agree on
it:

- `runtime-catalog` — `ExportImportUtils` and `ServiceTypeFiles`, which anchor on position: the postfix is read in the
  segment right after the id.
- `vscode-extension` — `serviceFileType.ts`, which compares the whole extension end-anchored, app name included,
  longest match first.

The two strategies are deliberately different and **neither may be ported onto the other**. The backend does not know
the project's app name, so it cannot compare a whole extension; the extension does know it, and an end-anchored compare
is what keeps a project configuring `service: .svc.yaml` beside `externalService: .external.svc.yaml` from reading
every external file as a plain one.

## Why a corpus and not a snapshot

Nothing else binds the two builds: `runtime-catalog` has no Maven dependency on `qip-schemas`, and the extension has no
notion of the backend at all. A captured snapshot of one side's output would let a drift bless itself — regenerate,
commit, green. So this file states the **rule**, both sides are measured against it, and agreement follows.

The corpus is read by:

- `ServiceNameCorpusTest` (Java), through a `<testResource>` that copies this directory onto the test classpath as
  `/naming`;
- `serviceNameCorpus.test.ts` (TypeScript), by relative path;
- `JsonSchemaFormatTest` (Java, in this module), which derives each service schema's expected `metaInfo.fileExtension`
  from `rule.current.postfixes` and `types.schemaFileStems` — so a postfix edit here reddens the schema metadata too.

## Sections

- **`rule`** — how a name is built, per format and kind. A name states which *kind* of document a file holds and
  nothing else, so the three plain types share `.service.<app>.yaml`. Three different things are called "legacy" and
  they are kept apart here: the flat `service-<id>.yaml` that only Java writes and reads, the per-type
  `.external-service.<app>.yaml` family that both sides read and neither writes any more, and
  `context-service-<id>.yaml` / `mcp-service-<id>.yaml`, which Java writes under the legacy flag and **nothing**
  discovers.
- **`types`** — how a plain service states its *type*: through `$schema`, matched first as the configured URI and then
  by the schema's own file name. The second layer is the one that has to agree across implementations and across
  hosts, and the one nothing else in either build compares.
- **`alphabet`** — hostile ids and app names. An id wearing the flat prefix, an id spelling a postfix, dotted ids, and
  an app name that itself contains a postfix.
- **`classify`** — names both sides must *read*, with an outcome recorded **per side**.

## Editing rules

**A `classify` entry needs an explicit `appName`.** Without it the entry has two readings with opposite verdicts,
because the extension is parameterized by the app name and the backend ignores it.

**A disagreement between the sides is allowed, and must carry a written `divergence`.** Asserting blanket agreement
would turn a genuine finding into a red test that the next implementer "fixes" by editing this file. An *undeclared*
disagreement fails the suite.

**Neither implementation authors this file.** If a test goes red, the question is which side broke the rule — not what
value would make the test pass. Changing an outcome here to match new behaviour is a change to the contract, and needs
the same review as changing the code.
