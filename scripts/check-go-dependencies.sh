#!/usr/bin/env bash
#
# Refuse a Go module that comes from outside the known-good public sources.
#
# This is the second net under the pre-commit sanitization gate, and the only one
# CI can enforce. The forbidden identifiers themselves cannot live in this
# repository, so instead of matching names this check asserts the shape of the
# graph: every module path named by go.mod and go.sum has to sit under a prefix
# listed below. A dependency from anywhere else fails the check without anything
# having to name it.
#
# The list is deliberately narrow — one GitHub organization per entry, not the
# whole of github.com — so that a new source of code shows up as an explicit edit
# to this file for a reviewer to weigh.
#
# Usage:
#   check-go-dependencies.sh [MODULE_DIR ...]   # defaults to testing-service
#
# Exit codes: 0 clean, 1 a module outside the allowlist, 2 the check could not run.

set -euo pipefail

# Every prefix the module graph needs today. Keep it sorted.
allowed_prefixes=(
    dario.cat
    github.com/AdaLogics
    github.com/Azure
    github.com/KyleBanks
    github.com/Microsoft
    github.com/Netcracker
    github.com/PaesslerAG
    github.com/PuerkitoBio
    github.com/andybalholm
    github.com/beorn7
    github.com/cenkalti
    github.com/cespare
    github.com/containerd
    github.com/cpuguy83
    github.com/creack
    github.com/davecgh
    github.com/distribution
    github.com/dlclark
    github.com/docker
    github.com/felixge
    github.com/fsnotify
    github.com/go-logr
    github.com/go-ole
    github.com/go-openapi
    github.com/go-viper
    github.com/gofiber
    github.com/gogo
    github.com/google
    github.com/grpc-ecosystem
    github.com/jinzhu
    github.com/josharian
    github.com/kisielk
    github.com/klauspost
    github.com/knadh
    github.com/kr
    github.com/kylelemons
    github.com/lufia
    github.com/magiconair
    github.com/mailru
    github.com/mattn
    github.com/mitchellh
    github.com/moby
    github.com/morikuni
    github.com/munnerz
    github.com/niemeyer
    github.com/opencontainers
    github.com/pkg
    github.com/pmezard
    github.com/power-devops
    github.com/prometheus
    github.com/rivo
    github.com/rogpeppe
    github.com/santhosh-tekuri
    github.com/shirou
    github.com/shoenig
    github.com/sirupsen
    github.com/stretchr
    github.com/swaggo
    github.com/testcontainers
    github.com/tidwall
    github.com/tklauser
    github.com/tmthrgd
    github.com/uptrace
    github.com/valyala
    github.com/vmihailenco
    github.com/wI2L
    github.com/yuin
    github.com/yusufpapurcu
    go.opentelemetry.io
    golang.org/x
    google.golang.org
    gopkg.in
    gotest.tools
    mellium.im
)

bail() {
    printf '%s\n' "$*" >&2
    exit 2
}

usage() {
    cat << 'USAGE'
Usage:
  check-go-dependencies.sh [MODULE_DIR ...]   # defaults to testing-service

Exit codes: 0 clean, 1 a module outside the allowlist, 2 the check could not run.
USAGE
}

# Module paths named by go.mod: the module itself, and every path in a require,
# replace or exclude directive. A version, an operator and a directive keyword all
# fail the path pattern, and a replace target on the local filesystem starts with
# a dot and is skipped along with them.
modules_from_go_mod() {
    awk '
        { sub(/\/\/.*/, "") }
        $1 == "module" { print $2; next }
        /^[[:space:]]*(require|replace|exclude)[[:space:]]*\(/ { block = 1; next }
        block && /^[[:space:]]*\)/ { block = 0; next }
        {
            if (!block && $1 != "require" && $1 != "replace" && $1 != "exclude") next
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^v[0-9]/) continue
                if ($i ~ /^[A-Za-z0-9][A-Za-z0-9._~-]*\.[A-Za-z0-9._~-]+(\/[^[:space:]]+)*$/) print $i
            }
        }
    ' "$1"
}

modules_from_go_sum() {
    awk 'NF { print $1 }' "$1"
}

is_allowed() {
    local module="$1" prefix
    for prefix in "${allowed_prefixes[@]}"; do
        [ "$module" = "$prefix" ] && return 0
        case "$module" in "$prefix"/*) return 0 ;; esac
    done
    return 1
}

if [ $# -gt 0 ] && { [ "$1" = "-h" ] || [ "$1" = "--help" ]; }; then
    usage
    exit 0
fi

[ $# -gt 0 ] || set -- testing-service

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
paths="$workdir/paths"
: > "$paths"

for module_dir in "$@"; do
    [ -d "$module_dir" ] || bail "No module directory at '$module_dir'."
    for file in go.mod go.sum; do
        [ -r "$module_dir/$file" ] ||
            bail "Cannot read '$module_dir/$file'. Without both files the graph is unknown, so this check cannot pass."
    done
    modules_from_go_mod "$module_dir/go.mod" >> "$paths"
    modules_from_go_sum "$module_dir/go.sum" >> "$paths"
done

[ -s "$paths" ] || bail "Found no module paths to check. Read the files by hand before trusting this result."

status=0
while IFS= read -r module; do
    is_allowed "$module" || {
        printf '%s: module path outside the dependency allowlist\n' "$module" >&2
        status=1
    }
done < <(sort -u "$paths")

if [ "$status" -eq 1 ]; then
    printf 'Every module above comes from a source this repository has not vetted. Confirm the module is public and add its prefix to the allowlist in %s, or drop the dependency.\n' "$0" >&2
fi

exit "$status"
