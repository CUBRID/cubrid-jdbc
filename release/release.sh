#!/bin/bash

# -----------------------------------------------------------------------------
# CUBRID JDBC - Maven Central Release Script
#
# Purpose
#   Builds the CUBRID JDBC driver and bundles all artifacts into a
#   Maven-Central-ready ZIP with GPG signatures and checksums.
#
# Workflow
#   1) Validate GPG key ID & passphrase (CLI args or env vars)
#   2) Run build.sh → produce JARs & read version from VERSION-DIST
#   3) Update <version> tag inside release.pom
#   4) GPG-sign JAR/POM (ASCII armor) and create MD5/SHA1/256/512 digests
#   5) Stage files in Maven directory layout: stage/org/cubrid/…
#   6) Generate cubrid-jdbc-<ver>-release.zip
#
# Usage
#   ./make-release.sh -k <GPG_FINGERPRINT> -s <PASSPHRASE>
#   (or set GPG_KEY_ID and SIGN_PASSPHRASE environment variables)
#
# Prerequisites
#   • bash 3.0+, zip, md5sum/sha*sum utilities, gpg 2.1+
#   • Secret GPG key available locally; loopback pinentry recommended
#
# Output
#   SCRIPT_DIR/cubrid-jdbc-<version>-release.zip
# -----------------------------------------------------------------------------

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDBC_DIR="$(dirname "${SCRIPT_DIR}")"
OUTPUT_DIR="${JDBC_DIR}/output"

GPG_KEY_ID="${GPG_KEY_ID:-}"
SIGN_PASSPHRASE="${SIGN_PASSPHRASE:-}"

log()         { printf '[%s] %s\n' "$1" "$2"; }
log_info()    { log INFO  "$*"; }
log_warn()    { log WARN  "$*"; }
log_error()   { log ERROR "$*" >&2; }
log_ok()      { log OK    "$*"; }

show_usage() {
  cat << EOF

Usage: $0 [-k <fingerprint>] [-s <passphrase>] [-h]

Options:
  -k, --keyid    GPG key fingerprint (env: GPG_KEY_ID)
  -s, --sign     GPG signing key pass (env: SIGN_PASSPHRASE)
  -h, --help     Show this help and exit
EOF
}

parse_cli() {
  local argv=()
  for arg in "$@"; do
    case "$arg" in
      --keyid) argv+=( -k ) ;;
      --sign)  argv+=( -s ) ;;
      --help)  argv+=( -h ) ;;
      *)       argv+=( "$arg" ) ;;
    esac
  done

  if (( ${#argv[@]} )); then
    set -- "${argv[@]}"
  else
    set --
  fi

  while getopts ':k:s:h' opt; do
    case "$opt" in
      k) GPG_KEY_ID="$OPTARG" ;;
      s) SIGN_PASSPHRASE="$OPTARG" ;;
      h) show_usage; exit 0 ;;
      *) show_usage; exit 1 ;;
    esac
  done
  shift $((OPTIND - 1))
}

check_gpg_config() {
  [[ -n "$GPG_KEY_ID" ]]       || { log_error "GPG key ID missing."; exit 1; }
  [[ -n "$SIGN_PASSPHRASE" ]]  || { log_error "GPG passphrase missing."; exit 1; }
}

check_gpg_key() {
  gpg --batch --list-secret-keys "$GPG_KEY_ID" &> /dev/null || {
    log_error "GPG secret key $GPG_KEY_ID not found"; exit 1;
  }
}

setup_pinentry() {
  local v major minor
  v=$(gpg --version | head -n1 | awk '{print $3}')
  major=${v%%.*}
  minor=${v#*.}; minor=${minor%%.*}

  (( major > 2 || ( major == 2  &&  minor >= 1 ) )) \
    && PINENTRY_OPTS=(--pinentry-mode loopback) || PINENTRY_OPTS=()
}

run_build() {
  log_info "Running build.sh..."
  ( cd "${JDBC_DIR}" && ./build.sh )
}

read_version() {
  local vf="${OUTPUT_DIR}/VERSION-DIST"
  [[ -f "$vf" ]] || { log_error "VERSION file missing: $vf"; exit 1; }
  VERSION=$(< "$vf")
  log_info "Version detected: $VERSION"
}

update_pom_version() {
  sed -i.back -e "s|<version>.*</version>|<version>${VERSION}</version>|" "${SCRIPT_DIR}/release.pom"
  log_info "release.pom updated"
}

sign_and_checksum() {
  log_info "Sign & checksum artefacts..."
  cp "${SCRIPT_DIR}/release.pom" "${SCRIPT_DIR}/cubrid-jdbc-${VERSION}.pom"

  local gpg_opts=(--batch --yes --local-user "${GPG_KEY_ID}" --passphrase "${SIGN_PASSPHRASE}")
  if ((${#PINENTRY_OPTS[@]})); then
    gpg_opts+=("${PINENTRY_OPTS[@]}")
  fi

  local artefacts=( "${JDBC_DIR}/cubrid-jdbc-${VERSION}"*.jar
                    "${SCRIPT_DIR}/cubrid-jdbc-${VERSION}.pom" )
  [[ ${#artefacts[@]} -gt 0 ]] || { log_error "No artefacts found"; exit 1; }

  for f in "${artefacts[@]}"; do
    log_info "  - Signing $f"
    gpg "${gpg_opts[@]}" --armor --detach-sign --output "${f}.asc" "$f"

    log_info "  - Checksums $f"
    for algo in md5 sha1 sha256 sha512; do
      "${algo}sum" "$f" | awk '{print $1}' > "${f}.${algo}"
    done
  done
}

create_release_zip() {
  log_info "Create release bundle..."
  local stage="${SCRIPT_DIR}/stage/org/cubrid/cubrid-jdbc/${VERSION}"
  local zip="${SCRIPT_DIR}/cubrid-jdbc-${VERSION}-release.zip"

  rm -rf "$stage" && mkdir -p "$stage"

  local jars=( \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.jar \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.asc \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.md5 \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.sha1 \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.sha256 \
    "${JDBC_DIR}"/cubrid-jdbc-"${VERSION}"*.sha512 \
    )
  [[ ${#jars[@]} -gt 0 ]] || { log_error "No JAR files found"; exit 1; }
  cp "${jars[@]}" "${stage}/"

  local poms=( \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom.asc \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom.md5 \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom.sha1 \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom.sha256 \
    "${SCRIPT_DIR}"/cubrid-jdbc-${VERSION}.pom.sha512 \
    )
  [[ ${#poms[@]} -gt 0 ]] || { log_error "No POM files found"; exit 1; }
  cp "${poms[@]}" "${stage}/"

  (cd "${SCRIPT_DIR}/stage" && zip -qr "${zip}" org) \
   && log_ok "Release ZIP ready: ${zip}" \
   || log_error "Release ZIP failed: ${zip}"
}

banner() {
  echo '
 _____  _   _ ______ ______  _____ ______     ___ ______ ______  _____
/  __ \| | | || ___ \| ___ \|_   _||  _  \   |_  ||  _  \| ___ \/  __ \
| /  \/| | | || |_/ /| |_/ /  | |  | | | |     | || | | || |_/ /| /  \/
| |    | | | || ___ \|    /   | |  | | | |     | || | | || ___ \| |
| \__/\| |_| || |_/ /| |\ \  _| |_ | |/ /  /\__/ /| |/ / | |_/ /| \__/\
 \____/ \___/ \____/ \_| \_| \___/ |___/   \____/ |___/  \____/  \____/

'
}

main() {
  parse_cli "$@"
  check_gpg_config
  check_gpg_key
  banner
  setup_pinentry
  run_build
  read_version
  update_pom_version
  sign_and_checksum
  create_release_zip
}

main "$@"

