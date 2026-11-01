#!/usr/bin/env python3
"""Build Disks with separately obtained, fingerprinted Hytale and Mega Bench inputs."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,zipfile
from release_pipeline import config,filename
R=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--server-jar',type=Path,default=os.environ.get('HYTALE_SERVER_JAR'))
p.add_argument('--mega-bench-jar',type=Path,default=os.environ.get('MEGA_BENCH_JAR'))
p.add_argument('--java-home',type=Path,default=os.environ.get('JAVA_HOME'))
a=p.parse_args();pin=json.loads((R/'config/target.json').read_text())
if not a.server_jar or not a.mega_bench_jar:p.error('Supply --server-jar and --mega-bench-jar (or their environment variables).')
if sha(a.server_jar)!=pin['server_sha256']:p.error('Hytale SDK fingerprint differs from config/target.json.')
depfile=R/'config/mega-bench.json'
if depfile.exists() and sha(a.mega_bench_jar)!=json.loads(depfile.read_text())['sha256']:p.error('Mega Bench dependency fingerprint differs from config/mega-bench.json.')
with zipfile.ZipFile(a.mega_bench_jar) as z:
 if 'dev/megabench/CompanionExtension.class' not in z.namelist():p.error('Mega Bench companion API1 is required.')
javac=str(a.java_home/'bin/javac') if a.java_home else shutil.which('javac');java=str(a.java_home/'bin/java') if a.java_home else shutil.which('java')
if not javac or not subprocess.check_output([javac,'-version'],text=True).startswith('javac 25'):p.error('JDK25 is required.')
classes=R/'build/classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir(parents=True)
cp=os.pathsep.join(map(str,[a.server_jar,a.mega_bench_jar]))
subprocess.run([javac,'--release','25','-Xlint:all','-cp',cp,'-d',str(classes),*map(str,sorted((R/'src/main/java').rglob('*.java')))],check=True)
tests=sorted((R/'src/test/java').rglob('*.java'));testout=R/'build/tests';testout.mkdir(parents=True,exist_ok=True)
if tests:
 subprocess.run([javac,'--release','25','-cp',os.pathsep.join([str(classes),cp]),'-d',str(testout),*map(str,tests)],check=True)
 subprocess.run([java,'-ea','-cp',os.pathsep.join([str(testout),str(classes),cp]),'dev.megabench.disks.RackConnectivityTest'],check=True)
entries={p.relative_to(d).as_posix():p.read_bytes() for d in [classes,R/'src/main/resources'] for p in d.rglob('*') if p.is_file()}
for notice in ['LICENSE','THIRD_PARTY_NOTICES.md']:
 if entries.get(notice)!=(R/notice).read_bytes():raise RuntimeError('Missing or changed packaged notice: '+notice)
if any(x.startswith(('dev/megabench/CompanionExtension','dev/megabench/Bench','dev/megabench/disks/StorageProbe')) for x in entries):raise RuntimeError('Dependency or probe classes must not be bundled.')
output=R/'build/libs'/filename(config(R/'release.json'));output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for n,data in sorted(entries.items()):
  i=zipfile.ZipInfo(n,(2026,10,25,0,0,0));i.compress_type=zipfile.ZIP_DEFLATED;i.external_attr=0o100644<<16;z.writestr(i,data)
print(output.relative_to(R));print('SHA256 '+sha(output))
