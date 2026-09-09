# Delivering: branch, pull request, labels, board, checks

Companion to `SKILL.md`, gates 5 and 6. Most of the `gh` commands below have failed silently in
a run; the verification after each one is the point.

Wrap every network call in `timeout`. A `gh issue comment` hung for seven minutes and an image
upload for 133 seconds; the run cannot tell a slow call from a dead one.

## Commit and push

```bash
git -C "$WT" status --porcelain          # only files the run touched
git -C "$WT" add <files>
git -C "$WT" commit -m "fix(<module>): <symptom in the reporter's words> (#<N>)"
git -C "$WT" push -u origin fix/<N>-<slug>
git -C "$WT" ls-remote origin fix/<N>-<slug>   # compare with git -C "$WT" rev-parse HEAD
```

A push during a network fault returned success and moved nothing; the `ls-remote` is the check.

## Attach images

GitHub's attachment endpoint is undocumented, so treat a failure as expected: fall back to the
numbers and the reproduction script, and say in the report that images could not be attached.

```bash
RID=$(gh api repos/{owner}/{repo} --jq .id)
timeout 60 curl -sS -X POST -H "Authorization: Bearer $(gh auth token)" -H "Accept: application/json" \
  --data-binary "@before.png" \
  "https://uploads.github.com/user-attachments/assets?name=before.png&content_type=image/png&repository_id=$RID"
```

It returns `{"url": "https://github.com/user-attachments/assets/<uuid>"}` for a Markdown image
tag.

## Open the pull request

```bash
gh pr create --base main --head fix/<N>-<slug> --title "<title>" --body-file "$TMP/body.md"
```

Ready for review, not a draft: gate 6 already holds the run until the checks are green, and a
draft asks nobody to look.

`Closes #<N>` goes in the body. `pr-linked-issue` reads GitHub's `closingIssuesReferences`,
which a keyword in a commit message never fills. Confirm:

```bash
gh api graphql -f query='query{repository(owner:"Netcracker",name:"qubership-integration-platform"){pullRequest(number:<PR>){closingIssuesReferences(first:1){totalCount}}}}'
```

## Edit the pull request: REST, not `gh pr edit`

`gh pr edit` is unreliable in this repository in every form. `--body-file` reports success and
changes nothing; `--add-label` and `--body` fail with a GraphQL error about Projects classic.
Go through REST from the start, and read back:

```bash
gh api -X PATCH repos/{owner}/{repo}/pulls/<PR> -F body=@"$TMP/body.md"
gh pr view <PR> --json body --jq '.body' | tail -3
gh api -X POST repos/{owner}/{repo}/issues/<PR>/labels -f "labels[]=ai:processed"
gh api -X POST repos/{owner}/{repo}/issues/<N>/labels -f "labels[]=ai:processed"
```

Label the pull request on every run that produces one, including a run resumed after a stop.

## Move the issue on the board

The board needs a token with the `project` scope. Check it at gate 0 with `gh auth status`; if
the scope is missing, ask the user to run `gh auth refresh -s project` at the very end, once, and
say in the report that the board was not updated. Five consecutive reports repeated the same
"could not move" line because nobody asked.

## Comment the run report

```bash
timeout 120 gh issue comment <N> --body-file "$TMP/report.md"
```

## Gate 6: read the checks correctly

`gh pr checks` lists the sub-checks a workflow reports and can miss a job that failed on its own:
it showed 23 green rows while `run-lint` was red. Green is the conclusion of every run on the
head commit. This `gh` version has no `--json` on `pr checks`; a monitor built on it stayed
silent for 30 minutes.

```bash
SHA=$(git -C "$WT" rev-parse HEAD)
gh run list --commit "$SHA" --json name,status,conclusion --jq '.[] | "\(.conclusion // .status)\t\(.name)"'
gh pr view <PR> --json statusCheckRollup --jq '.statusCheckRollup[] | select(.conclusion != "SUCCESS" and .conclusion != "SKIPPED" and .conclusion != null) | "\(.conclusion)\t\(.name // .context)"'
gh run view <run-id> --log-failed | tail -40
```

Both lists must be free of `failure` before the label. Wait with a background command that
exits when every run has a conclusion; do not poll by hand.

### Sonar

Sonar reports a status, not a reason. Ask the API which condition failed:

```bash
curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=<key>&pullRequest=<PR>" \
  | jq '.projectStatus.conditions[] | select(.status=="ERROR")'
```

The condition that catches this pipeline is `new_coverage`, threshold 80. Gate 4 already
covered the new lines with a test that fails when the fix is reverted; if the gate still fails,
the uncovered lines are cosmetics in a touched file, and the answer is to remove them.

### super-linter

It reaches past the module you touched: CSS, EditorConfig, gitleaks, Trivy, `shfmt`,
`shellcheck`, and `yamllint` all report separately, and a new file is linted by the rules for
its type. Read the job that failed, not the workflow name.

## Clean up

```bash
git -C "$REPO" worktree remove "$WT"
```

Delete the seeded entities, remove any temporary Maven artifacts from `~/.m2`, restore any
container you rebuilt, and record in the report anything about the stack that is not as you
found it.
