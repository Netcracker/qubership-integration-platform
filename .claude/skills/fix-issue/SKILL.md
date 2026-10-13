---
name: fix-issue
description: Take a GitHub issue labeled ready-for-agent, fix it end to end against the running local stack, and deliver a branch, a pull request, an evidence report, and an autonomy label. Works for any module (ui, runtime-catalog, engine, micro-engine, integration-build-pipeline, vscode-extension, schemas, infrastructure). Use when asked to work an issue by number or URL, or to run the autonomous issue pipeline.
---

# Working a GitHub issue end-to-end

Gates 0 through 6, in order. Each gate either stops the run or hands off to the next one, and
the label is a function of the gate reached, never of how the work felt.

| Gate | Name | Stopping here means |
|---|---|---|
| 0 | Intake | the issue is not ready, or the branch cannot be made |
| 1 | Contract | no criterion a human would recognize, or the fix needs a decision that is not yours |
| 2 | Reproduce | the defect does not appear |
| 3 | Fix | the change would break a stop rule |
| 4 | Verify | a criterion or a static check is red |
| 4.5 | Review | a reproduced regression, which returns the run to gate 4 |
| 5 | Deliver | the branch, the pull request, or the issue link fails to write |
| 6 | Green checks | a required check stays red |

A stop at any gate: `ai:needs-human`, the full report on the issue, and an offer in chat to
finish the work together. A stop after gate 3 commits the work on the run's branch without
pushing it and names the branch in the report. Green checks at gate 6: `ai:processed`.

Run silently and ask nothing until the run ends. When a person decides something along the way
(picks an option after a stop, changes the shape, points at a gap), the run carries on and the
report records the decision. Every run ends with a report and a label, including a resumed run,
a reworked fix, and a run a person stepped into.

Companion files, read when the gate names them:

- `verification.md`: reproducing and verifying per module, and the traps of each stack.
- `delivery.md`: the `gh`, GitHub API, Sonar, and board commands, with their known failures.
- `stack.md`: bringing the stack up, reaching each service, worktrees, and what each symptom means.
- `k8s.md`: micro-engine, micro domains, and the cluster, which Compose cannot run.
- `scripts/wait-checks.sh`: the gate 6 waiter.

## The labels

`ai:processed` records the outcome: the pull request reached green checks. What a person
decided on the way is recorded in the report under **Assisted**, not in the label. A person,
never you, adds `human:ai-failed` to an issue already marked `ai:processed` when the delivered
work does not do the job, so the difference between the two counts results. If the issue
carries `human:ai-failed`, every review comment on the earlier pull request becomes a hard
constraint in the contract.

## Gate 0: intake

```bash
gh issue view <N> --json number,title,body,labels,state,comments   # without --json it fails on Projects classic
gh pr list --state open --search "<N>" --json number,headRefName
gh auth status                                  # needs the project scope for the board
```

Continue only when the issue carries `ready-for-agent` and no open pull request references it.
A request to run the skill is not the label: on an unlabeled issue, stop and say so, without
adding the label yourself. An issue you file on the user's request gets `ready-for-agent` from
you at creation.

Work in a worktree on a fresh branch from `origin/main`, and leave the user's checkout alone:
it is usually dirty, and another run may be using it.

```bash
REPO=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")   # the main checkout, from any worktree
git -C "$REPO" fetch origin main
WT="$HOME/qip-wt-<N>"                           # under $HOME: Docker cannot mount /tmp
git -C "$REPO" worktree add -b fix/<N>-<slug> "$WT" origin/main
```

Run every command against `$WT` by absolute path or as `git -C "$WT"`; a bare `cd` persists into
the next command. Remove the worktree when the run ends. List the open `fix/*` branches that
touch the same files and name any overlap in the pull request.

Download every attachment before writing the contract. The reporter's screenshot is often the
only evidence of the state to reproduce.

```bash
curl -sSL -H "Authorization: Bearer $(gh auth token)" -o "$TMP/issue.png" \
  "https://github.com/user-attachments/assets/<uuid>"
```

## Gate 1: the contract, before any edit

Split the issue into numbered defects. For each, record where it lives, which module owns it,
and **how you will prove it fixed**. Write this down before the first edit: code written first
gets rationalized, and a falsifiable criterion cannot be.

- **Use the reporter's words, not your metric.** "Labels are too small" passed as "font-size is
  equal" while the reporter still saw it.
- **The criterion is the artifact.** For a visual defect, the before and after image; for an
  API defect, the response before and after; for compiled output (Camel XML, a Helm render, a
  bundle), its diff.
- **Measure targets; never infer them.** Write "must equal the edit state", not "must become
  14px".
- **Walk the whole interaction.** Every entry and exit, every caller of the endpoint, both
  themes.
- **Answer a thin issue with a precedent.** How the product already does the same thing,
  quoted in the contract.
- **Bring in the twins.** Grep for the same code in the other of `engine` and `micro-engine` and
  in sibling modules. A path with the same root cause that the product reaches joins the
  contract and is measured like the named one; a finding on another subject goes to the report.
- **Name the product path to the bad state.** A UI form, the product's own export and import, or
  a real deployment. A state only a hand-made API write produces, such as a boolean property
  `PATCH`ed in as the string `"false"`, is not a defect: stop under rule 8 and say how you
  reached it.

### The shape of the fix

Name the layer and the form before writing code, and give the reasoning in the report. Every
question reviewers asked after a run was about the shape: why the backend and not the frontend,
what the new type is for, what the alternative was, whether the defect was worth fixing.

A fix proposed in the issue, earlier in the chat, or as a ready diff is one candidate shape,
judged here like your own. Fix where the fix is correct, across modules if the correct fix lives
in two places: a backend
flag with the UI that reads it beats a UI fan-out of N requests. Use the pattern the codebase
already has for the same need before inventing one. A new type, mapper, shared handler, or
shared configuration the contract does not require means more than one shape is defensible:
stop under rule 7.

## Gate 2: reproduce, beside a healthy peer

Nothing is fixed until it has been seen broken. Bring the stack up as `stack.md` says, seed it
through the product's own shapes with a unique run token, and capture "before" now, ahead of
any edit.

| Module | Reproduction | Healthy peer |
|---|---|---|
| ui | a case in `e2e/specs/ui/` (or Jest when jsdom shows it); scratch Playwright for screenshots in both themes | a neighboring control the issue does not name |
| runtime-catalog, sessions-management | request matrix before and after with `diff`; container log gated on level | the sibling endpoint on the same mapper |
| engine, micro-engine | compiled Camel XML from `deployments/update`, two variants in one chain; micro only on the cluster (`k8s.md`) | the element variant that works |
| integration-build-pipeline | a golden pair in `TemplateServiceTest`, then a new snapshot on the stack | an element whose template renders correctly |
| vscode-extension | real exports run through the code under test; Jest as reproduction | the protocol or shape that works |
| schemas | `ajv` against real documents from the catalog | a sibling schema |
| infrastructure | throwaway container beside the stack, `helm template` before and after | the neighboring service |

**Look at the pixels.** `opacity`, `visibility`, and `color` read normal on an element
something else paints over; one screenshot of the control is the evidence. "Does not reproduce"
takes the same evidence as "reproduces": a picture or a response body, not a property.

Delete what you seeded when the run ends, and stop only what you started.

## Gate 3: fix

Change the least that satisfies the contract, in the shape gate 1 decided. Then walk the diff
and attach every line to a criterion number; remove a line that belongs to none. Parity ("the
sibling service has it"), analogy ("postgres sets the same value"), and foresight ("a future
caller might need it") never justify a line, configuration and test helpers included.

A defect pinned in the end-to-end suite is part of the fix. `grep -rn "#<N>\b" e2e/specs
e2e/support` finds the pin: a `test.fail()` naming the issue, or an entry in
`support/known-defect.ts` whose constant the specs pass to `test.fail()`. Remove the entry and
every `test.fail()` that uses it; the case passing becomes your "after".

A generated file in the diff is a public contract change. `runtime-catalog/api-spec/openapi.yaml`
is rewritten by `OpenApiSpecGeneratorTest` and checked in CI; name it first under **For the
reviewer**.

## Gate 4: verify

Re-run every contract criterion, then the module's static checks from `verification.md`. Use the
`maven-verifier` and `npm-verifier` agents for the suites.

- **A green suite is not evidence.** The UI suite runs in `jsdom` and passes identically before
  and after a visual fix. The suite guards against collateral damage; the criteria prove the fix.
- **A new test fails when the fix is reverted.** Revert and watch it go red. Cover the new lines
  and branches here, before Sonar's `new_coverage` gate at 80 does it for you.
- **An existing test that turns red may have pinned the defect.** Run it on `origin/main` and with
  the defect restored; if it is green only with the bug, its expectation moved, and that goes
  under **For the reviewer**.

A criterion not measured on the final code is red, whatever the reason. A busy stack is a
reason to run beside it (`verification.md`) or to wait, never to skip the measurement.

Two attempts at a red criterion, then stop.

## Gate 4.5: review

Spawn read-only reviewers on the green diff, one lens each: **correctness and regression risk**,
**simplification**, **conventions**. Three for a diff under about five files, more for larger
ones. Give each the issue, the contract, the diff, and the worktree path, and nothing of your
own reasoning, which they would only confirm.

Each result arrives as a notification. Wait for the whole round without touching the diff and
without polling; drafting the pull request body meanwhile is fine. Then sort the findings:

- **A reproduced regression** returns the run to gate 4.
- **A finding about the shape** (the codebase does this differently, a flag would replace N
  requests, a helper already exists) means gate 1 got the shape wrong. Go back to gate 1, or
  stop under rule 7. It is never a note for the reviewer.
- **A line that can go** goes, if every criterion stays green without it.
- **Anything else** is reproduced or dropped. Lenses produce confident, specific, wrong claims.

When lenses disagree, the contract and the measurement decide, not a majority.

A second round runs only when a confirmed finding changed code. It reads the delta, reruns the
lens that found it, and adds a fresh lens only for a regression the fix introduced. A blocking
finding in round two means the change is not understood: stop.

## Gate 5: deliver

Commit with a Conventional Commits subject that names the issue, adding only the files the run
touched, push, and open the pull request **ready for review**. The commands, and the ways each
one fails silently, are in `delivery.md`.

The description is not the run report; it has a different reader. **Title:** the symptom in
the reporter's words, naming the screen or the endpoint. **Body** in four short sections:

- **Why**: what a user sees and how they reach it, in plain words, then the before and after
  evidence.
- **What**: a handful of one-line bullets, with the blast radius of any shared component.
- **How to verify**: steps in the running application, then the commands.
- **For the reviewer**: only what needs a decision. A generated file that changed, a test whose
  expectation moved, behavior reaching call sites outside the issue, the shape decision and the
  alternatives it beat.

Move the issue to **In review** on the board, then post the run report on the issue. Before any
later push, confirm the pull request is still open; `delivery.md` says why.

## Gate 6: the checks, before the label

The label waits for CI. Green is the conclusion of every workflow run and every external check
on the head commit, which `scripts/wait-checks.sh` reports (`delivery.md`). A failed job goes
through the `ci-fix` skill, a Sonar finding through `sonar-triage`.

Green: `ai:processed` on the issue and on the pull request, including after a resumed run, which
also removes `ai:needs-human`. Red
after two attempts, or red for a reason outside the change: `ai:needs-human`, naming the check
and the reason.

## Stop rules

Stop and label `ai:needs-human` when any of these is true:

1. the issue lacks `ready-for-agent` and you did not file it on the user's request, or a pull
   request already addresses it;
2. a defect has no criterion a human would recognize;
3. the fix would change a Flyway migration, or change what a JSON schema accepts for documents
   that already exist (a new optional keyword such as `readOnly` is not that);
4. the fix would change a public API contract in a way that breaks an existing caller;
5. the defect does not reproduce;
6. a criterion is still red after two attempts;
7. **the fix requires choosing between defensible shapes**: save-on-blur or discard-on-blur; a
   backend flag or a UI fan-out; an annotation on a shared model, a request type of its own, or
   a mapper setting. The same holds for a fix that **widens a failure**: when a broken setting of
   one chain or connector would fail engine startup, and with it every chain on the pod (eager
   initialization, a constructor call, `@PostConstruct`). Present the options with measurements
   and stop;
8. the defect is not worth what the fix costs: unreachable through the product, years in
   production, or fixable only through a shared model. Say what it would cost and let the owner
   close the issue.

Rules 7 and 8 are the ones an agent talks itself out of: a decision you could defend either way
is not yours to take silently. A claim in a stop report clears the same evidence bar as a fix.

## The run report

The issue comment, and on a stop the only thing the run leaves behind.

1. **Verdict**: the label, the gate reached, and **Assisted**: each decision a person made during
   the run, or "none".
2. **Defects**: the issue split into numbered items.
3. **Contract**: the criteria as written before the code, and the shape decision with its
   alternatives.
4. **Evidence**: images or response bodies first, measurements second.
5. **Change**: files touched, one line each, and why.
6. **Gates**: pass or fail per gate, including the CI checks by name.
7. **Left undone**, in three separate lists: **reproduced by me** (with the command), **read in
   the code, not reproduced**, and **limits of my own change**. Then the exact question for the
   human, and the state of the stack if it differs from how you found it.

Section 7 is mandatory even on a clean run. In chat, not in the report, offer to file each
reproduced item as an issue, after checking that the product reaches it.

## Maintaining this skill

APM-managed. Edit the source under `.apm/skills/fix-issue/`, run `apm install` to refresh the
mirrors under `.claude/` and `.agents/`, then `apm compile` for the `AGENTS.md` files. Add a rule
only with the run that motivated it, and put that story in the commit message, not here.
