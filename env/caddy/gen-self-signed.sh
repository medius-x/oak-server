#!/usr/bin/env bash
# Generates the self-signed TLS certificate for the Caddy entry point — for
# deployments where the server has NO domain (a bare IP): Let's Encrypt cannot
# issue there, so the client pins the certificate fingerprint instead (TOFU).
#
#   ./gen-self-signed.sh [name ...]
#   ./gen-self-signed.sh localhost 127.0.0.1 192.168.0.78 203.0.113.10
#
# Each name becomes a subjectAltName entry (IPv4/IPv6 literals as IP:, anything
# else as DNS:). localhost + 127.0.0.1 are always included, so the dev-machine
# smoke works with no arguments. Writes certs/tls.crt + certs/tls.key (both
# git-ignored) valid for 10 years — a short-lived cert would invalidate every
# client's pinned fingerprint on rotation, which is exactly what the pin
# exists to detect. Prints the SHA-256 fingerprint at the end: the value the
# client's trust dialog shows and stores.
set -euo pipefail
cd "$(dirname "$0")"

san="DNS:localhost,IP:127.0.0.1"
for name in "$@"; do
  if [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ || "$name" =~ : ]]; then
    entry="IP:${name}"
  else
    entry="DNS:${name}"
  fi
  case ",${san}," in
    *",${entry},"*) continue ;;  # already among the defaults (e.g. "localhost")
  esac
  san="${san},${entry}"
done

mkdir -p certs
# MSYS_NO_PATHCONV: Git Bash on Windows otherwise rewrites the -subj argument
# "/CN=oak" into a Windows path ("C:/Program Files/Git/CN=oak"). Harmless no-op
# on real Linux (where the provisioning flow re-runs this same script).
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
  -keyout certs/tls.key -out certs/tls.crt -days 3650 \
  -subj "/CN=oak" -addext "subjectAltName=${san}"

echo "wrote certs/tls.crt + certs/tls.key (SAN: ${san})"
echo "SHA-256 fingerprint (the client pin):"
openssl x509 -in certs/tls.crt -noout -fingerprint -sha256
