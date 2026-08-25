#!/usr/bin/env bash
#
# Module topology, declared once and sourced by the release scripts.
#
# The same lists also appear as literal YAML in .github/workflows — dispatch
# `choice` options, the main-build matrix, and release-all's ALL_MODULES cannot
# read a shell file. Those stay hand-written; keep them in step with this file.
#
# Usage: . "$(dirname "$0")/modules.sh"
#
# Running this file does nothing. It keeps the executable bit because
# super-linter's BASH_EXEC rule requires it of every .sh in the repo.

# A sourced library: the consumers use these, this file does not.
# shellcheck disable=SC2034

# Modules whose POM pins qip-monorepo-parent by a literal version.
QIP_PARENT_CHILDREN=(engine integration-build-pipeline runtime-catalog sessions-management)

# Backend services. A full release-all wave publishes these under one version.
QIP_BACKEND=(engine micro-engine runtime-catalog sessions-management)

# Modules the release BOM reports, in output order. schemas is one row for two
# artifacts: the npm package and the Maven artifact carry the same version.
QIP_BOM_MODULES=(
    engine
    micro-engine
    runtime-catalog
    sessions-management
    integration-build-pipeline
    schemas
    testing-service
    ui
    vscode-extension
)

# POMs that pin the released qip-checkstyle by property.
QIP_CHECKSTYLE_CONSUMERS=(parent/pom.xml micro-engine/pom.xml schemas/pom.xml sessions-management/pom.xml)
