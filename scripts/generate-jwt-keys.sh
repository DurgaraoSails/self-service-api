#!/usr/bin/env bash
# Generates a local RSA key pair for signing/validating self-issued JWTs.
# Output: PKCS8 private key + X.509 public key, in the exact PEM formats
# Spring's RsaKeyConverters.pkcs8()/.x509() expect.
#
# Usage: ./scripts/generate-jwt-keys.sh [output-dir]
#   output-dir defaults to secrets/jwt (gitignored)

set -euo pipefail

OUT_DIR="${1:-secrets/jwt}"
mkdir -p "$OUT_DIR"

PRIVATE_KEY_PATH="$OUT_DIR/private-key.pem"
PUBLIC_KEY_PATH="$OUT_DIR/public-key.pem"

if [[ -f "$PRIVATE_KEY_PATH" || -f "$PUBLIC_KEY_PATH" ]]; then
  echo "Key files already exist under $OUT_DIR — remove them first if you want to regenerate." >&2
  exit 1
fi

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE_KEY_PATH"
openssl rsa -pubout -in "$PRIVATE_KEY_PATH" -out "$PUBLIC_KEY_PATH"

echo "Generated:"
echo "  $PRIVATE_KEY_PATH"
echo "  $PUBLIC_KEY_PATH"
