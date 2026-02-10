#!/bin/bash
set -e

# 1. System Dependencies
if ! command -v java >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v cmake >/dev/null 2>&1; then
    echo "Installing missing system packages..."
    apt-get update -qq
    apt-get install -y -qq openjdk-21-jdk wget unzip lib32z1 curl gpg cmake ninja-build build-essential
else
    echo "System dependencies already present, skipping apt install."
fi

# 2. Parse Gradle Version from Wrapper Properties
PROPERTIES_FILE="${CLAUDE_PROJECT_DIR:-.}/android-app-wamr/gradle/wrapper/gradle-wrapper.properties"

if [ ! -f "$PROPERTIES_FILE" ]; then
    echo "Error: gradle-wrapper.properties not found at $PROPERTIES_FILE"
    exit 1
fi

DIST_URL=$(grep 'distributionUrl' "$PROPERTIES_FILE" | cut -d'=' -f2 | sed 's/\\//g')
GRADLE_VERSION=$(echo "$DIST_URL" | grep -oP 'gradle-\K[0-9.]+(?=-bin|-all)')

# 3. Manual Gradle Installation
GRADLE_INSTALL_BASE="/opt/gradle"
GRADLE_HOME="$GRADLE_INSTALL_BASE/gradle-$GRADLE_VERSION"

if [ ! -d "$GRADLE_HOME" ]; then
    echo "Installing Gradle $GRADLE_VERSION (not found at $GRADLE_HOME)..."
    wget -q "$DIST_URL" -O /tmp/gradle.zip
    mkdir -p "$GRADLE_INSTALL_BASE"
    unzip -q /tmp/gradle.zip -d "$GRADLE_INSTALL_BASE"
    rm /tmp/gradle.zip
else
    echo "Gradle $GRADLE_VERSION already installed at $GRADLE_HOME."
fi

# 4. GitHub CLI
if ! command -v gh >/dev/null 2>&1; then
    echo "Installing GitHub CLI..."
    mkdir -p -m 755 /etc/apt/keyrings
    if [ ! -f /etc/apt/keyrings/githubcli-archive-keyring.gpg ]; then
        wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg | tee /etc/apt/keyrings/githubcli-archive-keyring.gpg > /dev/null
    fi
    chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | tee /etc/apt/sources.list.d/github-cli.list > /dev/null
    apt-get update -qq
    apt-get install -y -qq gh
else
    echo "GitHub CLI already installed."
fi

echo "Setup complete."
