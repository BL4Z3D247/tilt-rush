#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Gradle wrapper JAR is missing; downloading the official Gradle 8.9 wrapper..."
    mkdir -p "$(dirname "$WRAPPER_JAR")"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --retry 3 --output "$WRAPPER_JAR" "$WRAPPER_URL" || exit 1
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$WRAPPER_JAR" "$WRAPPER_URL" || exit 1
    else
        echo "ERROR: Install curl or wget, then run ./gradlew again." >&2
        exit 1
    fi
fi

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if ! command -v "$JAVACMD" >/dev/null 2>&1 && [ ! -x "$JAVACMD" ]; then
    echo "ERROR: Java was not found. Install JDK 17 or set JAVA_HOME." >&2
    exit 1
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
  -Dorg.gradle.appname=gradlew \
  -classpath "$WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain "$@"
