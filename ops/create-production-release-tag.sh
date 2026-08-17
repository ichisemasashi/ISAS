#!/bin/sh
set -eu

[ "$#" -eq 1 ] || { echo "usage: $0 BAKE_EVIDENCE" >&2; exit 64; }
evidence_file=$1
tag_name=$(jq -er '.tag.name' "$evidence_file")
target_commit=$(jq -er '.tag.target_commit' "$evidence_file")
case "$tag_name" in production/v[0-9]*.[0-9]*.[0-9]*) ;; *) echo "invalid production tag namespace" >&2; exit 64 ;; esac
case "$target_commit" in *[!0-9a-f]*|'') echo "invalid target commit" >&2; exit 64 ;; esac
[ "${#target_commit}" -eq 40 ] || { echo "target commit must have 40 characters" >&2; exit 64; }
git cat-file -e "$target_commit^{commit}"
if git show-ref --verify --quiet "refs/tags/$tag_name" || git ls-remote --exit-code --tags origin "refs/tags/$tag_name" >/dev/null 2>&1; then
  echo "tag already exists: $tag_name" >&2
  exit 1
fi
git tag -a "$tag_name" "$target_commit" -m "ISAS production release $tag_name"
git push origin "refs/tags/$tag_name"
echo "production release tag published: $tag_name -> $target_commit"
