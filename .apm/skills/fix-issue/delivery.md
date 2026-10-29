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

Before every push after the first, confirm the pull request is still open and read its
`mergeable_state`; at gate 6, `scripts/wait-checks.sh` reads it and fails on `dirty`. A maintainer
can merge the pull request mid-run, and a push then recreates the deleted branch, after which an
edit changes the merged pull request. On `dirty`, merge `origin/main`, re-run the criteria the
merge can reach, and push:

```bash
gh pr view <PR> -R Netcracker/qubership-integration-platform --json state --jq .state   # OPEN
gh api repos/{owner}/{repo}/pulls/<PR> --jq .mergeable_state   # dirty: merge main
git -C "$WT" fetch origin main && git -C "$WT" merge origin/main
```

`unknown` means GitHub has not computed it yet; ask again a few seconds later. `behind` does not
block the merge, because the `main` ruleset requires no up-to-date branch.

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
gh pr create --base main --head fix/<N>-<slug> --title "<title>" --body-file "$TMP/body.md"   # --draft for a draft
```

To turn an open pull request into a draft, or back, go through GraphQL:

```bash
PR_ID=$(gh pr view <PR> --json id --jq .id)
gh api graphql -f id="$PR_ID" -f query='mutation($id:ID!){convertPullRequestToDraft(input:{pullRequestId:$id}){pullRequest{isDraft}}}'
gh api graphql -f id="$PR_ID" -f query='mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}'
```

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
gh api -X DELETE repos/{owner}/{repo}/issues/<N>/labels/ai%3Aneeds-human    # a resumed run
gh api -X DELETE repos/{owner}/{repo}/issues/<PR>/labels/ai%3Aneeds-human   # and on the pull request
gh api -X PATCH repos/{owner}/{repo}/issues/<N> -F body=@"$TMP/issue.md"     # a finding joins the issue
```

## File an issue

Assign a finding filed on the way to the person who asked for the run, and add `ready-for-agent`
only when they ask for it (an issue filed for the run to work gets it at gate 0). `--label bug`
sets only the label, so set the issue type `Bug` as well, then add the issue to the board with the
`item-add` command under "Move the issue on the board".

```bash
URL=$(gh issue create -R Netcracker/qubership-integration-platform --assignee <requester> --label bug \
  --title "<symptom>" --body-file "$TMP/new-issue.md")
ID=$(gh issue view "$URL" --json id --jq .id)
gh api graphql -f id="$ID" -f query='mutation($id:ID!){updateIssue(input:{id:$id, issueTypeId:"IT_kwDOBQbhhM4A4iql"}){issue{issueType{name}}}}'
```

The token cannot list the organization's issue types; `IT_kwDOBQbhhM4A4iql` is `Bug`.

## Move the issue on the board

The board is project 12 of the `Netcracker` organization. The option is "In review", lowercase
"r"; "In Review" fails with "no changes to make".

```bash
ITEM=$(gh api graphql -F n=<N> -f query='query($n:Int!){repository(owner:"Netcracker",name:"qubership-integration-platform"){issue(number:$n){projectItems(first:10){nodes{id project{number}}}}}}' \
  --jq '.data.repository.issue.projectItems.nodes[] | select(.project.number==12) | .id')
[ -n "$ITEM" ] || ITEM=$(gh project item-add 12 --owner Netcracker \
  --url https://github.com/Netcracker/qubership-integration-platform/issues/<N> --format json --jq .id)
gh project item-edit --project-id PVT_kwDOBQbhhM4Axo_H --id "$ITEM" \
  --field-id PVTSSF_lADOBQbhhM4Axo_HzgnuIZM --single-select-option-id 4cc61d42   # In review
```

The token needs the `project` scope. Check it at gate 0 with `gh auth status`; if the scope is
missing, ask the user to run `gh auth refresh -s project` at the very end, once, and say in the
report that the board was not updated.

## Review comments

```bash
gh api --paginate repos/{owner}/{repo}/pulls/<PR>/comments --jq '.[] | "\(.id)\t\(.user.login)\t\(.path):\(.line)\t\(.body)"'
gh api --paginate repos/{owner}/{repo}/pulls/<PR>/reviews --jq '.[] | "\(.id)\t\(.user.login)\t\(.state)\t\(.body)"'
gh api -X POST repos/{owner}/{repo}/pulls/<PR>/comments/<comment-id>/replies -F body=@"$TMP/reply.md"
gh api -X POST repos/{owner}/{repo}/issues/<PR>/comments -F body=@"$TMP/reply.md"   # a review body has no thread
gh api -X DELETE repos/{owner}/{repo}/issues/<PR>/labels/ai%3Areviewed
```

## Comment the run report

```bash
timeout 120 gh issue comment <N> -R Netcracker/qubership-integration-platform --body-file "$TMP/report.md"
```

## Gate 6: read the checks correctly

`gh pr checks` lists the sub-checks a workflow reports and can miss a job that failed on its own:
it showed 23 green rows while `run-lint` was red. Green is the conclusion of every run on the
head commit, plus every external check such as SonarCloud. `scripts/wait-checks.sh` waits for
both and prints them; run it in the background and act on its exit code:

```bash
bash <this skill's directory>/scripts/wait-checks.sh <PR>   # 0: all green, 1: prints what failed
```

Write no waiter of your own. Hand-written ones exited early in about fifteen runs: a run in progress
has `"conclusion": ""`, and jq's `//` falls back only on null. For a failed job, apply the `ci-fix`
skill: it reads the job's log, routes the failure to its linter or build, and reproduces it.

### Sonar

Apply the `sonar-triage` skill for the project keys, the failed conditions, and the open issues;
read the issues even when the gate passed, because a reviewer reads them too. The conditions that
failed runs of this pipeline:

- `new_coverage` below 80, counted over lines and branches together: a run at 75% had no
  uncovered line, only an untested branch condition.
- `new_maintainability_rating`: cognitive complexity (S3776) of a method the fix grew.
- `new_reliability_rating`: a possible null dereference (S2259) on a real code path. Fix it; mark
  an issue a false positive only with the evidence that the path cannot occur.
- `new_security_rating`: old findings on lines the fix only moved, such as code wrapped in a `try`.

## Clean up

```bash
git -C "$REPO" worktree remove "$WT"
```

Delete the seeded entities, stop only what you started, restore any container you rebuilt, and
record in the report anything about the stack that is not as you found it.
