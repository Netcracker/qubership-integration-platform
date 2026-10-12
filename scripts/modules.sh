#!/usr/bin/env bash
#
# Module topology, declared once and sourced by the release scripts.
#
# The same lists also appear as literal YAML in .github/workflows — dispatch
# `choice` options, the main-build matrix, and release-all's per-module jobs
# cannot read a shell file. Those stay hand-written; keep them in step with this
# file.
#
# Usage: . "$(dirname "$0")/modules.sh"
#
# Running this file does nothing. It keeps the executable bit because
# super-linter's BASH_EXEC rule requires it of every .sh in the repo.

# A sourced library: the consumers use these, this file does not.
# shellcheck disable=SC2034

# The parent POMs that declare their own literal <version>, which is the platform
# version with -SNAPSHOT appended. parents/quarkus/pom.xml inherits its version
# from its pin on the base parent, so it is a child below and not listed here.
QIP_PARENT_POMS=(parents/pom.xml parents/base/pom.xml parents/spring/pom.xml)

# POMs whose <parent><version> pins a qip-parent-* POM by a literal version.
QIP_PARENT_CHILDREN=(
    pom.xml
    bom/pom.xml
    checkstyle/pom.xml
    schemas/pom.xml
    integration-build-pipeline/pom.xml
    integration-build-maven-plugin/pom.xml
    runtime-catalog/pom.xml
    sessions-management/pom.xml
    engine/pom.xml
    micro-engine/pom.xml
    parents/quarkus/pom.xml
)

# POMs that pin the released qip-integration-build-pipeline by this property. The
# dependency appends ${changelist} in each of them, so the pin names the release
# and a reactor build resolves the development coordinate from source.
QIP_LIBRARY_PROPERTY=qip.integration-build-pipeline.revision
QIP_LIBRARY_CONSUMERS=(runtime-catalog/pom.xml integration-build-maven-plugin/pom.xml)

# POMs that pin the released qip-checkstyle by this property.
QIP_CHECKSTYLE_PROPERTY=qip.checkstyle.version
QIP_CHECKSTYLE_CONSUMERS=(parents/base/pom.xml parents/spring/pom.xml)

# Module directories the BOM (bom/pom.xml) lists, one entry each.
QIP_BOM_ENTRIES=(
    schemas
    checkstyle
    micro-engine
    integration-build-pipeline
    integration-build-maven-plugin
    engine
    runtime-catalog
    sessions-management
)

# Modules release-all releases and the release manifest reports, in output order.
# schemas is one row for two artifacts: the npm package and the Maven artifact
# carry the same version. bom is released after every Maven module in the wave.
QIP_BOM_MODULES=(
    engine
    micro-engine
    runtime-catalog
    sessions-management
    integration-build-pipeline
    integration-build-maven-plugin
    schemas
    bom
    testing-service
    ui
    vscode-extension
)
