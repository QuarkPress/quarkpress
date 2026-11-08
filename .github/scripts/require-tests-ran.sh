#!/usr/bin/env bash
# Fails unless each named Surefire report exists and records at least one test and no skipped one: a parity test
# that was renamed, filtered out or disabled must not let its CI step pass.
# Usage: require-tests-ran.sh TEST-<class>.xml...
set -euo pipefail
status=0
for report in "$@"; do
  suite=$(grep -m1 -o '<testsuite [^>]*>' "$report" 2>/dev/null || true)
  tests=$(printf '%s' "$suite" | grep -o ' tests="[0-9]*"' | grep -o '[0-9][0-9]*' || true)
  skipped=$(printf '%s' "$suite" | grep -o ' skipped="[0-9]*"' | grep -o '[0-9][0-9]*' || true)
  if [ -z "$suite" ] || [ "${tests:-0}" -lt 1 ] || [ "${skipped:-1}" != 0 ]; then
    echo "::error::$report: expected at least one test run and none skipped (tests=${tests:-none}, skipped=${skipped:-none})"
    status=1
  else
    echo "$report: $tests test(s) ran, none skipped"
  fi
done
exit $status
