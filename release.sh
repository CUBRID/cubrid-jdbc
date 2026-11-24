#!/bin/bash
#
#  Copyright 2016 CUBRID Corporation
#

# Release script for CUBRID JDBC Driver
# Builds this commit with Gradle and uploads it to the Central Portal, where the deployment waits
# for Publish or Drop. It reads these Gradle properties from ~/.gradle/gradle.properties:
#   sonatypeUsername, sonatypePassword                the Central Portal user token
#   signing.gnupg.keyName, signing.gnupg.passphrase   the key the local gpg signs with
# To sign without gpg, give signingKey and signingPassword (an ASCII-armored secret key and its
# passphrase) instead, for example as ORG_GRADLE_PROJECT_signingKey in the environment.

set -e
cd "$(dirname "$0")"

# A shallow clone counts too few commits for the serial, and the jars are built from the working
# tree. Untracked files count only under src/, the only place the jars take them from, so IDE files
# elsewhere do not block a release.
if [ "$(git rev-parse --is-shallow-repository)" != false ] || ! git diff --quiet HEAD ||
    [ -n "$(git ls-files --others --exclude-standard -- src)" ]; then
    echo "release.sh: run it from a full clone with no local changes" >&2
    exit 1
fi

./gradlew -I gradle/central-publish.init.gradle publishToSonatype closeSonatypeStagingRepository
