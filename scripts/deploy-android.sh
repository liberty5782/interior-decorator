#!/usr/bin/env bash
# Build the debug APK and stage it on arrbox's nginx /apps/ share for download on the phone.
# Same mechanism as photo-share/scripts/deploy-android.sh (nginx block in its nginx-apps.conf).
set -euo pipefail

if [[ $EUID -eq 0 ]]; then
    echo "Don't run this with sudo: Gradle would start a second set of root daemons" >&2
    echo "(out-of-memory risk), leave root-owned files in android/, and ssh as root." >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
APK_SRC="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

DEPLOY_USER="bigpi"
DEPLOY_HOST="192.168.0.153"
DEPLOY_DIR="/var/www/html/apps"
DEPLOY_FILE="roomplanner.apk"

# Gradle needs a full JDK (javac), not just a JRE. The default java on this box is a
# JRE-only 21, so fall back to the first installed JDK if JAVA_HOME doesn't have javac.
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
    JAVAC="$(ls /usr/lib/jvm/*/bin/javac 2>/dev/null | head -n1 || true)"
    if [[ -z "$JAVAC" ]]; then
        echo "No JDK with javac found; install openjdk-17-jdk or set JAVA_HOME." >&2
        exit 1
    fi
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"
fi
echo "==> Using JDK at $JAVA_HOME"

echo "==> Building debug APK..."
# Compile Kotlin inside the Gradle JVM instead of a separate ~2 GB Kotlin daemon,
# and don't leave a daemon running afterwards. Slower, but fits in 8 GB of RAM.
# gradle.properties asks for a 4 GB heap; cap it at 2 GB here so the build isn't OOM-killed.
(cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process \
    "-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8")

echo "==> Ensuring $DEPLOY_DIR exists on server..."
ssh "$DEPLOY_USER@$DEPLOY_HOST" "mkdir -p $DEPLOY_DIR"

echo "==> Deploying APK..."
scp "$APK_SRC" "$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/$DEPLOY_FILE"

echo ""
echo "Done."
echo "  Local:     http://$DEPLOY_HOST/apps/$DEPLOY_FILE"
echo "  Tailscale: http://100.64.0.1/apps/$DEPLOY_FILE"
