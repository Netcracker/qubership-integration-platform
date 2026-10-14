---
name: sonar-triage
description: Triage the SonarCloud findings on a pull request — read the quality gate, classify each open issue as a real defect or a false positive, fix the real ones, and close new-code coverage gaps. Use only when the user asks about Sonar, SonarCloud, a quality gate, or pastes a sonarcloud.io link; do not auto-fire on unrelated turns.
---

# Triaging SonarCloud on a pull request

Every module publishes to its own SonarCloud project, so a pull request that
spans `runtime-catalog`, `ui`, and `vscode-extension` has three separate gates
and three separate issue lists. Triage each one; a green gate on one module says
nothing about the others.

## Project keys

Keys follow `Netcracker_qubership-integration-platform[-<module>]` and are
stored as repository variables, so read them rather than typing them:

```bash
gh api repos/Netcracker/qubership-integration-platform/actions/variables \
  --jq '.variables[] | select(.name|test("SONAR")) | "\(.name)=\(.value)"'
```

`SONAR_PROJECT_KEY` (no suffix) is the aggregate project; the per-module keys
are `-engine`, `-micro-engine`, `-runtime-catalog`, `-sessions-management`,
`-ui`, and `-vscode-extension`.

The read API needs no token — plain `curl` against `https://sonarcloud.io/api`
works. Scope every call to the pull request with `&pullRequest=<N>`; without it
you get the branch's whole backlog instead of what this change introduced.

Component keys for a single file are `<projectKey>:<path>`, where the path is
relative to the **module** directory, not the repository root, because each scan
sets its own base directory (`projectBaseDir: ui` for the UI,
the module POM for Maven modules). So a UI file is
`…-ui:src/diagrams/builder.ts` and a Java file is
`…-runtime-catalog:src/main/java/org/qubership/…`. URL-encode the colon and the
slashes.

## What actually blocks the merge

The UI scan passes `-Dsonar.qualitygate.wait=true`, so a red gate fails its own
job. The Maven modules' reusable `sonar` job does not wait; it blocks a merge
only when branch protection marks that job as a required status check. Check
which of the two you are dealing with before telling the user a red gate is
harmless.

## Sequence

1. **Gate first.** `api/qualitygates/project_status?projectKey=$K&pullRequest=$N`
   returns the failing conditions with their thresholds. The conditions are on
   new code, so a module can sit at 40% overall coverage and still pass.
2. **Pull the open issues.**
   `api/issues/search?componentKeys=$K&pullRequest=$N&issueStatuses=OPEN,CONFIRMED&ps=500`.
   Add `&types=BUG` or `&impactSoftwareQualities=RELIABILITY` to split a long
   list. Page size caps at 500.
3. **Classify every issue** as a real defect or a false positive, and say which
   is which in the report. Sonar's Java and TypeScript rules misfire on
   patterns this codebase uses deliberately — MapStruct-generated mappers,
   sealed-interface exhaustiveness, Handlebars template strings, and test
   fixtures that repeat structure on purpose.
4. **Fix the real ones in place.** Leaving a genuine defect for later means
   re-triaging the same issue on the next run.
5. **Report the false positives as a list** with rule key, file, and the reason
   each is safe. The user marks them in the SonarCloud UI; the API cannot do it
   without a token.

## Closing a coverage condition

Find the files that carry the gap rather than guessing:

```bash
curl -s "https://sonarcloud.io/api/measures/component_tree?component=$K&pullRequest=$N\
&metricKeys=new_uncovered_lines,new_lines_to_cover&qualifiers=FIL\
&s=metric&metricSort=new_uncovered_lines&asc=false&ps=15"
```

`api/measures/component?…&metricKeys=new_coverage,new_lines,new_uncovered_lines`
gives the module aggregate — use it to check whether the tests you added moved
the number past the threshold before pushing again.

For a duplication condition, `api/duplications/show?key=<componentKey>&pullRequest=$N`
prints the exact blocks and their partner files, which is the only practical way
to tell real duplication from two files that merely share a shape.
