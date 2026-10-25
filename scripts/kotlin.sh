# Sourced by build-android.sh and test-host.sh after they set $root and $jdk.
# Fetches a pinned, checksum-verified Kotlin compiler into build/kotlin and defines kotlinc().
kotlin_version=2.2.20
kotlin_sha256=81f0264c9073b5cbbdb3ff8418cf2c5dac076879fc156fa1a6462f5a5acc4420
kotlin_home="$root/build/kotlin/kotlinc-$kotlin_version"
kotlin_stdlib="$kotlin_home/lib/kotlin-stdlib.jar"
if [[ ! -f "$kotlin_home/lib/kotlin-compiler.jar" ]]; then
 python - "$root/build/kotlin" "$kotlin_version" "$kotlin_sha256" <<'PY'
from pathlib import Path
import hashlib,shutil,sys,urllib.request,zipfile
target,version,expected=Path(sys.argv[1]),sys.argv[2],sys.argv[3]
target.mkdir(parents=True,exist_ok=True)
archive=target/f'kotlin-compiler-{version}.zip'
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
 url=f'https://github.com/JetBrains/kotlin/releases/download/v{version}/kotlin-compiler-{version}.zip'
 print(f'Downloading {url}',file=sys.stderr)
 data=urllib.request.urlopen(url,timeout=120).read()
 if hashlib.sha256(data).hexdigest()!=expected:sys.exit(f'Checksum mismatch for {url}')
 archive.write_bytes(data)
staging=target/f'kotlinc-{version}.partial'
shutil.rmtree(staging,ignore_errors=True)
with zipfile.ZipFile(archive) as z:
 for member in z.infolist():
  name=Path(member.filename)
  if name.is_absolute() or '..' in name.parts or name.parts[0]!='kotlinc':sys.exit(f'Unexpected archive entry {member.filename}')
 z.extractall(staging)
# Replaces an incomplete earlier install (one missing kotlin-compiler.jar).
shutil.rmtree(target/f'kotlinc-{version}',ignore_errors=True)
(staging/'kotlinc').rename(target/f'kotlinc-{version}')
staging.rmdir()
PY
fi
kotlinc(){ "$jdk/bin/java" -Xmx1g -Dkotlin.home="$kotlin_home" -cp "$kotlin_home/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "$@"; }
