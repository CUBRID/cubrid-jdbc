#!/bin/bash
#
#  Copyright 2016 CUBRID Corporation
#

# Release script for CUBRID JDBC Driver
# Builds this commit with Gradle and uploads it to the Central Portal, where the deployment waits
# for Publish or Drop. The Portal token and the signing key come from ~/.gradle/gradle.properties.

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
