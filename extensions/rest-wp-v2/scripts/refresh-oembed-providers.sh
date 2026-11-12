#!/usr/bin/env bash
# Refreshes the bundled oEmbed provider snapshot: the registry published at https://oembed.com/providers.json
# (maintained in https://github.com/iamcal/oembed, MIT licence, whose LICENSE file is kept beside the snapshot).
# Run it from anywhere; it prints the snapshot date, which the README records.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
resources="$here/../runtime/src/main/resources/org/quarkpress/restwp/oembed"
providers_url="${OEMBED_PROVIDERS_URL:-https://oembed.com/providers.json}"
license_url="${OEMBED_LICENSE_URL:-https://raw.githubusercontent.com/iamcal/oembed/master/LICENSE}"

mkdir -p "$resources"
tmp="$(mktemp -d "$resources/.refresh.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --max-time 60 -o "$tmp/providers.json" "$providers_url"
curl -fsSL --max-time 60 -o "$tmp/LICENSE" "$license_url"

# the snapshot must be a JSON list of providers, each with a name and endpoints
python3 -I - "$tmp/providers.json" <<'EOF'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    providers = json.load(f)
if not isinstance(providers, list) or not providers:
    sys.exit("providers.json is not a non-empty list")
for p in providers:
    if not isinstance(p.get("provider_name"), str) or not isinstance(p.get("endpoints"), list):
        sys.exit("a provider lacks provider_name or endpoints: " + json.dumps(p)[:200])
print(f"{len(providers)} providers")
EOF

{
  echo "The provider snapshot providers.json beside this file is https://oembed.com/providers.json as published by"
  echo "the oembed project (https://github.com/iamcal/oembed), fetched on $(date -u +%Y-%m-%d) by"
  echo "extensions/rest-wp-v2/scripts/refresh-oembed-providers.sh. Its licence follows."
  echo
  cat "$tmp/LICENSE"
} > "$tmp/providers.LICENSE"

mv "$tmp/providers.json" "$resources/providers.json"
mv "$tmp/providers.LICENSE" "$resources/providers.LICENSE"
echo "snapshot date: $(date -u +%Y-%m-%d)"
echo "written: $resources/providers.json and providers.LICENSE"
