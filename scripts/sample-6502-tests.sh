#!/usr/bin/env bash
#
# Builds the committed subset of the Tom Harte nes6502/v1 test set.
#
# Takes the first N cases of each of the 256 opcode files from the full set in testdata/
# (see download-6502-tests.sh) and writes them gzipped into src/test/resources, so that a
# plain `mvn test` still exercises every opcode without a network fetch. The harness prefers
# the full set whenever it is present, so this only has to be re-run when bumping the pinned
# upstream commit or the sample size.
#
set -euo pipefail

SAMPLE_SIZE="${SAMPLE_SIZE:-500}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/testdata/nes6502/v1"
DEST_DIR="$ROOT_DIR/src/test/resources/harte/nes6502"
MARKER="$ROOT_DIR/testdata/nes6502/.commit"

if [ ! -d "$SRC_DIR" ]; then
    echo "$SRC_DIR is missing -- run scripts/download-6502-tests.sh first." >&2
    exit 1
fi

command -v jq >/dev/null || { echo "jq is required." >&2; exit 1; }

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

count=0
for source in "$SRC_DIR"/*.json; do
    name="$(basename "$source" .json)"
    jq -c ".[0:$SAMPLE_SIZE]" "$source" | gzip -9 > "$DEST_DIR/$name.json.gz"
    count=$((count + 1))
done

commit="unknown"
[ -f "$MARKER" ] && commit="$(cat "$MARKER")"

cat > "$DEST_DIR/PROVENANCE" <<EOF
Tom Harte SingleStepTests, nes6502/v1
Source:      https://github.com/SingleStepTests/65x02
Commit:      $commit
Sample:      first $SAMPLE_SIZE of 10000 cases per opcode
Files:       $count
Regenerate:  scripts/download-6502-tests.sh && scripts/sample-6502-tests.sh
License:     MIT, (c) Thomas Harte and contributors
EOF

echo "Wrote $count files ($SAMPLE_SIZE cases each) to $DEST_DIR"
du -sh "$DEST_DIR"
