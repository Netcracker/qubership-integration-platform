#!/usr/bin/env bash
# Locate the acceptance fixtures for the shell drivers.
QIP_SERVICE_ROOT="${QIP_SERVICE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
QIP_SKILLS_TESTING_ROOT="${QIP_SKILLS_TESTING_ROOT:-${QIP_SERVICE_ROOT}/.private/rocky-skills/testing}"
export QIP_SERVICE_ROOT QIP_SKILLS_TESTING_ROOT
