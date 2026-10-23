#!/usr/bin/env bash
#
# Releases from main require the latest Snapshot Flow push run to pass.
set -euo pipefail

if [ "$GITHUB_REF" != "refs/heads/main" ]; then
    echo "Snapshot Flow release check applies only to main."
    exit 0
fi

status_read_failures=0

while :; do
    if ! run_state=$(gh api --method GET \
        "repos/${GITHUB_REPOSITORY}/actions/workflows/snapshot-flow-build.yaml/runs" \
        -f branch=main \
        -f event=push \
        -F per_page=1 \
        --jq '.workflow_runs[0] | select(. != null) | [.status, (.conclusion // "pending"), .html_url] | @tsv'); then
        status_read_failures=$((status_read_failures + 1))
        if [ "$status_read_failures" -ge 3 ]; then
            echo "::error::Could not read Snapshot Flow status after three attempts. Release is blocked."
            exit 1
        fi
        echo "::warning::Could not read Snapshot Flow status; retrying."
        sleep 20
        continue
    fi
    status_read_failures=0

    if [ -z "$run_state" ]; then
        echo "::error::No Snapshot Flow push run was found for main. Run Snapshot Flow before releasing."
        exit 1
    fi

    IFS=$'\t' read -r status conclusion run_url <<< "$run_state"
    if [ "$status" = "completed" ]; then
        if [ "$conclusion" != "success" ]; then
            echo "::error::Snapshot Flow did not complete successfully (${conclusion}): ${run_url}"
            exit 1
        fi
        echo "Snapshot Flow passed: ${run_url}"
        exit 0
    fi

    echo "Waiting for Snapshot Flow to complete (status: ${status}): ${run_url}"
    sleep 20
done
