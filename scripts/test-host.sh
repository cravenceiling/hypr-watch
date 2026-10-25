#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jdk="${JAVA_BUILD_HOME:-${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}}"
source "$root/scripts/kotlin.sh"
src="$root/android/src/local/hyprwatch"
rm -rf "$root/build/tests/protocol" "$root/build/tests/link" "$root/build/tests/health" "$root/build/tests/notifications" "$root/build/tests/ingest" "$root/build/tests/http" "$root/build/tests/backup" "$root/build/host-tests"
mkdir -p "$root/build/tests/protocol" "$root/build/tests/link" "$root/build/tests/health" "$root/build/tests/notifications" "$root/build/tests/ingest" "$root/build/tests/http" "$root/build/tests/backup" "$root/build/host-tests"
if [[ ! -f "$root/build/tests/json.jar" ]]; then
 python - "$root/build/tests/json.jar" <<'PY'
from pathlib import Path
import sys,urllib.request
Path(sys.argv[1]).write_bytes(urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar',timeout=20).read())
PY
fi
# Real SQLite for HealthTables, pinned by checksum.
sqlite_jar="$root/build/tests/sqlite-jdbc-3.49.1.0.jar"
if [[ ! -f "$sqlite_jar" ]]; then
 python - "$sqlite_jar" 5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291 <<'PY'
from pathlib import Path
import hashlib,sys,urllib.request
data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.49.1.0/sqlite-jdbc-3.49.1.0.jar',timeout=60).read()
if hashlib.sha256(data).hexdigest()!=sys.argv[2]:sys.exit('Checksum mismatch for sqlite-jdbc')
Path(sys.argv[1]).write_bytes(data)
PY
fi
kotlinc -jvm-target 17 -Werror -d "$root/build/tests/protocol" "$src/Proto.kt" "$src/Frames.kt" "$src/WatchCrypto.kt" "$root/tests/ProtocolTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/protocol:$kotlin_stdlib" local.hyprwatch.ProtocolTest
# Watch features are Android-free *Feature.kt files; each tests/features/*Test.kt object runs here.
# HealthFeature serves the Health Connect export's routes, so the export and the health rows it reads compile here too.
kotlinc -jvm-target 17 -Werror -classpath "$root/build/tests/json.jar" -d "$root/build/tests/link" "$src/Proto.kt" "$src/Frames.kt" "$src/HealthCapture.kt" \
 "$src/HealthConnectExport.kt" "$src/HealthApi.kt" "$src/HealthIngest.kt" "$src/HealthFileId.kt" "$src"/HealthParsers*.kt \
 "$src/WatchNotifications.kt" "$src/DataUpload.kt" "$src/WatchSession.kt" "$src/WatchAuth.kt" "$src/WatchCrypto.kt" "$src/Http.kt" "$src/Phone.kt" "$src"/*Feature.kt "$src/WatchReconnect.kt" "$src/LinkGuards.kt" \
 "$root/tests/LinkTest.kt" "$root/tests/HealthCaptureTest.kt" "$root/tests/SessionTest.kt" "$root/tests/ReconnectTest.kt" "$root"/tests/features/*.kt
"$jdk/bin/java" -cp "$root/build/tests/link:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.LinkTest
"$jdk/bin/java" -cp "$root/build/tests/link:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.HealthCaptureTest
"$jdk/bin/java" -cp "$root/build/tests/link:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.SessionTest
"$jdk/bin/java" -cp "$root/build/tests/link:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.ReconnectTest
for t in "$root"/tests/features/*Test.kt; do
 "$jdk/bin/java" -cp "$root/build/tests/link:$root/build/tests/json.jar:$kotlin_stdlib" "local.hyprwatch.$(basename "$t" .kt)"
done
kotlinc -jvm-target 17 -Werror -d "$root/build/tests/health" "$src/HealthFileId.kt" "$src"/HealthParsers*.kt "$root/tests/HealthParsersTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/health:$kotlin_stdlib" local.hyprwatch.HealthParsersTest
kotlinc -jvm-target 17 -Werror -classpath "$root/build/tests/json.jar" -d "$root/build/tests/notifications" "$src/Proto.kt" "$src/WatchNotifications.kt" "$src/NotificationFilter.kt" "$root/tests/NotificationsTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/notifications:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.NotificationsTest
kotlinc -jvm-target 17 -Werror -classpath "$root/build/tests/json.jar" -d "$root/build/tests/ingest" "$src/HealthFileId.kt" "$src"/HealthParsers*.kt "$src/HealthIngest.kt" "$src/HealthApi.kt" \
 "$src/HealthTables.kt" "$src/HealthCapture.kt" "$src/Proto.kt" "$src/Frames.kt" "$src/Http.kt" "$src/HealthConnectExport.kt" \
 "$root/tests/JdbcSql.kt" "$root/tests/HealthIngestTest.kt" "$root/tests/HealthTablesTest.kt" "$root/tests/HealthConnectExportTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/ingest:$root/build/tests/json.jar:$sqlite_jar:$kotlin_stdlib" local.hyprwatch.HealthIngestTest
"$jdk/bin/java" -cp "$root/build/tests/ingest:$root/build/tests/json.jar:$sqlite_jar:$kotlin_stdlib" local.hyprwatch.HealthConnectExportTest
kotlinc -jvm-target 17 -Werror -classpath "$root/build/tests/json.jar" -d "$root/build/tests/http" "$src/Http.kt" "$root/tests/HttpTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/http:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.HttpTest
kotlinc -jvm-target 17 -Werror -d "$root/build/tests/backup" "$src/Backup.kt" "$root/tests/BackupTest.kt"
"$jdk/bin/java" -cp "$root/build/tests/backup:$kotlin_stdlib" local.hyprwatch.BackupTest
# Host stubs replace NotesStore and WatchConnection; NotesSync and SyncGuards are the real sources.
kotlinc -jvm-target 17 -Werror -classpath "$root/build/tests/json.jar" -d "$root/build/host-tests" "$root"/tests/host/local/hyprwatch/*.kt "$src/NotesSync.kt" "$src/SyncGuards.kt"
"$jdk/bin/java" -cp "$root/build/host-tests:$root/build/tests/json.jar:$kotlin_stdlib" local.hyprwatch.SyncTest

python "$root/tests/test_companion.py"
python "$root/tests/test_health_tools.py"
python "$root/tests/test_health_insights.py"
python "$root/tests/test_feature_tools.py"
