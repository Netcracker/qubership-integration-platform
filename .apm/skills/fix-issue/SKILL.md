---
name: fix-issue
description: Take a GitHub issue labeled ready-for-agent, fix it end to end against the running local stack, and deliver a branch, a pull request, an evidence report, and an autonomy label. Works for any module (ui, runtime-catalog, engine, vscode-extension, schemas, infrastructure). Use when asked to work an issue by number or URL, or to run the autonomous issue pipeline.
---

# Working a GitHub issue end-to-end

Gates 0 through 6, in order, with a review gate between 4 and 5. Each gate either stops the run
or hands off to the next one. The label you apply at the end is a function of the gate you
reached, never of how the work felt.

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

Stop at any gate: `ai:needs-human`, full report on the issue, then offer in chat to finish the
work together. Carry the pull request to green checks at gate 6: `ai:processed`.

Run silently. Ask nothing until the run ends, so the label reports an unassisted attempt. The
run ends with a report and a label even when a person stepped in halfway: a resumed run, a
reworked fix, or an issue you created a minute ago all close the same way. Two runs closed
without a report or a label because a person had intervened, and the metric lost both.

Three companion files hold the details this file only names:

- `verification.md`: how to reproduce and verify per module, and the traps of each stack.
- `delivery.md`: the exact `gh`, GitHub API, Sonar, and board commands, with their known failures.
- `stack.md`: bringing the stack up, worktrees, Docker Desktop, and what each symptom means.

## The labels

`ai:processed` counts your confidence. `human:ai-failed` is applied by a person, never by you,
and only to an issue you already marked `ai:processed`: the pull request was reviewed and the
delivered work does not do the job. `ai:processed` minus `human:ai-failed` counts results. It
is the only external signal in the scheme, so applying it to your own work would destroy the
one number that means anything.

If the issue carries `human:ai-failed`, read the review comments on the earlier pull request
first and carry every one of them into the contract as a hard constraint.

## Gate 0: intake

```bash
gh issue view <N> --json number,title,body,labels,state
gh pr list --state open --search "<N>" --json number,headRefName
gh auth status                                  # needs the project scope for the board
```

Stop unless the issue carries `ready-for-agent` and no open pull request references it. An
issue you filed yourself on the user's request gets `ready-for-agent` from you at creation, so
the run that follows is a real run and not an exception to rule 1.

Never touch the user's working tree. It is dirty more often than not, and another run may be
using it: a `git stash` issued from one run vanished under another, and a "before" measurement
was taken on fixed code twice. Work in a worktree on a fresh branch from `origin/main`:

```bash
REPO=/home/dmitrii/IdeaProjects/qubership-integration-platform
git -C "$REPO" fetch origin main
WT=/home/dmitrii/qip-wt-<N>                     # under $HOME: Docker cannot mount /tmp
git -C "$REPO" worktree add -b fix/<N>-<slug> "$WT" origin/main
```

Remove the worktree at the end of the run; five abandoned ones are sitting under old
scratchpads. Every command runs against `$WT` by absolute path or as `git -C "$WT"`. The shell
working directory persists between commands, so a bare `cd` in one command leaves the next one
running somewhere else. This broke `sed`, `ls`, and `git` alike.

Check which other `fix/*` branches are open and whether they touch the same files. Two runs
in one evening produced pull requests that conflicted on one file, and neither said so.

Download every attachment on the issue before you write the contract. The reporter's screenshot
showed the tree depth and the blank control that the run then reproduced; without it the run
had measured 29 nodes and concluded "does not reproduce".

```bash
curl -sSL -H "Authorization: Bearer $(gh auth token)" -o "$TMP/issue.png" \
  "https://github.com/user-attachments/assets/<uuid>"
```

## Gate 1: write the contract before touching code

Split the issue into numbered defects. For each one record where it lives, which module owns
it, and **how you will prove it fixed**. Write this down before the first edit. An agent that
writes code first will rationalize whatever it produced; an agent bound to a falsifiable
criterion cannot.

- **State the criterion in the reporter's words, not in your metric.** "Labels are too small"
  became "font-size is equal", and the criterion passed while the reporter still saw it.
- **For a visual defect the criterion is the before/after image.** For an API defect it is the
  response, byte for byte, before and after. For a compiled artifact (Camel XML, a Helm render,
  a bundle) it is the diff of the artifact.
- **Never write an absolute target you inferred from reading code.** "Must become 14px" is a
  guess that outranks the measurement you have not taken yet. Write "must equal the edit state".
- **Walk the whole interaction, not the named symptom.** Enumerate every entry and exit, every
  caller of the endpoint, both themes.
- **A thin issue gets a precedent, not a guess.** An empty body that asks for ordering is
  answered by how the five other lists in the product order, quoted in the contract.

### Decide the shape of the fix here, not in the diff

Name the layer and the form before writing code, and put the reasoning in the report. Every
question a reviewer asked after a run was about this: "why the backend and not the frontend, we
do these on the frontend", "what problem does the new DTO solve", "was there another way besides
the annotation", "is this worth fixing at all". Each one cost more time than the run itself.

Fix where the fix is correct. A change may span modules when the right fix lives in two places:
a backend flag plus the UI that reads it beats a UI fan-out of N requests, and two lenses said
so an hour before the reviewer rejected the fan-out. Reach for the pattern the codebase already
uses for the same need before inventing one.

Stop under **rule 7** when more than one defensible shape exists and the choice is a product
call. The tell is a new type, mapper, shared handler, or shared configuration that the contract
does not require: write the table of options with what each one breaks, and stop.

Stop under **rule 8** when the defect is not worth fixing: unreachable from the product,
years in production, and fixable only by touching a shared model. Say so with the evidence,
and let the owner close it.

## Gate 2: reproduce, and measure a healthy peer alongside

Nothing is fixed until it has been seen broken. Bring up the stack as `stack.md` says, seed
through the API, and capture the "before" state now, before any edit. Recovering it later
costs a stash round trip that has produced a fake baseline twice.

Choose the reproduction from `verification.md` by module:

| Module | Reproduction | Healthy peer |
|---|---|---|
| ui | Playwright from a scratch directory, screenshot of the element, both themes | a neighboring control the issue does not name |
| runtime-catalog, sessions-management | request matrix before/after with `diff`; container log gated on level | the sibling endpoint on the same mapper |
| engine, micro-engine | compiled Camel XML from `deployments/update`, two variants in one chain | the element variant that works |
| vscode-extension | real exports run through the code under test; Jest as reproduction | the protocol or shape that works |
| schemas | `ajv` against real documents from the catalog | a sibling schema |
| infrastructure | throwaway container beside the stack, `helm template` before/after | the neighboring service |

**Look at the pixels, not only at the properties.** `opacity`, `visibility`, and `color` read
normal on an element something else is painting over. Issue #671 measured all three on 29
nodes in both themes and reported "does not reproduce"; one screenshot of the control showed
a blank square, which is what the reporter had attached.

**A negative conclusion needs the same evidence as a positive one.** "Does not reproduce" is a
claim about what the screen or the response shows, so it takes a picture or a body, not a
property.

Seed with a unique run token and delete it when the run ends. Stop only what you started.

## Gate 3: fix

Change the least that satisfies the contract, in the shape gate 1 decided. Before you move on,
walk the diff and attach every line to a criterion number. A line that belongs to none is
removed now, not defended later. The runs that were cut down afterwards all had lines of this
kind: a `resolver_timeout` the report itself called unmotivated, a probe parameter equal to its
default, a seven-row table derivable from one exception, a request DTO the contract never
asked for.

Three arguments never justify a line:

- **parity**: "the Helm chart has it", "the sibling service has it";
- **analogy**: "postgres and opensearch set the same value";
- **foresight**: "a future caller might need it".

The working agreements already say this for guards, caches, and limits. It applies to every
line, including configuration and test helpers.

A generated file in the diff is a public contract change: `runtime-catalog/api-spec/openapi.yaml`
is rewritten by `OpenApiSpecGeneratorTest` and checked in CI. Name it in the pull request
under **For the reviewer**, first line.

## Gate 4: verify

Re-run every contract criterion, then the static checks for the module from
`verification.md`. Use the `maven-verifier` and `npm-verifier` agents for the suites; they know
the flags, the timeouts, and the working-directory traps that cost earlier runs their time.

**A green test suite is not evidence.** The UI suite runs in `jsdom` and passed identically
before and after a fix; a Maven build piped through `grep` swallowed its own failure and left
the old jar in the container. The suite guards against collateral damage; the contract
criteria are the only proof of the fix.

**A new test must fail when the fix is reverted.** Assert it by actually reverting. A blur
test passed against broken code because antd validates asynchronously; two mutations of a
`readOnly` fix left 140 tests green. Cover the new lines here, not after Sonar complains: its
`new_coverage` condition at 80 catches every behavior fix whose lines no suite reaches.

An existing test that fails after the fix may have been pinning the defect. Run it on clean
`origin/main` and again with the defect restored; if it is green only with the bug, the test
moved, and it goes under **For the reviewer**.

Two attempts at a red criterion, then stop. A third attempt is a spiral, not a fix.

## Gate 4.5: review

Spawn parallel reviewers on the green diff, one lens each: **correctness and regression risk**,
**simplification**, **conventions**. Three for a diff under about five files; more only for
larger ones. Give each the issue, the contract, the diff, and the worktree path. Never give
them your own reasoning, or they will confirm the story you told them.

Lenses are read-only. Two of them edited the working tree to try an alternative and left it
changed; one read the user's checkout instead of the worktree and cited a file that exists only
on the user's branch.

**Wait without doing anything.** The result of each lens arrives as a notification. A run spent
an hour in `until [ -f /tmp/nonexistent ]` loops, another polled the agent list six times, a
third armed timers that woke the session four times after the work was over. Draft the pull
request body meanwhile if you must, but do not touch the diff and do not poll.

**Let the round finish before you change anything.** Two of three lenses once came back
describing a file that had been deleted under them.

Then sort the findings:

- **A reproduced regression** returns the run to gate 4.
- **A finding about the shape of the fix** (the codebase does this differently, a flag would
  replace N requests, the table is derivable) is not "adjacent". Two lenses agreeing on it
  means gate 1 got the shape wrong: go back to gate 1, or stop under rule 7 if the choice is
  not yours. Filing it as a note is how the fix for #719 reached a reviewer and came back.
- **A finding that a line can go** is accepted if every criterion stays green without the
  line. It needs no reproduction; it is gate 3 applied by someone else.
- **Anything else** is reproduced or dropped. Two of three lenses once produced confident,
  specific, wrong claims, with citations to a file that said the opposite.

When lenses disagree, the contract and the measurement decide. Not a majority.

A second round is earned by exactly one thing: a confirmed finding changed code. It reads the
delta only, runs the lens whose finding was confirmed, and adds one fresh lens only when the
confirmed finding was a regression the fix itself introduced. A delta that is only comments or
prose gets no second round. Two rounds is the ceiling: a blocking finding in round two means
the change is not understood well enough to deliver, so stop with `ai:needs-human`.

## Gate 5: deliver

Commit with a Conventional Commits subject that names the issue, push, and open the pull
request **ready for review, not as a draft**. Before committing, run `git status` in the
worktree and add only the files the run touched; a foreign edit appeared in the tree mid-run
once. The commands, and the ways each one fails silently, are in `delivery.md`.

The description is not the run report. They have different readers. **Title:** the symptom in
the reporter's words, naming the screen or the endpoint. **Body** in four short sections:

- **Why** opens with what a user sees and how they reach it, in language that needs no
  knowledge of the tool involved. A reviewer asked three times to have a DNS caching fix
  explained "shorter and in plain words". Then the symptom, then the before and after evidence.
- **What** is a handful of one-line bullets, with the blast radius of any shared component.
- **How to verify** is steps in the running application, then the commands.
- **For the reviewer** carries only what needs a decision: a generated file that changed, a
  test whose expectation moved, a behavior reaching call sites outside the issue, the shape
  decision from gate 1 and the alternatives it beat.

Label the pull request as well as the issue, including a run resumed after a stop; two pull
requests from resumed runs carry no label and are invisible in the list. Move the issue to
**In Review** on the board. Then comment the run report on the issue.

## Gate 6: the checks, before the label

A pull request is not delivered while CI is red, so the label waits for the checks. Green means
the conclusion of every workflow run on the head commit, not the check list: `gh pr checks`
showed 23 passing sub-checks and hid a failed lint job, and the run announced "all green" until
the reviewer pointed at the red run. The commands are in `delivery.md`.

`super-linter` reaches past the module you touched and lints new files by their own rules:
a new shell script failed on `shfmt`. Read the job that failed, not the workflow name.

Reach green: `ai:processed`. Red after two attempts, or red for a reason outside the change:
`ai:needs-human`, and say which check and why.

## Stop rules

Stop and label `ai:needs-human` when any of these is true:

1. the issue lacks `ready-for-agent` and you did not file it on the user's request, or a pull
   request already addresses it;
2. a defect has no criterion a human would recognize;
3. the fix would change a Flyway migration, or change what a JSON schema accepts for documents
   that already exist (a new optional keyword such as `readOnly` on a field is not that);
4. the fix would change a public API contract in a way that breaks an existing caller;
5. the defect does not reproduce;
6. a criterion is still red after two attempts;
7. **the fix requires choosing between two defensible shapes**: save-on-blur versus
   discard-on-blur; a flag on the backend versus a fan-out in the UI; an annotation on a shared
   model versus a request type of its own versus a mapper setting. Implementing one is easy;
   choosing is the owner's call. Present the trade-off with measurements and stop;
8. the defect is not worth fixing at the cost the fix requires. Say what it would cost and let
   the owner close the issue.

Rules 7 and 8 are the ones an agent talks itself out of. A decision you could defend either way
is not yours to take silently. Every claim in a stop report clears the same evidence bar as a
fix: a stop once rested on "the VS Code extension renders its own dialog", and the extension
renders the UI package's dialog.

## The run report

This is the record of how the run went, and it goes in the issue comment. On a stop there is no
pull request, so this is the only thing you leave behind.

1. **Verdict**: the label and the gate reached.
2. **Defects**: the issue split into numbered items.
3. **Contract**: the criteria, as written before the code, and the shape decision with its
   alternatives.
4. **Evidence**: images or response bodies first, measurements second.
5. **Change**: files touched, one line each, and why.
6. **Gates**: pass or fail per gate, including the CI checks by name.
7. **Left undone**, in three lists that are never merged: **reproduced by me** (with the
   command), **read in the code, not reproduced**, and **limits of my own change**. A flat list
   of six "pre-existing defects" drew "are you sure these are bugs?", and two of the six were
   not. Then the exact question for the human, and the state you left the stack in if it
   differs from how you found it.

Section 7 is mandatory even on a clean run. A report without it is advertising. Offer, in the
chat and not in the report, to file each reproduced item as an issue; the user has asked for
that after every run that had one.

## Maintaining this skill

APM-managed. Edit the source under `.apm/skills/fix-issue/`, run `apm install` to refresh the
mirrors under `.claude/` and `.agents/`, then `apm compile` for the `AGENTS.md` files. Do not
hand-edit the mirrored copies. Add a rule only with the run that motivated it; the rules above
each cost at least one.
