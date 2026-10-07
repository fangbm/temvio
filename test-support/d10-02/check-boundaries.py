"""Read-only D10-02 source/link/status guard. Does not approve screenshots."""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

root = Path(__file__).resolve().parents[2]
errors = []
for folder in ("apps/presentation/src/main", "apps/android/src/main", "apps/desktop/src/main"):
    for path in (root / folder).rglob("*.kt"):
        if path.name.endswith("CompositionRoot.kt") or path.name == "MainActivity.kt":
            continue  # Existing platform composition boundary.
        content = path.read_text(encoding="utf-8")
        if re.search(r"\b(?:upsert\w*|\w+Dao)\s*\(", content):
            errors.append(f"UI persistence bypass: {path.relative_to(root)}")
for path in (root / "shared/ui/src/commonMain").rglob("*.kt"):
    if re.search(r"import (?:dev\.agenticscheduler\.(?:domain|application|database|agent|sync)|io\.ktor|android\.)", path.read_text(encoding="utf-8")):
        errors.append(f"Shared UI ownership: {path.relative_to(root)}")
for name in ("docs/tasks/D10_02_TODAY_CALENDAR_TASKS_ACADEMIC.md", "docs/ROADMAP_D5_D9.md"):
    path = root / name
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
        target = target.split("#", 1)[0].strip("<>")
        if not target or re.match(r"[a-z]+:", target):
            continue
        if not (path.parent / unquote(target)).exists():
            errors.append(f"Broken Markdown link: {name}: {target}")
changed = [] if "--source-only" in sys.argv else subprocess.check_output(["git", "diff", "--name-only", "19d6a0779b27ff2641ff0c9251c8017b256ba774"], cwd=root, text=True).splitlines()
for name in changed:
    if name.startswith(("docs/tasks/fixtures/d10-01/", "apps/wear/", "shared/domain/", "shared/planner/", "shared/sync/", "shared/agent/", "server/")):
        errors.append(f"D10-02 scope fence: {name}")
    if name.startswith("shared/database/") and any(part in name.lower() for part in ("migration", "schema", "entity")):
        errors.append(f"Schema scope fence: {name}")
roadmap = (root / "docs/ROADMAP_D5_D9.md").read_text(encoding="utf-8")
if re.search(r"D10-01[^;\n]*(?:AWAITING REVIEW|unstarted)", roadmap):
    errors.append("D10-01 roadmap still awaiting review after baseline merge")
if "OD-012" not in roadmap or "OPEN" not in roadmap:
    errors.append("OD-012 release gate missing")
if errors:
    raise SystemExit("\n".join(errors))
print("D10-02 source ownership and Markdown/status checks PASS" + ("; diff/history scope PASS" if "--source-only" not in sys.argv else "; diff scope not checked in source-only mode"))
