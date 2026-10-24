"""Copy actual Wear emulator captures into the new review set, never approve appearance."""
from pathlib import Path
import hashlib,json,re,shutil,struct
root=Path(__file__).resolve().parents[2]
source=root/'build/d10-05/wear-captures/d10-05-screenshots'
dest=root/'docs/tasks/fixtures/d10-05/screenshots'
dest.mkdir(parents=True,exist_ok=True)
items=[]
for p in sorted(source.glob('*.png')):
    m=re.fullmatch(r'wear-(192|227)x\1-font(100|200)-(light|dark)-(.+)\.png',p.name)
    if not m: continue
    data=p.read_bytes(); assert data[:8]==b'\x89PNG\r\n\x1a\n'
    shutil.copyfile(p,dest/p.name)
    items.append(dict(file=p.name,logicalDp=[int(m[1])]*2,bitmapPixels=list(struct.unpack('>II',data[16:24])),
        profile='wearos_small_round' if m[1]=='192' else 'wearos_large_round',densityDpi=320,fontScale=int(m[2])/100,theme=m[3],scene=m[4],sha256=hashlib.sha256(data).hexdigest()))
assert items,'No actual Wear captures'
paths=sorted((root/'apps/wear/src/main/kotlin').rglob('*.kt'))
paths+=[root/'apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/WearFinalUxInstrumentedTest.kt',root/'apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/CredentialSetupFixtures.kt',root/'apps/wear/src/androidTest/kotlin/dev/agenticscheduler/wear/WearCredentialSetupInstrumentedTest.kt',root/'apps/wear/build.gradle.kts']
manifest=dict(baseSha='6f7071239df3addf6fbc2031aa8baf556d3c4f13',status='CANDIDATES / AWAITING MAINTAINER VISUAL REVIEW',
    platform='Wear emulator acceptance; no physical Watch or speech recognition success claim',
    avds=[dict(name='temvio-d10-05',profile='wearos_small_round',physicalPixels=[384,384]),dict(name='temvio-d10-05-cold-large',profile='wearos_large_round',physicalPixels=[454,454])],apiLevel=35,systemImage='android-35-ext15/android-wear/x86_64',round=True,
    fixture='Synthetic typed presentation inputs; retained real AgentRunService/Room/Android HTTP E2E is separate execution evidence',
    containsRealSecrets=False,sourceHashNormalization='UTF-8 without BOM / LF',
    sourceHashes={p.relative_to(root).as_posix():hashlib.sha256(p.read_text(encoding='utf-8-sig').replace('\r\n','\n').encode()).hexdigest() for p in paths},
    candidates=items)
(dest/'capture-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(f'{len(items)} actual Wear candidates recorded; visual acceptance remains pending')
