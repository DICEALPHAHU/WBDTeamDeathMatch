#!/usr/bin/env bash
# 不依赖 Maven 的构建脚本：javac --release 17 + jar
#
# 产物：target/WBDTDM-<版本>-<内部构建号>-<git短哈希>-<时间戳>.jar
# 内部构建号每次自增，存在 buildNumber.properties（已 gitignore）。
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$REPO/target/build"
M2="${M2_REPO:-$HOME/.m2/repository}"

PAPER="$M2/io/papermc/paper/paper-api/1.20.1-R0.1-SNAPSHOT/paper-api-1.20.1-R0.1-SNAPSHOT.jar"
SQLITE="$M2/org/xerial/sqlite-jdbc/3.42.0.0/sqlite-jdbc-3.42.0.0.jar"

if [ ! -f "$PAPER" ]; then
  echo "找不到 paper-api，请先执行 mvn dependency:resolve，或用 M2_REPO 指定本地仓库" >&2
  exit 1
fi
if [ ! -f "$REPO/libs/WBDAddon.jar" ]; then
  echo "缺少 libs/WBDAddon.jar —— 先构建 WBDAddon 并放到 libs/ 下（见 libs/README.md）" >&2
  exit 1
fi

CP="$PAPER:$REPO/libs/WBDAddon.jar:$SQLITE"
for j in \
  "$M2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar" \
  "$M2/net/kyori/adventure-api/4.14.0/adventure-api-4.14.0.jar" \
  "$M2/net/kyori/adventure-text-serializer-legacy/4.14.0/adventure-text-serializer-legacy-4.14.0.jar" \
  "$M2/net/kyori/adventure-key/4.14.0/adventure-key-4.14.0.jar" \
  "$M2/net/md-5/bungeecord-chat/1.20-R0.2/bungeecord-chat-1.20-R0.2.jar" \
  "$M2/org/jetbrains/annotations/24.0.1/annotations-24.0.1.jar" \
  "$M2/net/kyori/examination-api/1.3.0/examination-api-1.3.0.jar" \
  "$M2/com/google/code/gson/gson/2.10/gson-2.10.jar" ; do
  [ -f "$j" ] && CP="$CP:$j"
done

rm -rf "$OUT" && mkdir -p "$OUT/classes"
find "$REPO/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -encoding UTF-8 -nowarn -cp "$CP" -d "$OUT/classes" "@$OUT/sources.txt"

cp -r "$REPO/src/main/resources/." "$OUT/classes/"

VERSION=$(grep -m1 '<version>' "$REPO/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
SHA=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo nogit)
STAMP=$(date +%Y%m%d-%H%M)

BUILD_FILE="$REPO/buildNumber.properties"
BUILD=$(sed -n 's/^buildNumber=//p' "$BUILD_FILE" 2>/dev/null | head -1)
BUILD=$((${BUILD:-0} + 1))
printf 'buildNumber=%s\n' "$BUILD" > "$BUILD_FILE"

sed -i "s/\${project.version}/$VERSION/" "$OUT/classes/plugin.yml"

JAR="$REPO/target/WBDTDM-$VERSION-$BUILD-$SHA-$STAMP.jar"
( cd "$OUT/classes" && jar --create --file "$JAR" . )
echo "已构建：$JAR"
