#!/usr/bin/env bash
# Golden test of WikiSync.java. It syncs the sample repository in test/repo into a copy of the sample
# wiki in test/wiki and compares the result with test/expected. Then it checks that a second run makes
# no commit, and that each bad input fails and leaves the wiki unchanged.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# No git identity, as on a fresh runner: the script must bring its own committer.
export HOME="$work/home" GIT_CONFIG_NOSYSTEM=1
mkdir -p "$HOME"

fail() { echo "FAIL: $*" >&2; exit 1; }
sync() { java "$here/WikiSync.java" "$work/repo" "$work/wiki" "$@"; }
commits() { git -C "$work/wiki" rev-list --count HEAD; }

# Copies the sample repository and wiki, the wiki as a git clone with one commit.
fresh() {
  rm -rf "$work/repo" "$work/wiki"
  cp -r "$here/test/repo" "$work/repo"
  cp -r "$here/test/wiki" "$work/wiki"
  git -C "$work/wiki" init -q
  git -C "$work/wiki" add -A
  git -C "$work/wiki" -c user.name=test -c user.email=test@example.invalid commit -qm "Sample wiki"
}

fresh
sync --commit
diff -r --exclude=.git "$here/test/expected" "$work/wiki" || fail "the wiki differs from test/expected"
[[ $(commits) == 2 ]] || fail "the first run made no commit"
sync --commit
[[ $(commits) == 2 ]] || fail "a run with no change made a commit"
echo "ok: golden wiki, one commit, no commit without a change"

# Runs one bad case: the change is applied to a fresh copy, then the sync must fail and write nothing.
expect_failure() {
  local name=$1 change=$2
  fresh
  (cd "$work" && eval "$change")
  cp -r "$work/wiki" "$work/before"
  if sync 2>"$work/error"; then fail "$name: the sync did not fail"; fi
  diff -r "$work/before" "$work/wiki" >/dev/null || fail "$name: the wiki changed"
  rm -rf "$work/before"
  echo "ok: $name fails ($(grep -o 'error: .*' "$work/error"))"
}

intro=repo/docs/getting-started/intro.md
expect_failure "unmarked page with a generated name" "printf '# Mine\n' > wiki/Getting-started-intro.md"
expect_failure "unmarked sidebar" "printf '**Menu**\n' > wiki/_Sidebar.md"
# The image and the outside file exist, so only the check under test can stop these two.
expect_failure "relative image" "touch repo/docs/getting-started/diagram.png && printf '![Diagram](diagram.png)\n' >> $intro"
expect_failure "link out of the repository" "touch outside.md && printf '[Out](../../../outside.md)\n' >> $intro"
expect_failure "link to a missing file" "printf '[Gone](missing.md)\n' >> $intro"
expect_failure "page missing from the index" "printf '# Orphan\n' > repo/docs/orphan.md"
expect_failure "page in a subfolder missing from the index" "mkdir -p repo/docs/sub && printf '# Orphan\n' > repo/docs/sub/orphan.md"
expect_failure "anchor to a missing heading in another page" "printf '[Gone](../contributing.md#nowhere)\n' >> $intro"
expect_failure "same-page anchor to a missing heading" "printf '[Gone](#nowhere)\n' >> $intro"
expect_failure "anchor to a heading inside a code fence" "printf '\x60\x60\x60\n# Fenced\n\x60\x60\x60\n[Fenced](#fenced)\n' >> $intro"
expect_failure "index section without a group" "printf '## Extra\n' >> repo/docs/README.md"
# Adds a table row under the index's "Getting started" section.
add_row() { sed -i "s#^| \\[Intro\\](getting-started/intro.md) | The first steps. |#&\\n$1#" repo/docs/README.md; }
expect_failure "index row outside docs" "add_row '| [Rules](../AGENTS.md) | x |'"
expect_failure "two pages with one name" \
  "mkdir -p repo/docs/getting && printf '# Twin\\n' > repo/docs/getting/started-intro.md && add_row '| [Twin](getting/started-intro.md) | x |'"
