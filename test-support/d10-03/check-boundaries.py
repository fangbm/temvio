"""D10-03 source/diff/link/status fence. No acceptance or automatic visual approval."""
from pathlib import Path
import re, subprocess, sys
from urllib.parse import unquote
root=Path(__file__).resolve().parents[2]
errors=[]
base='74e20e555c9037ff62dda95ab67e3e0e455b0aa1'
changed=set() if '--source-only' in sys.argv else set(subprocess.check_output(['git','diff','--name-only',base],cwd=root,text=True).splitlines())
changed.update(subprocess.check_output(['git','ls-files','--others','--exclude-standard'],cwd=root,text=True).splitlines())
for name in changed:
    if name.startswith(('shared/domain/','shared/application/','shared/planner/','shared/sync/','shared/agent/','shared/database/','server/','apps/wear/','docs/tasks/fixtures/d10-01/','docs/tasks/fixtures/d10-02/')):
        errors.append('D10-03 scope fence: '+name)
for folder in ('apps/presentation/src/main','apps/android/src/main','apps/desktop/src/main'):
    for p in (root/folder).rglob('*.kt'):
        if p.name.endswith('CompositionRoot.kt') or p.name in ('MainActivity.kt','SecurityWorkflowComposition.kt'): continue
        text=p.read_text(encoding='utf-8-sig')
        if re.search(r'\b(?:upsert\w*|\w+Dao)\s*\(',text): errors.append('UI persistence bypass: '+str(p.relative_to(root)))
        if p.name in ('PlannerWorkspaceCoordinator.kt','HistoryScreenCoordinator.kt','SyncSecurityScreenCoordinator.kt','PlannerWorkspaceScreen.kt','HistoryScreen.kt','SyncSettingsScreens.kt','PlanningProfileDraft.kt','PlanningProfileEditor.kt'):
            if re.search(r'import (?:dev\.agenticscheduler\.database|io\.ktor)|LocalJournalCodec|SyncWireCodec|candidateValuesJson|\.saveAgent\(',text):
                errors.append('Presentation protocol/foundation bypass: '+p.name)
for p in (root/'shared/ui/src/commonMain').rglob('*.kt'):
    if re.search(r'import (?:dev\.agenticscheduler\.(?:domain|application|database|agent|sync)|io\.ktor|android\.)',p.read_text(encoding='utf-8')):
        errors.append('shared:ui ownership: '+str(p.relative_to(root)))
for name in ('docs/tasks/D10_03_PLANNER_HISTORY_SYNC_SETTINGS.md','docs/ROADMAP_D5_D9.md'):
    p=root/name;content=p.read_text(encoding='utf-8')
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',content):
        target=target.split('#',1)[0].strip('<>')
        if target and not re.match('[a-z]+:',target) and not (p.parent/unquote(target)).exists(): errors.append('Broken link: '+name+': '+target)
roadmap=(root/'docs/ROADMAP_D5_D9.md').read_text(encoding='utf-8')
if 'D10-03 IMPLEMENTED / AWAITING REVIEW' not in roadmap or 'D10-04+ unstarted' not in roadmap: errors.append('D10-03/later-slice status drift')
if re.search(r'D10-02[^;\n]*(?:AWAITING REVIEW|unstarted)',roadmap): errors.append('D10-02 baseline not synchronized')
if 'MERGED PR #36' not in roadmap or '74e20e555c9037ff62dda95ab67e3e0e455b0aa1' not in roadmap: errors.append('FG-01 merged foundation missing')
if 'OD-012 OPEN' not in roadmap: errors.append('OD-012 gate lost')
if errors: raise SystemExit('\n'.join(errors))
print('D10-03 source ownership, Markdown links and status consistency PASS' + ('; diff scope not checked in source-only mode' if '--source-only' in sys.argv else '; diff scope PASS'))
