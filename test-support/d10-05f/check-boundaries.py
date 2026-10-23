"""D10-05F foundation diff, retained evidence, local links and status fence."""
from pathlib import Path
from urllib.parse import unquote
import re
import subprocess

root = Path(__file__).resolve().parents[2]
base = '93a2aab9714a729ed2f53e60e0077372a237cbf7'
allowed = {
    '.github/workflows/ci.yml',
    'shared/application/src/commonMain/kotlin/dev/agenticscheduler/application/sync/ProviderCredentialTargetCoordinator.kt',
    'shared/application/src/desktopTest/kotlin/dev/agenticscheduler/application/sync/ProviderCredentialTargetCoordinatorTest.kt',
    'apps/wear/src/main/kotlin/dev/agenticscheduler/wear/provisioning/WearTargetProviderProvisioningComposition.kt',
    'docs/tasks/D10_05F_WEAR_TARGET_PROVISIONING_COORDINATOR.md',
    'docs/ROADMAP_D5_D9.md',
    'test-support/d10-04/check-boundaries.py',
    'test-support/d10-05f/check-boundaries.py',
}
changed = set(subprocess.check_output(['git', 'diff', '--name-only', base], cwd=root, text=True).splitlines())
changed.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
errors = ['D10-05F scope fence: ' + name for name in sorted(changed - allowed)]
for name in ('docs/tasks/D10_05F_WEAR_TARGET_PROVISIONING_COORDINATOR.md', 'docs/ROADMAP_D5_D9.md'):
    path = root / name
    content = path.read_text(encoding='utf-8')
    for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', content):
        target = target.split('#', 1)[0].strip('<>')
        if target and not re.match('[a-z]+:', target) and not (path.parent / unquote(target)).exists():
            errors.append('Broken link: ' + name + ': ' + target)
task = (root / 'docs/tasks/D10_05F_WEAR_TARGET_PROVISIONING_COORDINATOR.md').read_text(encoding='utf-8')
roadmap = (root / 'docs/ROADMAP_D5_D9.md').read_text(encoding='utf-8')
for token in (base, 'OD-012 OPEN', 'No migration', 'PR #39', 'D10-06'):
    if token not in task:
        errors.append('Foundation contract missing: ' + token)
if 'D10-05F IMPLEMENTED / AWAITING REVIEW' not in roadmap:
    errors.append('Foundation roadmap status missing')
if 'OD-012 OPEN' not in roadmap:
    errors.append('OD-012 release gate lost')
before = subprocess.check_output(['git', 'show', base + ':docs/ROADMAP_D5_D9.md'], cwd=root).decode('utf-8')
if roadmap != before.replace('D10-05   Wear Final UX', 'D10-05F IMPLEMENTED / AWAITING REVIEW — [target provisioning foundation](tasks/D10_05F_WEAR_TARGET_PROVISIONING_COORDINATOR.md)\nD10-05   Wear Final UX'):
    errors.append('Roadmap changed beyond the authorized D10-05F note')
source = (root / next(n for n in allowed if n.endswith('ProviderCredentialTargetCoordinator.kt'))).read_text(encoding='utf-8')
for forbidden in ('import androidx.room', 'decryptProviderCredential(', 'importPreparedProviderSecret(', 'prepareProviderSlot(', 'delay(', 'launch('):
    if forbidden in source:
        errors.append('Coordinator owns forbidden implementation: ' + forbidden)
if errors:
    raise SystemExit('\n'.join(errors))
print('D10-05F scope, preserved wire/crypto/schema/runtime/tests, Markdown links and status PASS')
