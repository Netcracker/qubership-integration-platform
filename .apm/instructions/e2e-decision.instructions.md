---
description: "When a fix or a feature needs an end-to-end case, and where the decision is recorded."
applyTo: "**"
---

A change that fixes a defect or adds behavior ends with an end-to-end decision: a case under
`e2e/specs/` that covers it, or a sentence in the pull request description that names the reason
below it needs none. Decide while you reproduce a defect, because the case that reproduces it is
usually the one that stays.

Write an end-to-end case when no test inside one module can see the defect or the behavior:

- It crosses services: a chain the catalog compiles and the engine runs, a deploy the engine picks
  up, an import or export round trip, a session the engine records and the API searches, a message
  through a broker, a result that differs between the classic and the micro engine, a resource on
  Kubernetes.
- It needs the running stack: a Hibernate cascade or a transaction boundary, concurrent requests, a
  service restart, the nginx proxy, the deployed configuration.
- It is a UI flow that saves or reads data through the API, and the defect is in what the UI sends
  or in how it reads the real response, which a Jest test with a mocked response cannot show.
- It is UI behavior only a browser shows: a screen that throws, focus, a missing redraw, a control
  that another element covers.

The change needs no end-to-end case when:

- A unit or module test reaches it: a mapper test, a `TemplateServiceTest` golden pair, a Jest
  test. That test is the regression case.
- The UI defect is visual only: spacing, color, alignment, highlighting, text. Screenshots are the
  evidence.
- It changes logs, metrics, the build, CI, documentation, or a dependency version, or it refactors
  without changing behavior.
- It stays inside the VS Code extension. Its integration suite under
  `vscode-extension/src/web/test/suite/` covers it; `specs/api/extension-output.spec.ts` covers only
  the files the extension writes for import.

A fix for a defect the suite already pins removes the pin (`test.fail()` or `strikesAsKnown`), and
the case that then passes is the regression case. `grep -rn "#<N>\b" e2e/specs e2e/support` finds
the case that pins it.

A new regression case fails on the code without the fix and passes with it: run it both ways.
Load the `e2e-spec-authoring` skill before you write it; its rule 23 says how to run it and what
the pull request description records.
