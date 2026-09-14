#!/usr/bin/env bash
# Run all .test.main.kts unit tests under scripts/ (and scripts/lib/).
# Each test script exits non-zero on failure and prints its own summary.
# Runs every test before reporting so the full picture is visible.
#
# Requires `kotlin` on PATH (and a JDK). Run from the repository root:
#   ./scripts/run-script-tests.sh
set -uo pipefail

# The directory globs resolve against — a suite launched from the wrong checkout is a green that
# says nothing about the tree you meant to test, so put it on the record.
echo "Running tests from: $PWD (test globs resolve relative to this directory)"

failed=()
passed=0

for script in scripts/*.test.main.kts scripts/lib/*.test.main.kts; do
  [[ -e "$script" ]] || continue
  name=$(basename "$script")
  echo "--- $name"
  if kotlin "$script"; then
    (( passed++ )) || true
  else
    echo "FAILED"
    failed+=("$name")
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "Test failures:"
  printf "  %s\n" "${failed[@]}"
  exit 1
fi

echo "Tests complete: $passed passed."
