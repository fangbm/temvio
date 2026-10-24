"""Read-only Wear renderer/source/matrix evidence check; never visual approval."""
from pathlib import Path
import hashlib,json,struct
root=Path(__file__).resolve().parents[2]
folder=root/'docs/tasks/fixtures/d10-05/screenshots'
m=json.loads((folder/'capture-manifest.json').read_text(encoding='utf-8'))
assert m['baseSha']=='6f7071239df3addf6fbc2031aa8baf556d3c4f13'
assert m['round'] is True and m['containsRealSecrets'] is False
names=set()
for item in m['candidates']:
    p=folder/item['file'];data=p.read_bytes();assert data[:8]==b'\x89PNG\r\n\x1a\n'
    assert item['file'] not in names;names.add(item['file'])
    assert hashlib.sha256(data).hexdigest()==item['sha256']
    assert list(struct.unpack('>II',data[16:24]))==item['bitmapPixels']
    assert item['bitmapPixels']==[int(v*item['densityDpi']/160) for v in item['logicalDp']]
    assert item['logicalDp'] in ([192,192],[227,227])
    assert item['profile']==('wearos_small_round' if item['logicalDp'][0]==192 else 'wearos_large_round')
for name,value in m['sourceHashes'].items():
    assert hashlib.sha256((root/name).read_text(encoding='utf-8-sig').replace('\r\n','\n').encode()).hexdigest()==value,name
for size,scenes in [(192,['today','ready','offline','confirmation','confirmation-actions','status']),
                    (227,['today','conversation','tool-result','confirmation','confirmation-actions','status'])]:
    for scene in scenes:
        for theme in ('light','dark'): assert f'wear-{size}x{size}-font100-{theme}-{scene}.png' in names
for scene in ('chat-only','sync-stopped'): assert f'wear-192x192-font100-dark-{scene}.png' in names
for size in (192,227):
    for scene in ('confirmation-actions','status'): assert f'wear-{size}x{size}-font200-dark-{scene}.png' in names
for size,scenes in ((192,['credential-ready','credential-comparison','credential-approved','credential-installed']), (227,['credential-comparison','credential-installed'])):
    for scene in scenes:
        theme='light' if scene in ('credential-ready','credential-installed') else 'dark'
        assert f'wear-{size}x{size}-font100-{theme}-{scene}.png' in names
for size in (192,227):
    assert f'wear-{size}x{size}-font200-dark-credential-approved.png' in names
assert len(names)==38
assert {p.name for p in folder.glob('*.png')}==names
print(f'{len(names)} actual Wear PNG/source hashes + 192/227 round/Light/Dark/scaled matrix PASS; visual review pending')
