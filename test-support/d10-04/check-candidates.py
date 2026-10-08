"""Read-only new capture/source/matrix validation; no historical evidence rewritten."""
from pathlib import Path
import hashlib,json,re,struct,sys
root=Path(__file__).resolve().parents[2]
folder=root/'docs/tasks/fixtures/d10-04/screenshots'
manifest=json.loads((folder/'capture-manifest.json').read_text(encoding='utf-8'))
names=set()
for item in manifest['candidates']:
    p=folder/item['file'];data=p.read_bytes()
    assert p.name not in names,p
    names.add(p.name)
    assert data[:8]==b'\x89PNG\r\n\x1a\n',p
    assert list(struct.unpack('>II',data[16:24]))==item['bitmapPixels'],p
    dimensions=re.match(r'(?:desktop|android)-(\d+)x(\d+)-',p.name)
    assert dimensions and [int(dimensions[1]),int(dimensions[2])]==item['bitmapPixels'],p
    assert hashlib.sha256(data).hexdigest()==item['sha256'],p
    if '--compare-generated' in sys.argv:
        assert hashlib.sha256((root/item['generatedFrom']).read_bytes()).hexdigest()==item['sha256'],p
for name,expected in manifest['sourceHashes'].items():
    text=(root/name).read_text(encoding='utf-8-sig').replace('\r\n','\n')
    assert hashlib.sha256(text.encode()).hexdigest()==expected,name
for platform,sizes in [('desktop',['640x720','800x600','1024x768','1280x800','1440x900','1920x1080','800x360']),
                       ('android',['360x800','480x900','600x960','840x900','1024x768','800x360'])]:
    for size in sizes:
        for theme in ['light','dark']:
            assert any(n.startswith(f'{platform}-{size}-font100-{theme}-conversation') for n in names),(platform,size,theme)
    for theme in ['light','dark']:
        for scene in ['conversation','confirmation','permissions']:
            assert any(n.startswith(platform+'-') and f'-font200-{theme}-{scene}.png' in n for n in names),(platform,theme,scene)
    for scene in ['tool-result','confirmation','stale','denied','chat-only','threads','provider-status']:
        assert any(n.startswith(platform+'-') and n.endswith('-'+scene+'.png') for n in names),(platform,scene)
assert manifest['baseSha']=='cdc8504527cc636f5d2932b7e00785f4be9ecaad'
assert manifest['containsRealSecrets'] is False
assert {p.name for p in folder.glob('*.png')}==names,'Unrecorded/stale candidate file'
print(f'{len(names)} D10-04 real Compose candidate/source hashes + responsive/theme matrix PASS; maintainer visual approval pending')
