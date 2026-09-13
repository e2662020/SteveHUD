#!/bin/sh
set -e

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "BoardcastMod: gradle-wrapper.jar is missing, trying to find/download it..." >&2
    mkdir -p "$DIR/gradle/wrapper"
    if [ -f "$DIR/../MOBmod/gradle/wrapper/gradle-wrapper.jar" ]; then
        cp "$DIR/../MOBmod/gradle/wrapper/gradle-wrapper.jar" "$WRAPPER_JAR"
    elif command -v curl >/dev/null 2>&1; then
        curl -fsSL "https://raw.githubusercontent.com/gradle/gradle/v9.4.0/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "https://raw.githubusercontent.com/gradle/gradle/v9.4.0/gradle/wrapper/gradle-wrapper.jar" -O "$WRAPPER_JAR"
    else
        echo "curl or wget is required, or open this folder in IntelliJ IDEA / use a local Gradle 9.4 installation." >&2
        exit 1
    fi
fi

exec java -classpath "$WRAPPER_JAR" -Dorg.gradle.appname=gradlew org.gradle.wrapper.GradleWrapperMain "$@"
