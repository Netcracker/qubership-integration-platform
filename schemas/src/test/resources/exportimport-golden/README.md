# Service export/import golden archives

Four full archive trees, each produced by runtime-catalog's real serializer chain. They live here rather than in
runtime-catalog because two modules read them:

- **runtime-catalog** — through a `<testResource>` that copies this directory onto the test classpath as
  `/exportimport-golden`. `GoldenServiceCorpus` is the accessor; `ServiceExportFormatTest`, `ServiceTypeRoundTripTest`,
  `ServiceFormatConvergenceTest`, `SystemExportImportServiceTest` and `PreS553DiscoveryCompatibilityTest` read them.
- **vscode-extension** — `pretest:integration` copies `post553` and `post553-dotted` into its integration workspace, so
  the extension is exercised against bytes the backend wrote rather than fixtures authored to match it.

| Set | What it is | Regenerable |
|---|---|---|
| `pre553-current` | the current format as it stood before issue #553: the plain name, the plain `$schema`, and the type in the document | **no** — only a pre-#553 checkout can produce it |
| `legacy-flat` | `QIP_EXPORT_LEGACY_FORMAT=true` | **no** — same reason |
| `post553` | today's format: the plain `<id>.service.<app>.yaml` name, the type stated by a per-type `$schema` | yes |
| `post553-dotted` | the same, over an api group and an api whose ids carry dots | yes |

```bash
mvn -pl runtime-catalog test -Dtest=GoldenCorpusCapture#capturePost553 -DfailIfNoTests=false
mvn -pl runtime-catalog test -Dtest=GoldenCorpusCapture#capturePost553Dotted -DfailIfNoTests=false
```

`GoldenCorpusCapture` checks the archive it built **before** writing anything, so running the wrong method on the wrong
checkout fails instead of overwriting a baseline. It sits outside Surefire's include patterns, so the suite never runs
it. Those guards read the **document**, not the file name: the type moved out of the name and back into `$schema`, so
`pre553-current` and `post553` are named identically, file for file, and only the stamped schema tells the two
checkouts apart. The names differ in exactly one window — the versions that shipped #553 — and no set records it.

## This is recorded output, not a declaration

Read that distinction before editing anything here. The `naming/` corpus next door **declares** a rule that two
implementations are measured against; these trees are **what one implementation produced**. The failure mode is
different: a snapshot can be regenerated, and a regeneration that follows a drift blesses it — commit, green, nobody
notices.

Two things stand against that:

- `ServiceExportFormatTest.currentExportMatchesTheRecordedFormat` and `dottedExportMatchesTheRecordedFormat` compare a
  fresh export against the recorded set, file for file and document for document. Without them these would be trees
  nothing regenerates and the exporter could drift away with the whole suite green.
- The two historical sets cannot be regenerated at all, which is what makes "the legacy format is unchanged" a
  measurement rather than a claim.

So a red case here asks whether the export changed on purpose. If it did, regenerate and the diff is the change under
review. Never hand-edit a file to make a test pass.

**After regenerating, run `mvn clean`.** `target/test-classes` keeps the previous copy, and a renamed file leaves both
names behind — a test then runs against a tree these sources do not hold.
