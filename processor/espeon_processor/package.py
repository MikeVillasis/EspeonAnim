"""Reading `.espeon` capture packages (docs/formats.md section 1) and bare video files."""
from __future__ import annotations
import json, zipfile
from dataclasses import dataclass, field
from pathlib import Path
from .gem_stage import ProcessorError

VIDEO_EXT = {".mp4", ".mov", ".m4v", ".mkv", ".avi", ".webm"}


@dataclass
class Take:
    id: str
    role: str                       # action | calibration
    path: Path
    guidance_verdict: str | None = None
    guidance_warnings: list = field(default_factory=list)
    info: dict = field(default_factory=dict)


@dataclass
class Capture:
    actions: list
    calibration: Take | None
    performer_height_cm: float | None
    action_type: str
    manifest: dict | None
    notes: list = field(default_factory=list)


def _safe_extract(zf: zipfile.ZipFile, dest: Path):
    dest = dest.resolve()
    for m in zf.infolist():
        target = (dest / m.filename).resolve()
        if dest not in target.parents and target != dest:
            raise ProcessorError(f"unsafe path in package: {m.filename}")
    zf.extractall(dest)


def load_capture(path, work_dir) -> Capture:
    path = Path(path)
    if not path.exists():
        raise ProcessorError(f"input not found: {path}")
    if path.suffix.lower() in VIDEO_EXT:
        return Capture([Take("action_01", "action", path)], None, None, "jump", None,
                       ["input is a bare video: treated as one action take, no calibration"])
    if not zipfile.is_zipfile(path):
        raise ProcessorError(f"{path.name} is neither a video nor an .espeon (zip) package")
    dest = Path(work_dir) / "package"
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path) as zf:
        if "manifest.json" not in zf.namelist():
            raise ProcessorError("package has no manifest.json")
        _safe_extract(zf, dest)
    try:
        man = json.loads((dest / "manifest.json").read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise ProcessorError(f"manifest.json is not valid JSON: {e}")
    if man.get("format") != "espeon-package":
        raise ProcessorError(f"unexpected manifest format {man.get('format')!r} (expected 'espeon-package')")
    try:
        major = int(str(man.get("version", "")).split(".")[0])
    except ValueError:
        raise ProcessorError(f"bad manifest version {man.get('version')!r}")
    if major > 1:
        raise ProcessorError(f"package version {man['version']} is newer than this processor supports (1.x); "
                             "update EspeonAnim")
    actions, calib, notes = [], None, []
    for t in man.get("takes", []):
        f = dest / t["file"]
        if not f.exists():
            raise ProcessorError(f"take file missing from package: {t['file']}")
        g = t.get("guidance") or {}
        take = Take(t["id"], t["role"], f, g.get("verdict"), list(g.get("warnings") or []), t)
        if t["role"] == "action":
            actions.append(take)
        elif t["role"] == "calibration":
            if calib is None:
                calib = take
            else:
                notes.append(f"extra calibration take {t['id']} ignored")
        else:
            notes.append(f"take {t['id']} has unknown role {t['role']!r}; ignored")
    if not actions:
        raise ProcessorError("package contains no action take")
    h = (man.get("performer") or {}).get("height_cm")
    return Capture(actions, calib, float(h) if h else None, man.get("action", "jump"), man, notes)
