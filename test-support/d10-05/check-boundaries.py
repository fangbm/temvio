"""Read-only D10-05 diff, ownership, local links and milestone consistency gate."""
from pathlib import Path
import re, subprocess
from urllib.parse import unquote
root=Path(__file__).resolve().parents[2]
base='6f7071239df3addf6fbc2031aa8baf556d3c4f13'
changed=set(subprocess.check_output(['git','diff','--name-only',base],cwd=root,text=True).splitlines())
changed.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=root,text=True).splitlines())
errors=[]
allowed=('apps/wear/','test-support/d10-05/','docs/tasks/fixtures/d10-05/')
specific={'.github/workflows/ci.yml','test-support/d10-04/check-boundaries.py','test-support/d10-05f/check-boundaries.py','docs/tasks/D10_05_WEAR_FINAL_UX.md','docs/ROADMAP_D5_D9.md'}
for name in changed:
    if name not in specific and not name.startswith(allowed): errors.append('Outside D10-05: '+name)
for name in ('agent/WatchPermissionPolicy.kt','agent/WearAgentRuntimeComposition.kt','agent/WearAgentSessionController.kt'):
    p='apps/wear/src/main/kotlin/dev/agenticscheduler/wear/'+name
    if p in changed: errors.append('Existing runtime/ceiling must stay unchanged: '+p)
if any(n.startswith('apps/wear/src/main/kotlin/dev/agenticscheduler/wear/capability/') for n in changed): errors.append('C6/C7 semantics changed')
for p in (root/'apps/wear/src/main/kotlin/dev/agenticscheduler/wear/presentation').glob('*.kt'):
    text=p.read_text(encoding='utf-8-sig')
    if re.search(r'\b(?:\w*Dao|upsert\w*|decodeEnvelope|reserveForExplicitUser)\s*\(',text): errors.append('Presentation authority bypass: '+p.name)
    if re.search(r'import (?:dev\.agenticscheduler\.database|io\.ktor)|\b(?:ProviderCredentialTargetCoordinator|ProviderCredentialProvisioningService|ProviderTargetComparison)\s*\(',text):
        errors.append('Presentation must consume target facade and received comparison: '+p.name)
    if re.search(r'approveComparison\([^)]*\.code',text): errors.append('Comparison must use independently entered sender code: '+p.name)
for name in ('docs/tasks/D10_05_WEAR_FINAL_UX.md','docs/ROADMAP_D5_D9.md','docs/tasks/fixtures/d10-05/screenshots/README.md'):
    p=root/name
    if not p.exists(): errors.append('Missing documentation: '+name);continue
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',p.read_text(encoding='utf-8')):
        target=target.split('#',1)[0].strip('<>')
        if target and not re.match('[a-z]+:',target) and not (p.parent/unquote(target)).exists(): errors.append('Broken link: '+name+': '+target)
roadmap=(root/'docs/ROADMAP_D5_D9.md').read_text(encoding='utf-8')
for required in ('D10-04 MERGED PR #37','93a2aab9714a729ed2f53e60e0077372a237cbf7','D10-05F','MERGED PR #40',base,'D10-06 unstarted','OD-012 OPEN','FG-02','FG-03','FG-04'):
    if required not in roadmap: errors.append('Missing roadmap fact: '+required)
if re.search(r'D10-05(?![A-Z0-9-])\s+(?:Wear Final UX\s+)?(?:MERGED|COMPLETE)\b',roadmap): errors.append('D10-05 acceptance overstated')
task=(root/'docs/tasks/D10_05_WEAR_FINAL_UX.md').read_text(encoding='utf-8')
for required in (base,'RESOLVED via D10-05F PR #40','independent','SAS','ACK'):
    if required not in task: errors.append('Missing task fact: '+required)
subprocess.run(['python', 'test-support/d10-05f/check-boundaries.py', '--source-only'],cwd=root,check=True)
if errors: raise SystemExit('\n'.join(errors))
print('D10-05 diff/ownership/Markdown/status PASS; frozen runtime, security, semantic modules and historical fixtures unchanged')
