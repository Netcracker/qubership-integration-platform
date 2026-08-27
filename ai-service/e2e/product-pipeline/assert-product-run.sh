#!/usr/bin/env bash
# Asserts a product CREATE run report against durable contract fields.
set -euo pipefail

REPORT="${1:?report json}"
command -v jq >/dev/null

require_string() {
  local key="$1"
  local value
  value="$(jq -r --arg k "${key}" '.[$k] // empty' "${REPORT}")"
  [[ -n "${value}" && "${value}" != "null" ]] || {
    echo "FAIL: missing ${key}" >&2
    exit 1
  }
}

for key in runtimeMode profileId profileVersion validationVerdict \
  terminalState expectedTerminalState; do
  require_string "${key}"
done

jq -e '
  (.knowledgePackage | keys | sort) == [
    "certificationDigest",
    "certificationStatus",
    "knowledgeVersion",
    "packageChecksum",
    "packageKey",
    "schemaVersion"
  ]
  and .knowledgePackage.certificationStatus == "CERTIFIED"
  and (
    [
      .knowledgePackage.packageKey,
      .knowledgePackage.knowledgeVersion,
      .knowledgePackage.schemaVersion,
      .knowledgePackage.packageChecksum,
      .knowledgePackage.certificationDigest
    ]
    | all(type == "string" and length > 0)
  )
  and .knowledgeContext.packageChecksum
      == .knowledgePackage.packageChecksum
  and (.knowledgeContext.objectIds | type == "array")
  and (
    .knowledgeContext.contentChars
    | type == "number" and floor == . and . >= 0
  )
' "${REPORT}" >/dev/null || {
  echo "FAIL: invalid or mismatched Knowledge Package evidence" >&2
  exit 1
}

if [[ "$(jq -r '.scenarioId' "${REPORT}")" == "product-create-chain-error-handling" ]]; then
  jq -e '
    (.knowledgePackage | keys | sort) == [
      "certificationDigest",
      "certificationStatus",
      "knowledgeVersion",
      "packageChecksum",
      "packageKey",
      "schemaVersion"
    ]
    and .knowledgePackage.certificationStatus == "CERTIFIED"
    and .knowledgeContext.packageChecksum == .knowledgePackage.packageChecksum
    and (.knowledgeContext.objectIds | index("CIP:GEN-000049") != null)
    and (.materializedElementTypes | index("try-catch-finally-2") != null)
    and (.materializedElementTypes | index("try-2") != null)
    and (.materializedElementTypes | index("catch-2") != null)
  ' "${REPORT}" >/dev/null || {
    echo "FAIL: error-handling scenario missing required knowledge or element evidence" >&2
    exit 1
  }
fi

[[ "$(jq -r '.runtimeMode' "${REPORT}")" == "product" ]] \
  || { echo "FAIL: runtimeMode must be product" >&2; exit 1; }

state="$(jq -r '.terminalState' "${REPORT}")"
expected="$(jq -r '.expectedTerminalState' "${REPORT}")"
[[ "${state}" == "${expected}" ]] || {
  echo "FAIL: terminalState=${state} expected=${expected}" >&2
  exit 1
}

if [[ "${expected}" == "PLAN_APPROVED" ]]; then
  require_string "approvalTargetHash"
  jq -e '.approvalEligible == true' "${REPORT}" >/dev/null || {
    echo "FAIL: approvalEligible must be true for PLAN_APPROVED" >&2
    exit 1
  }
  [[ "$(jq -r '.validationVerdict' "${REPORT}")" == "PASS" ]] || {
    echo "FAIL: validationVerdict must be PASS for PLAN_APPROVED" >&2
    exit 1
  }
elif [[ "${expected}" == "WAITING_FOR_APPROVAL" ]]; then
  jq -e '.approvalEligible == true' "${REPORT}" >/dev/null || {
    echo "FAIL: approvalEligible must be true when waiting for plan approval" >&2
    exit 1
  }
elif [[ "${expected}" == "FAILED" ]]; then
  jq -e '.approvalEligible == false' "${REPORT}" >/dev/null || {
    echo "FAIL: approvalEligible must be false for FAILED validation runs" >&2
    exit 1
  }
  [[ "$(jq -r '.validationVerdict' "${REPORT}")" == "FAIL" ]] || {
    echo "FAIL: validationVerdict must be FAIL for FAILED runs" >&2
    exit 1
  }
  jq -e '(.committedArtifactKinds // []) | index("PLAN_VALIDATION_RESULT") != null' "${REPORT}" \
    >/dev/null || {
    echo "FAIL: FAILED runs must retain PLAN_VALIDATION_RESULT evidence" >&2
    exit 1
  }
elif [[ "${expected}" == "CHAIN_MATERIALIZED" ]]; then
  require_string "materializedChainId"
  require_string "approvedPlanContentHash"
  [[ "$(jq -r '.validationVerdict' "${REPORT}")" == "PASS" ]] || {
    echo "FAIL: validationVerdict must be PASS for CHAIN_MATERIALIZED" >&2
    exit 1
  }
  jq -e '.reconcileMatches == true' "${REPORT}" >/dev/null || {
    echo "FAIL: reconcileMatches must be true for CHAIN_MATERIALIZED" >&2
    exit 1
  }
  for kind in \
    IMPLEMENTATION_PLAN PLAN_VALIDATION_RESULT APPROVAL_RECORD \
    MATERIALIZATION_RESULT CATALOG_CHAIN_SNAPSHOT RECONCILE_RESULT; do
    jq -e --arg k "${kind}" '(.committedArtifactKinds // []) | index($k) != null' "${REPORT}" \
      >/dev/null || {
      echo "FAIL: missing committed kind ${kind}" >&2
      exit 1
    }
  done
  if jq -e '(.materializedElementTypes // []) | index("script") != null' "${REPORT}" >/dev/null; then
    # Script bodies are redacted from durable evidence. The script generator either changes the
    # graph or records NOT_APPLICABLE after verifying that an earlier generator filled every body.
    if ! jq -e '
      (.graphPatchArtifacts // [])
      | map(select(
          (.ownerCapabilityId == "cip-script-generator")
          and ((.applicability // "") | ascii_upcase) == "APPLICABLE"
          and (.baseGraphDigest // "") != ""
          and (.resultGraphDigest // "") != ""
          and (.baseGraphDigest != .resultGraphDigest)
        ))
      | length > 0
    ' "${REPORT}" >/dev/null; then
      if ! jq -e '
        (.graphPatchArtifacts // [])
        | map(select(
            (.ownerCapabilityId == "cip-script-generator")
            and ((.applicability // "") | ascii_upcase) == "NOT_APPLICABLE"
            and (.baseGraphDigest // "") != ""
            and (.baseGraphDigest == .resultGraphDigest)
          ))
        | length > 0
      ' "${REPORT}" >/dev/null; then
        echo "FAIL: CHAIN_MATERIALIZED with script elements requires script-body evidence" >&2
        exit 1
      fi
    fi
  fi
elif [[ "${expected}" == "WAITING_FOR_INPUT" ]]; then
  if jq -e '.haltGate == "stage-escalated"' "${REPORT}" >/dev/null; then
    for kind in REQUIREMENT_BRIEF IMPLEMENTATION_PLAN; do
      jq -e --arg k "${kind}" '(.committedArtifactKinds // []) | index($k) != null' "${REPORT}" \
        >/dev/null || {
        echo "FAIL: exhausted halt missing committed kind ${kind}" >&2
        exit 1
      }
    done
    jq -e '(.haltActions // []) | index("stop-with-report") != null' "${REPORT}" >/dev/null || {
      echo "FAIL: exhausted halt card must offer stop-with-report" >&2
      exit 1
    }
    jq -e '
      (.haltGuard // "") != ""
      and ((.haltPrompt // "") | length) > 0
    ' "${REPORT}" >/dev/null || {
      echo "FAIL: exhausted halt card must name the guard and keep visible text" >&2
      exit 1
    }
    jq -e '
      ((.committedArtifactKinds // [])
        | any(. == "MATERIALIZATION_RESULT" or . == "CATALOG_CHAIN_SNAPSHOT")
        | not)
    ' "${REPORT}" >/dev/null || {
      echo "FAIL: exhausted halt must not materialize a catalog chain" >&2
      exit 1
    }
  fi
fi

missing="$(jq -r '.missingRequiredFacts // [] | .[]' "${REPORT}")"
[[ -z "${missing}" ]] || { echo "FAIL: missing required facts: ${missing}" >&2; exit 1; }
forbidden="$(jq -r '.presentForbiddenFacts // [] | .[]' "${REPORT}")"
[[ -z "${forbidden}" ]] || { echo "FAIL: forbidden facts present: ${forbidden}" >&2; exit 1; }

jq -e '.hasGeneratedBundle == false' "${REPORT}" >/dev/null \
  || { echo "FAIL: hasGeneratedBundle must be false after cutover" >&2; exit 1; }
jq -e '.hasPublicationReceipt == false' "${REPORT}" >/dev/null \
  || { echo "FAIL: publication receipt must not exist" >&2; exit 1; }
jq -e '.hasCatalogMutation == false' "${REPORT}" >/dev/null \
  || { echo "FAIL: catalog mutation must not exist" >&2; exit 1; }
jq -e '.hasDeploymentArtifact == false' "${REPORT}" >/dev/null \
  || { echo "FAIL: deployment artifact must not exist" >&2; exit 1; }

if [[ "${expected}" == "PLAN_APPROVED" ]]; then
  for kind in IMPLEMENTATION_PLAN PLAN_VALIDATION_RESULT APPROVAL_RECORD; do
    jq -e --arg k "${kind}" '(.committedArtifactKinds // []) | index($k) != null' "${REPORT}" >/dev/null \
      || { echo "FAIL: missing committed kind ${kind}" >&2; exit 1; }
  done
  jq -e '.hasGeneratedBundle == false' "${REPORT}" >/dev/null \
    || { echo "FAIL: hasGeneratedBundle must be false" >&2; exit 1; }
fi

echo "PASS: product run assertions"
