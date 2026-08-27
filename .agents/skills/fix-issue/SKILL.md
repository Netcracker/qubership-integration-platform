---
name: fix-issue
description: Take a GitHub issue labeled ready-for-agent, fix it end to end against the running local stack, and deliver a branch, a draft pull request, an evidence report, and an autonomy label. Use when asked to work an issue by number or URL, or to run the autonomous issue pipeline.
---

# Working a GitHub issue end to end

Gates 0 through 6, in order, with a review gate between 4 and 5. Each gate either stops the run
or hands off to the next one. The label you apply at the end is a function of the gate you
reached, never of how the work felt.

| Gate | Name | Stopping here means |
|---|---|---|
| 0 | Intake | the issue or the tree is not ready |
| 1 | Contract | no criterion a human would recognize |
| 2 | Reproduce | the defect does not appear |
| 3 | Fix | the change would break a stop rule |
| 4 | Verify | a criterion or a static check is red |
| 4.5 | Review | a reproduced regression, which returns the run to gate 4 |
| 5 | Deliver | the branch, the pull request, or the issue link fails to write |
| 6 | Green checks | a required check stays red |

Stop at any gate: `ai:needs-human`, full report on the issue, then offer in chat to finish the
work together. Carry the pull request to green checks at gate 6: `ai:processed`.

Run silently. Ask nothing until the run ends, so the label reports an unassisted attempt.

`human:ai-failed` is applied by a person, never by you, and only to an issue you already marked
`ai:processed`. It means the pull request was reviewed and the delivered work does not do the
job: wrong approach, wrong defect fixed, something adjacent broken. Ordinary review comments on
a sound change are not that, and an issue you stopped on with `ai:needs-human` never earns it
either, since a correct refusal is not a failure.

It is the only external signal in the scheme, so applying it to your own work would destroy the
one number that means anything. `ai:processed` counts your confidence; `ai:processed` minus
`human:ai-failed` counts results.

## Gate 0: intake

```bash
gh issue view <N> --json number,title,body,labels,state
gh pr list --state open --search "<N>" --json number,headRefName
git status --porcelain && git branch --show-current
```

Stop unless all of these hold: the issue carries `ready-for-agent`, no open pull request
references it, and the working tree is clean. Branch from `main`, named `fix/<N>-<slug>`.

If the issue carries `human:ai-failed`, read the review comments on the earlier pull request
first and carry every one of them into the contract as a hard constraint. A previous failure is
input, not just a statistic.

## Gate 1: write the contract before touching code

Split the issue into numbered defects. For each one, record where it lives and **how you will
prove it fixed**. Write this down before the first edit. An agent that writes code first will
rationalize whatever it produced; an agent bound to a falsifiable criterion cannot.

Four rules that exist because they were violated:

- **State the criterion in the reporter's words, not in your metric.** "Labels are too small"
  became "font-size is equal", and the criterion passed while the reporter still saw it,
  because the eye reads the box, not the type size. Ask what the reporter would look at.
- **For a visual defect, the criterion is the before/after image.** Numbers go in the report as
  supporting detail. The picture closes acceptance.
- **Never write an absolute target you inferred from reading code.** "Must become 14px" is a
  guess that outranks the measurement you have not taken yet. Write "must equal the edit state".
- **Walk the whole interaction, not the named symptom.** The reporter reached the feature
  through normal use, so enumerate every entry and exit (commit, click away, Escape, cancel)
  and both themes. Adjacent defects the issue never mentioned belong in the report even when
  you do not fix them.

## Gate 2: reproduce, and measure a healthy peer alongside

Nothing is fixed until it has been seen broken.

### Bring the stack up

```bash
docker compose -f infrastructure/docker-compose.yml up -d
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8091/actuator/health
nohup npm -w @netcracker/qip-ui run dev > /tmp/vite.log 2>&1 &
```

- Drive the UI through **nginx on 8080**, never Vite on 4200: it serves no data.
- 8080 answering 502 means Vite is down, not that the app is broken.
- `npm run dev` fetches documentation over the network before Vite starts; the first run is slow.
- `vite` is hoisted to the repo-root `node_modules/.bin`, not `ui/node_modules/.bin`.
- The shell working directory persists between commands. Use absolute paths everywhere. A
  `git checkout` issued from a scratch directory fails quietly, and the "before" frame then
  captures the fixed code without anything looking wrong.
- Stop only what you started. Leave containers that were already running.

### Seed through the API, shaped by the OpenAPI document

All three services publish a live spec. Query it rather than reading it whole: it is 234 KB.

```bash
TMP=$(mktemp -d)                    # scratch for this run; nothing here enters the repo
curl -s http://localhost:8091/v3/api-docs > "$TMP/openapi.json"
jq -r '.paths | keys[] | select(test("snapshots"))' "$TMP/openapi.json"
jq -r '.components.schemas.SnapshotRequest.properties | keys' "$TMP/openapi.json"
```

The spec is authoritative on **shape**: paths, field names, request bodies. It is silent on
**behavior**: it will not tell you that omitting `labels` throws an NPE. That knowledge lives in
the `runtime-catalog-api-testing` skill. Order of consultation: spec, then that skill, then the
Java source.

Prefix every seeded entity with a unique run token and delete it when the run ends.

### Capture the evidence

Use Playwright from a scratch directory. Never add it to `ui/package.json`: the workspace has
no e2e infrastructure and no CI job to run one, so a browser download would be imposed on the
whole team for a tool only this pipeline uses.

```bash
mkdir -p "$TMP/e2e" && cd "$TMP/e2e" && npm init -y && npm i playwright
npx playwright install chromium     # ~115 MB unless the cached build already matches
```

The cached browser revision often lags the Playwright package, so budget the download or pin
the package to the revision already in `~/.cache/ms-playwright`.

Capture the "before" state **now**, before any edit. Recovering it later costs a `git stash`
round trip.

Two traps that cost real time:

- **Anchor locators on something the state change cannot remove.** A row filtered by
  `hasText: <name>` stops matching the moment the name becomes an input, and the failure looks
  exactly like a broken application.
- **antd v6 has no `.ant-select-selector`.** The padding lives on `.ant-select` itself. Read the
  DOM before writing a selector rather than reusing v5 habits.

### Measure a healthy peer

For a layout or styling defect, measure an element that is **not** part of the complaint and use
it as the reference line. A neighboring non-editable table column revealed that editable columns
sit 12 px right of their own headers, a fact invisible when you only measure the broken cell
against itself.

### Look at the pixels, not only at the properties

A visual defect can leave every property correct. `opacity`, `visibility`, `color` and `display`
all read normal on an element that something else is painting over, because none of them knows
about paint order. Reading them and concluding "not reproducible" is how a real defect gets
reported as absent.

So screenshot the element itself before you conclude anything, and look at the picture:

```js
await locator.screenshot({ path: "control.png" });   // crops away everything else
// with deviceScaleFactor: 4 on the page, a 24px control is legible
```

Issue #671 is the case. The arrow reported as vanishing under the pointer measured
`opacity: 1`, `visibility: visible`, and an unchanged colour, on all 29 nodes of the tree, in
both themes. The run reported it as not reproducing. The arrow was being covered by the hover
fill antd paints into the switcher, which this theme had made opaque. One screenshot of that
one control showed a blank square, which is exactly what the reporter had attached.

**A negative conclusion needs the same evidence as a positive one.** "Does not reproduce" on a
visual defect is a claim about what the screen shows, so it takes a picture, not a property.

## Gate 3: fix

Change the least that satisfies the contract. Add no guard, cache, or limit that no observed
failure motivates. If a call site count is large, say so in the report instead of narrowing the
fix to one page.

## Gate 4: verify

Re-run every contract criterion, then:

```bash
cd ui && npx tsc --noEmit && npx eslint src/ && npx prettier --check "src/**/*.{ts,tsx,css}" && npx jest --coverage=false
docker logs qip-runtime-catalog --since 10m 2>&1 | grep -E '^\[[^]]+\] \[ERROR\]'
```

`eslint` must run with `ui` as the working directory. Match the log level field, not the word
"error": every line carries an `error_code=` field, so a naive grep matches almost everything.

**A green test suite is not evidence here.** The UI suite passed identically before and after a
change that fixed two reported defects: it runs in `jsdom`, which has no layout engine. The suite
guards against collateral damage; the contract criteria are the only proof of the fix.

Two attempts at a red criterion, then stop. A third attempt is a spiral, not a fix.

## Gate 4.5: review

Spawn parallel reviewers on the green diff, one lens each: **correctness and regression risk**,
**simplification**, **conventions**. Three for a diff under about five files; more only for
larger ones. Give each the issue, the contract, and the diff. Never give them your own
reasoning, or they will confirm the story you told them.

Then apply the rule that matters most:

> **A review finding must clear the same evidence bar as the fix. Reproduce it or drop it.**

In a real run, two of three lenses produced confident, specific, wrong claims: a regression that
could not be reproduced once the interaction was actually driven, a suggested value that would
have failed the contract, and three citations to a sibling file that said the opposite of what
was claimed. Acting on any of them would have added code for a defect that does not exist.

When lenses disagree, the contract and the measurement decide. Not a majority.

A blocking finding, meaning a reproduced regression, goes back to gate 4. Everything else goes
into the report as a note. Do not widen the change because a reviewer found something adjacent.

## Gate 5: deliver

Commit with a Conventional Commits subject referencing the issue, push the branch, open a
**draft** pull request.

### The description is not the run report

They have different readers. The run report says how you worked; the description says what a
person will see and whether to merge. Writing one and calling it the other produces a page full
of your own working notes.

**Title:** the symptom in the reporter's words, naming the screen. Not the mechanism you
changed. `fix(ui): snapshot rename looks unsaved and labels render too small` tells a reviewer
where to look; `fix(ui): close the inline editor on commit` tells them nothing.

**Body**, four short sections:

- **Why** opens with the screen, in a sentence anyone can follow: which page, which control, how
  a user reaches it. Then the symptom, then the before and after images. This is the section
  that earns the reviewer's attention, so it comes first and it stays in plain language.
- **What** is a handful of one-line bullets. Name the blast radius if a shared component changed.
- **How to verify** is steps in the running application, then the commands. A reviewer who
  cannot repeat it by hand will not check it at all.
- **For the reviewer** carries only what needs a decision: a test whose expectation moved, a
  behavior change reaching call sites outside the issue.

Leave out the investigation. Coordinates, API field names, the class antd renamed, the query
that turned out to be wrong: all of it earned the fix and none of it helps someone decide to
merge. It belongs in the run report.

Attach the before/after images through GitHub's attachment endpoint:

```bash
RID=$(gh api repos/{owner}/{repo} --jq .id)
curl -sS -X POST -H "Authorization: Bearer $(gh auth token)" -H "Accept: application/json" \
  --data-binary "@before.png" \
  "https://uploads.github.com/user-attachments/assets?name=before.png&content_type=image/png&repository_id=$RID"
```

It returns `{"url": "https://github.com/user-attachments/assets/<uuid>"}` for a markdown image
tag. The endpoint is undocumented, so treat a failure as expected: fall back to the numbers and
the reproduction script, and say in the report that images could not be attached.

### Link the issue in the description, not only in the commit

`pr-linked-issue` reads GitHub's `closingIssuesReferences`, which a keyword in a **commit
message never fills**. Put `Closes #<N>` in the pull request body, or name the issue in the title
as `fix: #<N> ...` or `... (#<N>)` and let the workflow write the keyword for you.

`gh pr edit --body-file` can report success and change nothing. Read the body back, and fall back
to the API:

```bash
gh pr edit <PR> --body-file body.md
gh pr view <PR> --json body --jq '.body' | tail -3          # confirm, do not assume
gh api -X PATCH repos/{owner}/{repo}/pulls/<PR> -f body="$(cat body.md)"
gh api graphql -f query='query{repository(owner:"O",name:"R"){pullRequest(number:<PR>){closingIssuesReferences(first:1){totalCount}}}}'
```

Once the issue is linked, move it to **In Review** on the board. That needs a token carrying the
`project` scope; `gh auth refresh -s project` grants it. Without the scope the query fails with
`INSUFFICIENT_SCOPES`. Report that you could not move it rather than passing over it in silence.

Then comment the short report on the issue and link the pull request.

## Gate 6: the checks, before the label

A pull request is not delivered while CI is red, so the label waits for the checks.

```bash
gh pr checks <PR> --watch
gh run view <run-id> --log-failed | tail -40
```

**Sonar reports a status, not a reason.** Ask the API which condition failed:

```bash
curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=<key>&pullRequest=<PR>" \
  | jq '.projectStatus.conditions[] | select(.status=="ERROR")'
```

The condition that catches this pipeline is `new_coverage`, threshold 80. A UI behavior fix adds
lines that no jsdom suite reaches, so the gate fails on a correct change. Cover the new lines with
a unit test rather than arguing with the gate, and hold that test to the bar below.

> **A new test must fail when the fix is reverted.** Assert it by actually reverting.

A blur test written for this pipeline passed against deliberately broken code, because antd
validates asynchronously and the synchronous `expect(...).not.toHaveBeenCalled()` ran before the
submit could happen. It was green by coincidence. Two mutations (restore the defect, drop the
guard) are cheap, and they are the only thing that separates a test from a decoration.

`super-linter` reaches past the module you touched: CSS, EditorConfig, gitleaks, and Trivy all
report separately. Read the job that failed, not the workflow name.

Reach green: `ai:processed`. Red after two attempts, or red for a reason outside the change:
`ai:needs-human`, and say which check and why.

## Stop rules

Stop and label `ai:needs-human` when any of these is true:

1. the issue lacks `ready-for-agent`, or a pull request already addresses it;
2. a defect has no criterion a human would recognize;
3. the fix would touch a Flyway migration, a JSON schema, or a public API contract;
4. the fix spans more than one module (`ui` plus `schemas` counts as one);
5. the defect does not reproduce;
6. a criterion is still red after two attempts;
7. **the fix requires choosing between two defensible behaviors**: save-on-blur versus
   discard-on-blur, alignment to the header versus to the editor. Implementing both is easy;
   choosing is the product owner's call. Present the trade-off with measurements and stop.

Rule 7 is the one an agent talks itself out of. A decision you could defend either way is not
yours to take silently.

## The run report

This is the record of how the run went, and it goes in the issue comment. It is not the pull
request description above, which is written for a reviewer. On a stop there is no pull request,
so this is the only thing you leave behind.

1. **Verdict**: the label and the gate reached.
2. **Defects**: the issue split into numbered items.
3. **Contract**: the criteria, as written before the code.
4. **Evidence**: before/after images first, measurements second.
5. **Change**: files touched, one line each, and why.
6. **Gates**: pass or fail per gate, including the CI checks by name.
7. **Left undone**: remainder, adjacent findings, unverified review claims marked as such, and
   the exact question for the human.

Section 7 is mandatory even on a clean run. A report without it is advertising.

## Maintaining this skill

APM-managed. Edit the source under `.apm/skills/fix-issue/`, run `apm install` to refresh the
mirrors under `.claude/` and `.agents/`, then `apm compile` for the `AGENTS.md` files. Do not
hand-edit the mirrored copies.
