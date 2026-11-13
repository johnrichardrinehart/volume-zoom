# shellcheck shell=bash
# Usage: volume-zoom-sign UNSIGNED_APK SIGNED_APK
#
# Signs with the release keystore. VOLUME_ZOOM_KEYSTORE (PKCS12, key alias
# volume-zoom) and VOLUME_ZOOM_KEYSTORE_PASSWORD override the local defaults in
# ${XDG_DATA_HOME:-~/.local/share}/volume-zoom/. CI passes both explicitly.
# Every published release must be signed with the same key, or installed copies
# cannot update.

if [[ $# -ne 2 ]]; then
  echo "usage: volume-zoom-sign UNSIGNED_APK SIGNED_APK" >&2
  exit 2
fi

key_dir="${XDG_DATA_HOME:-$HOME/.local/share}/volume-zoom"
keystore="${VOLUME_ZOOM_KEYSTORE:-$key_dir/release.p12}"
password="${VOLUME_ZOOM_KEYSTORE_PASSWORD:-}"
if [[ -z $password && -f "$key_dir/release.pass" ]]; then
  password="$(<"$key_dir/release.pass")"
fi
if [[ ! -f $keystore || -z $password ]]; then
  echo "volume-zoom-sign: no keystore at $keystore or no password set" >&2
  exit 1
fi

VOLUME_ZOOM_KEYSTORE_PASSWORD="$password" "$APKSIGNER" sign \
  --ks "$keystore" --ks-type PKCS12 --ks-key-alias volume-zoom \
  --ks-pass env:VOLUME_ZOOM_KEYSTORE_PASSWORD \
  --out "$2" "$1"
"$APKSIGNER" verify --print-certs "$2" | grep -E 'SHA-256 digest' | head -1
