#!/usr/bin/env bash
# =====================================================================
#  FaceAuth MFA - one-command setup + start
#
#  Usage:   ./start.sh              install what's missing, then start the site
#           ./start.sh --test       also run the unit tests first
#           ./start.sh --mysql      use MySQL instead of the built-in H2 database
#           ./start.sh --reset-db   delete the local H2 database before starting
#           PORT=9090 ./start.sh    run on a different port (default 8080)
#
#  What it does:
#    1. Checks for Java 17+. If missing, downloads Eclipse Temurin JDK 17
#       into ./.tools/ (no admin/sudo needed, nothing installed system-wide).
#    2. Checks for Maven. If missing, downloads Apache Maven into ./.tools/.
#    3. Downloads all Java libraries (Spring Boot, H2, MySQL driver, ZXing...)
#       and compiles the project.
#    4. Starts the website at http://localhost:8080
#
#  Works on macOS, Linux and Windows (Git Bash / WSL).
#  The face-recognition AI model loads in the browser from a CDN, so it
#  needs no install step.
# =====================================================================
set -euo pipefail

JAVA_REQUIRED=17
MAVEN_VERSION=3.9.9
PORT="${PORT:-8080}"

# Always run from the folder this script lives in
cd "$(dirname "$0")"
ROOT="$(pwd)"
TOOLS="$ROOT/.tools"

RUN_TESTS=false
PROFILE=""
RESET_DB=false
for arg in "$@"; do
  case "$arg" in
    --test)     RUN_TESTS=true ;;
    --mysql)    PROFILE="mysql" ;;
    --reset-db) RESET_DB=true ;;
    -h|--help)  sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg (try --help)"; exit 1 ;;
  esac
done

# ---------- pretty output ----------
if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; Y=$'\e[33m'; R=$'\e[31m'; N=$'\e[0m'; else B= G= Y= R= N=; fi
step() { echo; echo "${B}==> $*${N}"; }
ok()   { echo "  ${G}✔${N} $*"; }
warn() { echo "  ${Y}!${N} $*"; }
die()  { echo "  ${R}✘ $*${N}" >&2; exit 1; }

# ---------- detect OS / CPU ----------
case "$(uname -s)" in
  Linux*)               OS=linux ;;
  Darwin*)              OS=mac ;;
  MINGW*|MSYS*|CYGWIN*) OS=windows ;;
  *) die "Unsupported operating system: $(uname -s)" ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  ARCH=x64 ;;
  arm64|aarch64) ARCH=aarch64 ;;
  *) die "Unsupported CPU architecture: $(uname -m)" ;;
esac

download() {  # download <url> <output-file>
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --progress-bar -o "$2" "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -q --show-progress -O "$2" "$1"
  else
    die "Need curl or wget to download tools. Please install one of them."
  fi
}

extract() {  # extract <archive> <dest-dir>
  mkdir -p "$2"
  case "$1" in
    *.zip)
      if command -v unzip >/dev/null 2>&1; then unzip -q "$1" -d "$2"
      else powershell.exe -NoProfile -Command "Expand-Archive -Force '$1' '$2'"; fi ;;
    *) tar -xzf "$1" -C "$2" ;;
  esac
}

java_major() {  # prints major version of the given java binary, or 0
  local v
  v=$("$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}') || true
  [ -z "$v" ] && { echo 0; return; }
  case "$v" in
    1.*) echo "$v" | cut -d. -f2 ;;              # 1.8.0_x -> 8
    *)   echo "$v" | cut -d. -f1 | cut -d- -f1 ;; # 17.0.9 -> 17, 21-ea -> 21
  esac
}

echo "${B}FaceAuth MFA - setup & start${N}  ($OS/$ARCH)"

# =====================================================================
# 1. Java
# =====================================================================
step "Checking Java (need $JAVA_REQUIRED or newer)"

JAVA_OK=false
LOCAL_JDK="$TOOLS/jdk"

# Prefer a JDK we downloaded earlier
if [ -x "$LOCAL_JDK/bin/java" ] || [ -x "$LOCAL_JDK/bin/java.exe" ]; then
  export JAVA_HOME="$LOCAL_JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
  JAVA_OK=true
  ok "Using project-local JDK in .tools/jdk"
elif command -v java >/dev/null 2>&1 && command -v javac >/dev/null 2>&1; then
  V=$(java_major java)
  if [ "$V" -ge "$JAVA_REQUIRED" ] 2>/dev/null; then
    JAVA_OK=true
    ok "Found Java $V"
  else
    warn "Found Java $V, but $JAVA_REQUIRED+ is required"
  fi
else
  warn "Java JDK not found"
fi

if [ "$JAVA_OK" = false ]; then
  step "Downloading Eclipse Temurin JDK $JAVA_REQUIRED (one time, ~190 MB)"
  mkdir -p "$TOOLS"
  EXT=tar.gz; [ "$OS" = windows ] && EXT=zip
  ARCHIVE="$TOOLS/jdk.$EXT"
  download "https://api.adoptium.net/v3/binary/latest/$JAVA_REQUIRED/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse" "$ARCHIVE"
  rm -rf "$TOOLS/jdk-tmp" "$LOCAL_JDK"
  extract "$ARCHIVE" "$TOOLS/jdk-tmp"
  JDK_DIR=$(find "$TOOLS/jdk-tmp" -mindepth 1 -maxdepth 1 -type d | head -n1)
  [ "$OS" = mac ] && [ -d "$JDK_DIR/Contents/Home" ] && JDK_DIR="$JDK_DIR/Contents/Home"
  mv "$JDK_DIR" "$LOCAL_JDK"
  rm -rf "$TOOLS/jdk-tmp" "$ARCHIVE"
  export JAVA_HOME="$LOCAL_JDK"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "Installed JDK to .tools/jdk ($(java_major java))"
fi

# =====================================================================
# 2. Maven
# =====================================================================
step "Checking Maven"

LOCAL_MVN="$TOOLS/maven"
if [ -x "$LOCAL_MVN/bin/mvn" ]; then
  export PATH="$LOCAL_MVN/bin:$PATH"
  ok "Using project-local Maven in .tools/maven"
elif command -v mvn >/dev/null 2>&1; then
  ok "Found $(mvn -v 2>/dev/null | head -n1 | sed 's/ (.*//')"
else
  warn "Maven not found"
  step "Downloading Apache Maven $MAVEN_VERSION (one time, ~9 MB)"
  mkdir -p "$TOOLS"
  ARCHIVE="$TOOLS/maven.tar.gz"
  download "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" "$ARCHIVE"
  rm -rf "$LOCAL_MVN" "$TOOLS/apache-maven-$MAVEN_VERSION"
  extract "$ARCHIVE" "$TOOLS"
  mv "$TOOLS/apache-maven-$MAVEN_VERSION" "$LOCAL_MVN"
  rm -f "$ARCHIVE"
  chmod +x "$LOCAL_MVN/bin/mvn"
  export PATH="$LOCAL_MVN/bin:$PATH"
  ok "Installed Maven to .tools/maven"
fi

# =====================================================================
# 3. Project dependencies + compile
# =====================================================================
step "Downloading Java libraries and compiling (first run takes a few minutes)"
MVN_ARGS=(-B -ntp)
if [ "$RUN_TESTS" = true ]; then
  mvn "${MVN_ARGS[@]}" test || die "Tests failed - see output above."
  ok "All tests passed"
else
  mvn "${MVN_ARGS[@]}" -DskipTests compile || die "Build failed - see output above."
  ok "Build succeeded"
fi

# =====================================================================
# 4. Database
# =====================================================================
step "Database"
if [ -n "$PROFILE" ]; then
  ok "Using MySQL (settings in src/main/resources/application-mysql.properties)"
  warn "Make sure MySQL is running and you ran: CREATE DATABASE faceauth;"
else
  if [ "$RESET_DB" = true ]; then
    rm -rf "$ROOT/data"
    ok "Deleted local H2 database (fresh start)"
  fi
  ok "Using built-in H2 database (saved in ./data/, tables created automatically)"
fi

# =====================================================================
# 5. Start
# =====================================================================
if command -v lsof >/dev/null 2>&1 && lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "Port $PORT is already in use. Stop the other app or run: PORT=9090 ./start.sh"
fi

step "Starting FaceAuth"
echo "  Open ${B}http://localhost:$PORT${N} in Chrome, Edge or Firefox"
echo "  (the webcam only works on localhost or https)"
echo "  Press Ctrl+C to stop."
echo

RUN_ARGS=(-B -ntp spring-boot:run "-Dspring-boot.run.arguments=--server.port=$PORT")
[ -n "$PROFILE" ] && RUN_ARGS+=("-Dspring-boot.run.profiles=$PROFILE")
exec mvn "${RUN_ARGS[@]}"
