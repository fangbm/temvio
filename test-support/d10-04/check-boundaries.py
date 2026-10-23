"""Read-only D10-04 ownership, scope, local links and milestone status check."""
from pathlib import Path
import re, subprocess, sys
from urllib.parse import unquote
root=Path(__file__).resolve().parents[2]
errors=[]
base='17fd6e7ef117cdb899b7e2f28d3a1a8bf13a58ed'
source_only='--source-only' in sys.argv
changed=set() if source_only else set(subprocess.check_output(['git','diff','--name-only',base],cwd=root,text=True).splitlines())
if not source_only:
    changed.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=root,text=True).splitlines())
for name in changed:
    if name.startswith(('shared/','server/','apps/wear/','docs/tasks/fixtures/d10-01/','docs/tasks/fixtures/d10-02/','docs/tasks/fixtures/d10-03/')):
        errors.append('D10-04 scope fence: '+name)
for folder in ('apps/presentation/src/main','apps/android/src/main','apps/desktop/src/main'):
    for p in (root/folder).rglob('*.kt'):
        if p.name.endswith('CompositionRoot.kt') or p.name in ('MainActivity.kt','SecurityWorkflowComposition.kt'): continue
        content=p.read_text(encoding='utf-8-sig')
        if re.search(r'\b(?:upsert\w*|\w+Dao)\s*\(',content): errors.append('UI persistence bypass: '+str(p.relative_to(root)))
        if p.name.startswith('AgentWorkspace') or p.name=='AgentToolPresentation.kt':
            if re.search(r'import (?:dev\.agenticscheduler\.database|io\.ktor)|LocalJournalCodec|SyncWireCodec|copyRawSecretBytesForSecureStore',content):
                errors.append('Agent presentation authority/security bypass: '+p.name)
for name in ('docs/tasks/D10_04_AGENT_PRODUCT_SURFACE.md','docs/ROADMAP_D5_D9.md'):
    p=root/name;content=p.read_text(encoding='utf-8')
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',content):
        target=target.split('#',1)[0].strip('<>')
        if target and not re.match('[a-z]+:',target) and not (p.parent/unquote(target)).exists(): errors.append('Broken link: '+name+': '+target)
roadmap=(root/'docs/ROADMAP_D5_D9.md').read_text(encoding='utf-8')
for required in ('D10-03 MERGED PR #35','cdc8504527cc636f5d2932b7e00785f4be9ecaad','D10-04F MERGED PR #38',base,'OD-012 OPEN','FG-02','FG-03','FG-04'):
    if required not in roadmap: errors.append('Roadmap contract lost: '+required)
if '--source-only' not in sys.argv:
    if 'D10-05/06 unstarted' not in roadmap: errors.append('D10-04 slice fence lost')
    if re.search(r'D10-04(?![A-Z0-9-])[^\n]*(?:MERGED|COMPLETE)',roadmap): errors.append('D10-04 acceptance overstated')
task=(root/'docs/tasks/D10_04_AGENT_PRODUCT_SURFACE.md').read_text(encoding='utf-8')
if 'ContextAnchor foundation — RESOLVED' not in task or base not in task: errors.append('Accepted ContextAnchor foundation missing')
if errors: raise SystemExit('\n'.join(errors))
print('D10-04 ownership/Markdown/status PASS' + ('; historical source-only mode; current slice owns diff/status gate' if source_only else '; diff scope PASS'))
