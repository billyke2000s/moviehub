#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/../.."
build=android-tv-launcher/app/build
filters=(
    'VodSessionTest'
    'VodSessionPlaybackTest'
    'PlaybackTest#vodRepeatedBufferingPreparesBackupsWithoutStoppingCurrentPlayback'
    'PlaybackTest#shortInitialFreeClipWaitsForLateBackupWithoutEndingMovie'
)
minimum=(3 5 1 1)
result=0
for index in "${!filters[@]}"; do
    target="local.tv.home.${filters[$index]}"
    if ! bash android-tv-launcher/tests/run-device-tests.sh "-Pandroid.testInstrumentationRunnerArguments.class=$target"; then
        result=1
    fi
    if ! python3 - "$build/outputs/androidTest-results" "$target" "${minimum[$index]}" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
name,_,method=sys.argv[2].partition('#')
cases=[]
for path in pathlib.Path(sys.argv[1]).rglob('*.xml'):
    root=ET.parse(path).getroot()
    cases.extend(c for c in root.iter('testcase') if c.get('classname')==name and
                 (not method or c.get('name')==method))
if len(cases)<int(sys.argv[3]) or any(c.find('failure') is not None or c.find('error') is not None or
                                   c.find('skipped') is not None for c in cases):
    raise SystemExit('Required VOD filter missing or unsuccessful: '+sys.argv[2]+' ('+str(len(cases))+' cases)')
print('Verified VOD filter: '+sys.argv[2]+': '+str(len(cases))+' passing tests, none skipped')
PY
    then
        result=1
    fi
    mkdir -p "$build/reports/vod-recovery/$index"
    cp -a "$build/reports/androidTests" "$build/reports/vod-recovery/$index/" || true
    cp "$build/device-tests.log" "$build/reports/vod-recovery/$index/" || true
done
exit "$result"
