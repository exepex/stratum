#!/usr/bin/env bash
# Golden test of ConfigReference.java. It lays out the sample metadata in test/metadata as the build
# would, under each module's target/classes/META-INF, writes the page and compares it with
# test/expected.md. Then it checks that each bad input fails and writes no page.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
page=docs/configuration/reference.md

fail() { echo "FAIL: $*" >&2; exit 1; }
generate() { java "$here/ConfigReference.java" "$work/repo"; }

# Copies each sample file, such as test/metadata/libs/stratum-web.json, to the place the build writes it.
fresh() {
  rm -rf "$work/repo"
  (cd "$here/test/metadata" && find . -name '*.json') | while read -r file; do
    local folder="$work/repo/${file%.json}/target/classes/META-INF"
    mkdir -p "$folder"
    cp "$here/test/metadata/$file" "$folder/spring-configuration-metadata.json"
  done
}

metadata() { echo "$work/repo/$1/target/classes/META-INF/spring-configuration-metadata.json"; }

fresh
generate >/dev/null
diff "$here/test/expected.md" "$work/repo/$page" || fail "the page differs from test/expected.md"
echo "ok: golden page"

# Runs one bad case: the change is applied to a fresh copy, then the run must fail with the expected
# error and write no page.
expect_failure() {
  local name=$1 expected=$2 change=$3
  fresh
  eval "$change"
  if generate 2>"$work/error" >/dev/null; then fail "$name: the run did not fail"; fi
  grep -qF "error: ${expected}" "$work/error" || fail "$name: no '$expected' in: $(cat "$work/error")"
  [[ ! -e "$work/repo/$page" ]] || fail "$name: a page was written"
  echo "ok: $name fails ($(grep -o 'error: .*' "$work/error"))"
}

expect_failure "module missing from MODULES" "starters/stratum-starter-new has stratum.* keys but no entry in MODULES" \
  "mkdir -p \$(dirname \$(metadata starters/stratum-starter-new)) && cp \$(metadata starters/stratum-starter-data-jpa) \$(metadata starters/stratum-starter-new)"
expect_failure "module not built" "starters/stratum-starter-cache/target/classes/META-INF/spring-configuration-metadata.json is missing" \
  "rm \$(metadata starters/stratum-starter-cache)"
expect_failure "key outside the module's prefix" "starters/stratum-starter-data-jpa: stratum.database.enabled does not start with stratum.data" \
  "sed -i 's/stratum.data.enabled/stratum.database.enabled/' \$(metadata starters/stratum-starter-data-jpa)"
expect_failure "malformed metadata" "libs/stratum-web/target/classes/META-INF/spring-configuration-metadata.json: unexpected" \
  "printf '{\"properties\": [}' > \$(metadata libs/stratum-web)"
