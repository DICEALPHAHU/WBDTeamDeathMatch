#!/usr/bin/env bash
# 不依赖 Maven 的构建脚本：javac --release 17 + jar
#
# 两个版本号：
#   对外版本 public-version.txt（玩家看到的，写进 plugin.yml）
#   内部版本 pom.xml 的 <version>（每次更新自增，只在产物名里体现）
#
# 产物：target/WBDTDM-<对外>-<内部>-<git短哈希>-<时间戳>.jar
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

PUBLIC=$(tr -d '[:space:]' < "$REPO/public-version.txt")
INTERNAL=$(grep -m1 '<version>' "$REPO/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
SHA=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo nogit)
STAMP=$(date +%Y%m%d-%H%M)

sed -i "s/\${project.version}/$PUBLIC/" "$OUT/classes/plugin.yml"
printf 'git.commit.id.abbrev=%s\ngit.build.time=%s\nversion.public=%s\nversion.internal=%s\n' \
  "$SHA" "$STAMP" "$PUBLIC" "$INTERNAL" > "$OUT/classes/git.properties"

JAR="$REPO/target/WBDTDM-$PUBLIC-$INTERNAL-$SHA-$STAMP.jar"
( cd "$OUT/classes" && jar --create --file "$JAR" . )
echo "已构建：$JAR（对外 $PUBLIC / 内部 $INTERNAL）"
