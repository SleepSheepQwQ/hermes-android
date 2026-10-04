#!/bin/sh
# Gradle wrapper 启动脚本（POSIX）。
# 注意：gradle-wrapper.jar 是二进制，未提交到仓库；
# 首次使用前需执行 `gradle wrapper` 生成，或由 CI 的 gradle/actions/setup-gradle 提供。
# 若 jar 缺失，本脚本会尝试用系统 gradle 兜底。

set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$JAR" ]; then
  echo "gradle-wrapper.jar 缺失；尝试用系统 gradle 生成…" >&2
  if command -v gradle >/dev/null 2>&1; then
    (cd "$DIR" && gradle wrapper --gradle-version "${GRADLE_VERSION:-8.9}")
  else
    echo "错误：找不到 gradle，且 $JAR 不存在。" >&2
    echo "请在 CI 里先执行 gradle wrapper，或提交 gradle-wrapper.jar。" >&2
    exit 1
  fi
fi

# 定位 java
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="$(command -v java || true)"
fi
if [ -z "$JAVACMD" ] || [ ! -x "$JAVACMD" ]; then
  echo "错误：找不到 java。请设置 JAVA_HOME。" >&2
  exit 1
fi

exec "$JAVACMD" \
  -Xmx6144m \
  -Dorg.gradle.appname=gradlew \
  -classpath "$JAR" \
  org.gradle.wrapper.GradleWrapperMain "$@"
