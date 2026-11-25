"""Inventario de solo lectura de las fotos y vídeos del móvil (MediaStore, vía ADB)."""
import glob
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def find_adb():
    """Busca adb: variable CRIBA_ADB, PATH, carpeta platform-tools junto al proyecto o instalación de winget."""
    candidates = [os.environ.get("CRIBA_ADB"), shutil.which("adb"),
                  os.path.join(HERE, "..", "platform-tools", "adb.exe")]
    local = os.environ.get("LOCALAPPDATA", "")
    candidates += glob.glob(os.path.join(local, "Microsoft", "WinGet", "Packages",
                                         "Google.PlatformTools*", "platform-tools", "adb.exe"))
    candidates += [os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe")]
    for c in candidates:
        if c and os.path.isfile(c):
            return os.path.abspath(c)
    sys.exit("No encuentro adb. Instálalo con:  winget install Google.PlatformTools")


ADB = find_adb()
KEYS = ["_id", "media_type", "mime_type", "_size", "bucket_display_name", "relative_path", "_display_name",
        "_data", "datetaken", "date_added", "duration", "width", "height", "is_trashed", "is_favorite"]
INT_KEYS = ("_id", "media_type", "_size", "datetaken", "date_added", "duration", "width", "height",
            "is_trashed", "is_favorite")


def query():
    cmd = [ADB, "shell", 'content query --uri content://media/external/file --projection %s '
                         '--where "media_type=1 OR media_type=3"' % ":".join(KEYS)]
    p = subprocess.run(cmd, capture_output=True)
    out = p.stdout.decode("utf-8", "replace")
    if "Row:" not in out:
        print(out[:500], p.stderr.decode("utf-8", "replace")[:500])
    rows = []
    pat = re.compile(r"(?:^|, )(" + "|".join(map(re.escape, KEYS)) + r")=")
    for line in out.splitlines():
        if not line.startswith("Row:"):
            continue
        parts = pat.split(line.split(" ", 2)[2])[1:]
        r = {parts[i]: parts[i + 1] for i in range(0, len(parts), 2)}
        r = {k: (None if v == "NULL" else v) for k, v in r.items()}
        for k in INT_KEYS:
            try:
                r[k] = int(r[k]) if r.get(k) is not None else None
            except ValueError:
                pass
        rows.append(r)
    return rows


if __name__ == "__main__":
    rows = query()
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False)
    print(len(rows), "filas")
