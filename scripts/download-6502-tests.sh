#!/usr/bin/env bash
#
# Downloads the full Tom Harte SingleStepTests "nes6502/v1" set into
# mynes-core/testdata/.
#
# Source: https://github.com/SingleStepTests/65x02 (the canonical home of the
#         65x02 family sets; also mirrored under SingleStepTests/ProcessorTests).
# License: MIT (c) Thomas Harte and contributors -- see the LICENSE file in that
#          repository. The data is NOT redistributed by this project; this script
#          fetches it on demand into a gitignored directory.
#
# The set is 256 files (one per opcode), 10,000 cases each, ~1.4 GB total. A
# 500-case-per-opcode subset lives in mynes-core/src/test/resources/harte/nes6502
# and is what plain `mvn test` uses; the test harness automatically prefers the
# full set when this script has populated mynes-core/testdata/nes6502/v1.
#
# Inside the module rather than at the root of the checkout, because that is
# where HarteCaseLoader looks: it resolves a relative path, and Surefire runs
# with the working directory set to the module being tested. Getting this wrong
# is quiet rather than loud -- the loader simply falls back to the committed
# subset -- so it is worth knowing that this path and that one are the same
# decision written down twice.
#
# Idempotent: re-running is a no-op once the pinned commit is already unpacked.
#
set -euo pipefail

REPO_URL="https://github.com/SingleStepTests/65x02.git"
RAW_URL="https://raw.githubusercontent.com/SingleStepTests/65x02"
SPARSE_PATH="nes6502/v1"
# Pinned so the data set never shifts under the test suite.
PINNED_SHA="2f6980a2d95757486c7bee24355c360e40e2a224"
EXPECTED_FILES=256

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/mynes-core/testdata/nes6502/v1"
MARKER="$ROOT_DIR/mynes-core/testdata/nes6502/.commit"
WORK_DIR="$ROOT_DIR/mynes-core/testdata/.65x02-checkout"

count_json() {
    if [ -d "$DEST_DIR" ]; then
        find "$DEST_DIR" -maxdepth 1 -name '*.json' | wc -l | tr -d ' '
    else
        echo 0
    fi
}

if [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$PINNED_SHA" ] && [ "$(count_json)" -eq "$EXPECTED_FILES" ]; then
    echo "nes6502/v1 already present at $PINNED_SHA ($EXPECTED_FILES files) -- nothing to do."
    exit 0
fi

echo "Fetching $SPARSE_PATH from $REPO_URL @ $PINNED_SHA ..."

fetch_with_git() {
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    git -C "$WORK_DIR" init -q
    git -C "$WORK_DIR" remote add origin "$REPO_URL"
    git -C "$WORK_DIR" config core.sparseCheckout true
    git -C "$WORK_DIR" sparse-checkout init --cone
    git -C "$WORK_DIR" sparse-checkout set "$SPARSE_PATH"
    # blob:none keeps the fetch to just the blobs the sparse checkout needs.
    git -C "$WORK_DIR" fetch -q --depth 1 --filter=blob:none origin "$PINNED_SHA"
    git -C "$WORK_DIR" checkout -q FETCH_HEAD
    [ -d "$WORK_DIR/$SPARSE_PATH" ]
}

fetch_with_curl() {
    echo "git fetch failed; falling back to per-file download over HTTPS."
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR/$SPARSE_PATH"
    for i in $(seq 0 255); do
        name="$(printf '%02x' "$i").json"
        curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
            -o "$WORK_DIR/$SPARSE_PATH/$name" \
            "$RAW_URL/$PINNED_SHA/$SPARSE_PATH/$name"
    done
}

if ! fetch_with_git; then
    fetch_with_curl
fi

actual="$(find "$WORK_DIR/$SPARSE_PATH" -maxdepth 1 -name '*.json' | wc -l | tr -d ' ')"
if [ "$actual" -ne "$EXPECTED_FILES" ]; then
    echo "Expected $EXPECTED_FILES json files, got $actual -- aborting." >&2
    exit 1
fi

rm -rf "$DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
mv "$WORK_DIR/$SPARSE_PATH" "$DEST_DIR"
rm -rf "$WORK_DIR"
echo "$PINNED_SHA" > "$MARKER"

echo "Done: $EXPECTED_FILES files in $DEST_DIR (commit $PINNED_SHA)."
