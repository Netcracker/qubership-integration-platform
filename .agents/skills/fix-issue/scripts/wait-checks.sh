#!/usr/bin/env bash
# Waits until every workflow run and every check on the head commit of a pull request is
# finished, then prints the result. Exits 0 when everything passed, 1 when anything failed.
# Usage: wait-checks.sh <pr-number>    (run it in the background)
set -euo pipefail

PR=${1:?usage: wait-checks.sh <pr-number>}
REPO=Netcracker/qubership-integration-platform
INTERVAL=${INTERVAL:-30}

SHA=$(gh pr view "$PR" -R "$REPO" --json headRefOid --jq .headRefOid)

# A run in progress has "conclusion": "", not null, so pending is read from the status fields.
# A workflow run again on the same commit replaces its earlier run, so keep the latest per name.
# shellcheck disable=SC2016 # $r and $c are jq variables
STATE='
    def ok: ((.conclusion // .state // "") | ascii_upcase) as $s
        | ["SUCCESS", "SKIPPED", "NEUTRAL"] | index($s) != null;
    ($r | group_by(.name) | map(max_by(.databaseId))) as $r
    | {
        total: ($r | length),
        pending: ([$r[] | select(.status != "completed")]
            + [$c[] | select((.status // "COMPLETED") != "COMPLETED" or .state == "PENDING")] | length),
        runs: [$r[] | "\(.conclusion)\t\(.name)\t\(.databaseId)"],
        failed: ([($r[], $c[]) | select(ok | not) | (.name // .context)] | unique)
    }'

last=-1
while :; do
    R=$(gh run list -R "$REPO" --commit "$SHA" --limit 100 --json name,status,conclusion,databaseId)
    C=$(gh pr view "$PR" -R "$REPO" --json statusCheckRollup --jq .statusCheckRollup)
    S=$(jq -n --argjson r "$R" --argjson c "$C" "$STATE")
    read -r total pending < <(jq -r '"\(.total) \(.pending)"' <<< "$S")
    # Workflows triggered by other workflows start late: require two quiet polls in a row
    # with the same number of runs before calling the commit finished.
    if [ "$total" -gt 0 ] && [ "$pending" -eq 0 ]; then
        [ "$total" -eq "$last" ] && break
        last=$total
    else
        last=-1
    fi
    sleep "$INTERVAL"
done

echo "head $SHA"
jq -r '.runs[]' <<< "$S"
if [ "$(jq '.failed | length' <<< "$S")" -gt 0 ]; then
    echo "FAILED:"
    jq -r '.failed[]' <<< "$S"
    exit 1
fi
echo "ALL GREEN"
