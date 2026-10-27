#!/bin/sh
# Fails when a tracked file is over its line limit (see AGENTS.md, "File size limits"):
# 500 lines for text-only files, 400 lines for every other file.
set -eu

CODE_LIMIT=400
TEXT_LIMIT=500

cd "$(git rev-parse --show-toplevel)"

failed=0
for file in $(git ls-files); do
  case "$file" in
    LICENSE | */LICENSE | mvnw | mvnw.cmd | .mvn/*) continue ;;
    *.md | *.txt | *.adoc) limit=$TEXT_LIMIT ;;
    *) limit=$CODE_LIMIT ;;
  esac
  [ -f "$file" ] || continue
  lines=$(wc -l < "$file")
  if [ "$lines" -gt "$limit" ]; then
    echo "$file: $lines lines (limit $limit)"
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo "Split the files above into smaller single-purpose files."
  exit 1
fi
echo "file sizes ok"
