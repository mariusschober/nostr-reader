#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
dist_dir="$repo_dir/chrome/dist"
output=${1:-"$repo_dir/artifacts/reader-chrome-extension.zip"}

case "$output" in
  /*) ;;
  *) output="$repo_dir/$output" ;;
esac

if [ ! -f "$dist_dir/manifest.json" ]; then
  echo "Chrome dist is missing manifest.json; run the Chrome build first." >&2
  exit 1
fi

if [ -n "$(find "$dist_dir" -type l -print -quit)" ]; then
  echo "Chrome dist contains a symbolic link; refusing ambiguous packaging." >&2
  exit 1
fi

mkdir -p "$(dirname -- "$output")"
stage_dir=$(mktemp -d "${TMPDIR:-/tmp}/reader-chrome-package.XXXXXX")
cleanup() {
  rm -rf -- "$stage_dir"
}
trap cleanup EXIT HUP INT TERM

export COPYFILE_DISABLE=1
export TZ=UTC
cp -R "$dist_dir"/. "$stage_dir"/
find "$stage_dir" -exec touch -t 198001010000 {} +

rm -f -- "$output"
cd "$stage_dir"
find . -type f -print \
  | sed 's#^\./##' \
  | LC_ALL=C sort \
  | zip -X -q "$output" -@

unzip -t "$output"
shasum -a 256 "$output"
