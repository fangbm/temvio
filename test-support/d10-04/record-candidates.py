"""Explicitly collect new native captures as review candidates, never visual approval."""
from pathlib import Path
import hashlib, json, shutil, struct, re
root=Path(__file__).resolve().parents[2]
folder=root/'docs/tasks/fixtures/d10-04/screenshots'
folder.mkdir(parents=True,exist_ok=True)
sources=['apps/desktop/build/d10-04/screenshots','build/d10-04/android-captures/d10-04-screenshots']
candidates=[]
for source in sources:
    captures=sorted((root/source).glob('*.png'))
    assert captures,source
    for p in captures:
        # Standalone instrumentation also records the host emulator's default
        # dimensions. Retain only the explicitly requested review matrix here.
        if p.name.startswith('android-'):
            dimensions=re.match(r'android-(\d+x\d+)-font(\d+)-',p.name)
            allowed={(size,'100') for size in ['360x800','480x900','600x960','840x900','1024x768','800x360']} | {('360x800','200')}
            if not dimensions or (dimensions[1],dimensions[2]) not in allowed:
                continue
        data=p.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n',p
        shutil.copyfile(p,folder/p.name)
        candidates.append(dict(file=p.name,generatedFrom=p.relative_to(root).as_posix(),
            bitmapPixels=list(struct.unpack('>II',data[16:24])),sha256=hashlib.sha256(data).hexdigest()))
paths=list((root/'apps/presentation/src/main/kotlin/dev/agenticscheduler/presentation').glob('Agent*.kt'))
paths += [root/p for p in ['apps/desktop/src/main/kotlin/dev/agenticscheduler/desktop/DesktopApp.kt',
    'apps/android/src/main/kotlin/dev/agenticscheduler/android/AndroidApp.kt',
    'apps/desktop/src/test/kotlin/dev/agenticscheduler/desktop/AgentWorkspaceUiTest.kt',
    'apps/android/src/androidTest/kotlin/dev/agenticscheduler/android/AgentWorkspaceInstrumentedTest.kt',
    'test-support/d10-04/D10AgentFixtureGraph.kt']]
manifest=dict(baseSha='cdc8504527cc636f5d2932b7e00785f4be9ecaad',status='CANDIDATES / AWAITING MAINTAINER VISUAL REVIEW',
    containsRealSecrets=False,fixture='Existing AgentRunService -> typed Tools -> Application -> Room, synthetic MockEngine HTTP peer; no live Provider acceptance.',
    nativeEvidence='Actual Desktop Compose/Skia + Android API35 Google APIs x86_64 emulator. Native clock/chrome is not deterministic; no physical-device claim.',
    sourceHashNormalization='UTF-8 text / no BOM / LF',sourceHashes={p.relative_to(root).as_posix():hashlib.sha256(p.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode()).hexdigest() for p in sorted(paths)},
    candidates=candidates)
(folder/'capture-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(f'{len(candidates)} new real captures recorded; visual approval remains pending')
