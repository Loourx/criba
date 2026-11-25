"""Criba: revisa las fotos y vídeos de un móvil Android desde el PC, uno a uno.

Servidor local (solo escucha en 127.0.0.1) que:
  - lee del móvil la lista de fotos y vídeos (y los enviados/privados de WhatsApp),
  - reproduce los vídeos leyéndolos por trozos directamente del móvil,
  - copia en segundo plano los siguientes archivos de la tanda a una caché en el PC,
  - guarda las decisiones en decisiones.json,
  - al confirmar, mueve dentro del móvil lo marcado a Criba_para_borrar / Criba_ya_en_PC
    (los de "Guardar en PC" solo después de copiarlos y comprobarlos byte a byte).

Nunca borra nada del móvil: el borrado final lo hace el usuario.

Uso:  python -I criba/servidor.py [--refresh] [--port 8765] [--export CARPETA]
"""
import json, os, sys, threading, time, subprocess, mimetypes, argparse, re, shutil, queue
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import inventario  # noqa: E402

ADB = inventario.ADB
INV_PATH = os.path.join(HERE, "inventario.json")
DEC_PATH = os.path.join(HERE, "decisiones.json")
CACHE = os.path.join(HERE, "cache")
STATIC = os.path.join(HERE, "static")
EXPORT_DEFAULT = os.path.join(os.path.expanduser("~"), "Videos", "Del movil")
PHONE_DEL_DIR = "Criba_para_borrar"   # en el móvil: lo que tú borrarás a mano
PHONE_PC_DIR = "Criba_ya_en_PC"       # en el móvil: lo que ya tiene copia comprobada en el PC
LOG_PATH = os.path.join(HERE, "registro.jsonl")

PREFETCH_AHEAD = 40              # archivos por delante
CACHE_BUDGET = 5 * 1024**3       # 5 GB como máximo en caché
KEEP_BEHIND = 5                  # archivos ya vistos que se mantienen
MAX_CHUNK = 4 * 1024 * 1024      # tamaño máximo de cada trozo de vídeo servido

os.makedirs(CACHE, exist_ok=True)
lock = threading.RLock()


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def save_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


def ts(r):
    for t in ((r.get("datetaken") or 0), (r.get("date_added") or 0) * 1000):
        if 946684800000 < t < 1900000000000:
            return t
    return 0


def ext_for(r):
    e = os.path.splitext(r.get("_display_name") or "")[1].lower()
    if re.fullmatch(r"\.[a-z0-9]{1,5}", e):
        return e
    return mimetypes.guess_extension(r.get("mime_type") or "") or ".bin"


def sh_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def safe_name(s):
    s = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", s).strip(" .")
    return s or "sin nombre"


WA_BASE = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media"
WA_DIRS = {  # carpeta -> nombre del "álbum" en la Criba
    "WhatsApp Video/Sent": "WhatsApp · vídeos enviados",
    "WhatsApp Video/Private": "WhatsApp · vídeos privados",
    "WhatsApp Images/Sent": "WhatsApp · fotos enviadas",
    "WhatsApp Images/Private": "WhatsApp · fotos privadas",
}
VIDEO_EXT = {".mp4", ".3gp", ".mov", ".mkv", ".webm"}
PHOTO_EXT = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic"}


def list_whatsapp_hidden():
    """WhatsApp esconde Enviados y Privados de la galería (.nomedia), así que no están en el
    índice de medios. Se leen directamente de las carpetas: solo nombre, tamaño y fecha."""
    import hashlib
    dirs = " ".join(sh_quote(d) for d in WA_DIRS)
    cmd = f"cd {sh_quote(WA_BASE)} && find {dirs} -type f -exec stat -c '%s|%Y|%n' {{}} + 2>/dev/null"
    try:
        p = subprocess.run([ADB, "shell", cmd], capture_output=True, timeout=120, stdin=subprocess.DEVNULL)
    except subprocess.TimeoutExpired:
        return []
    out = []
    for line in p.stdout.decode("utf-8", "replace").splitlines():
        parts = line.strip().split("|", 2)
        if len(parts) != 3:
            continue
        size, mtime, rel = parts
        ext = os.path.splitext(rel)[1].lower()
        if ext not in VIDEO_EXT | PHOTO_EXT:
            continue
        folder = rel.rsplit("/", 1)[0]
        if folder not in WA_DIRS:
            continue
        path = f"{WA_BASE}/{rel}"
        iid = "w" + hashlib.md5(path.encode("utf-8")).hexdigest()[:16]
        out.append({
            "id": iid, "type": "video" if ext in VIDEO_EXT else "foto",
            "mime": mimetypes.guess_type(rel)[0] or ("video/mp4" if ext in VIDEO_EXT else "image/jpeg"),
            "album": WA_DIRS[folder], "path": path, "name": os.path.basename(rel),
            "size": int(size), "date": int(mtime) * 1000, "w": None, "h": None, "duration": None,
            "ext": ext, "wa": True,
        })
    return out


class State:
    def __init__(self, rows, export_dir):
        self.export_dir = export_dir
        self.items = {}
        for r in rows:
            if not r.get("_data") or r.get("is_trashed"):
                continue
            # lo que ya está en las carpetas de revisión no se vuelve a enseñar
            if f"/{PHONE_DEL_DIR}/" in r["_data"] or f"/{PHONE_PC_DIR}/" in r["_data"]:
                continue
            self.items[str(r["_id"])] = {
                "id": str(r["_id"]),
                "type": "video" if r["media_type"] == 3 else "foto",
                "mime": r.get("mime_type") or "",
                "album": r.get("bucket_display_name") or "(sin álbum)",
                "path": r["_data"],
                "name": r.get("_display_name") or os.path.basename(r["_data"]),
                "size": r.get("_size") or 0,
                "date": ts(r),
                "w": r.get("width"), "h": r.get("height"),
                "duration": r.get("duration"),
                "ext": ext_for(r),
            }
        for it in list_whatsapp_hidden():
            self.items[it["id"]] = it
        self.decisions = load_json(DEC_PATH, {})
        self.batch = []
        self.pos = 0
        self.inflight = {}
        self.cache_bytes = 0
        self.errors = {}
        self.wake = threading.Event()
        self.exports = queue.Queue()
        self.applies = queue.Queue()
        for fn in os.listdir(CACHE):
            fp = os.path.join(CACHE, fn)
            if fn.endswith(".part"):
                os.remove(fp)
            else:
                self.cache_bytes += os.path.getsize(fp)
        # reanudar copias al PC pendientes
        for iid, d in self.decisions.items():
            if d.get("d") == "pc" and d.get("export") != "ok" and iid in self.items:
                self.exports.put(iid)

    # ---------- caché ----------
    def cache_file(self, it):
        return os.path.join(CACHE, it["id"] + it["ext"])

    def cached(self, iid):
        fp = self.cache_file(self.items[iid])
        return fp if os.path.exists(fp) else None

    def pull(self, it, dst):
        part = dst + ".part"
        p = subprocess.run([ADB, "pull", it["path"], part], capture_output=True, timeout=1800)
        if p.returncode == 0 and os.path.exists(part):
            os.replace(part, dst)
            return True
        try:
            os.remove(part)
        except OSError:
            pass
        self.errors[it["id"]] = (p.stderr or p.stdout).decode("utf-8", "replace")[-300:]
        return False

    def ensure(self, iid):
        it = self.items[iid]
        dst = self.cache_file(it)
        if os.path.exists(dst):
            return dst
        with lock:
            ev = self.inflight.get(iid)
            owner = ev is None
            if owner:
                ev = self.inflight[iid] = threading.Event()
        if not owner:
            ev.wait(1800)
            return dst if os.path.exists(dst) else None
        try:
            if self.pull(it, dst):
                with lock:
                    self.cache_bytes += os.path.getsize(dst)
                return dst
            return None
        except subprocess.TimeoutExpired:
            self.errors[iid] = "tiempo agotado copiando el archivo"
            return None
        finally:
            with lock:
                self.inflight.pop(iid, None)
            ev.set()

    def evict(self):
        with lock:
            keep = set(self.batch[max(0, self.pos - KEEP_BEHIND): self.pos + PREFETCH_AHEAD])
            keep |= {i for i in self.batch if self.decisions.get(i, {}).get("d") in ("delete", "pc")
                     and self.items[i]["type"] == "foto"}
            busy = set(self.inflight)
        for fn in os.listdir(CACHE):
            iid = fn.split(".")[0]
            if fn.endswith(".part") or iid in keep or iid in busy:
                continue
            fp = os.path.join(CACHE, fn)
            try:
                sz = os.path.getsize(fp)
                os.remove(fp)
                with lock:
                    self.cache_bytes -= sz
            except OSError:
                pass

    def prefetch_loop(self):
        while True:
            self.wake.wait(2)
            self.wake.clear()
            self.evict()
            with lock:
                # el archivo actual se ve en streaming; la caché prepara los siguientes
                start = self.pos + 1
                window = self.batch[start: self.pos + PREFETCH_AHEAD]
            for iid in window:
                with lock:
                    moved = self.batch[self.pos + 1: self.pos + PREFETCH_AHEAD] != window
                    over = self.cache_bytes > CACHE_BUDGET
                if moved or over or self.wake.is_set():
                    break
                if iid not in self.errors:
                    self.ensure(iid)

    # ---------- guardar en el PC ----------
    def export_loop(self):
        while True:
            iid = self.exports.get()
            it = self.items[iid]
            d = self.decisions.get(iid)
            if not d or d.get("d") != "pc":
                continue
            folder = os.path.join(self.export_dir, safe_name(it["album"]))
            os.makedirs(folder, exist_ok=True)
            dst = os.path.join(folder, safe_name(it["name"]))
            base, ext = os.path.splitext(dst)
            n = 1
            while os.path.exists(dst) and os.path.getsize(dst) != it["size"]:
                dst = f"{base} ({n}){ext}"
                n += 1
            ok = os.path.exists(dst) and os.path.getsize(dst) == it["size"]
            if not ok:
                src = self.cached(iid)
                if src:
                    shutil.copyfile(src, dst + ".part")
                    os.replace(dst + ".part", dst)
                else:
                    self.pull(it, dst)
                ok = os.path.exists(dst) and os.path.getsize(dst) == it["size"]
            with lock:
                d = self.decisions.get(iid)
                if d and d.get("d") == "pc":
                    d["export"] = "ok" if ok else "error"
                    d["dest"] = dst
                    save_json(DEC_PATH, self.decisions)

    # ---------- mover lo decidido a las carpetas de revisión ----------
    def adb_sh(self, cmd, timeout=600):
        p = subprocess.run([ADB, "shell", cmd], capture_output=True, timeout=timeout)
        return p.returncode, p.stdout.decode("utf-8", "replace").strip()

    def remote_info(self, path):
        rc, out = self.adb_sh(f"stat -c %s {sh_quote(path)} && md5sum {sh_quote(path)}")
        if rc != 0:
            return None, None
        lines = out.splitlines()
        return int(lines[0]), lines[1].split()[0]

    @staticmethod
    def local_md5(path):
        import hashlib
        h = hashlib.md5()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()

    def unique_dest(self, folder, name, size):
        os.makedirs(folder, exist_ok=True)
        dst = os.path.join(folder, safe_name(name))
        base, ext = os.path.splitext(dst)
        n = 1
        while os.path.exists(dst) and os.path.getsize(dst) != size:
            dst = f"{base} ({n}){ext}"
            n += 1
        return dst

    def set_apply(self, iid, state, **extra):
        with lock:
            d = self.decisions.get(iid)
            if d:
                d["apply"] = state
                d.update(extra)
                save_json(DEC_PATH, self.decisions)

    def move_on_phone(self, iid, rel_folder):
        """Mueve el archivo dentro del móvil cambiando su carpeta en la base de datos de medios.
        Android mueve el archivo y la galería queda al día. No borra nada."""
        rc, _ = self.adb_sh(f"content update --uri content://media/external/file/{iid} "
                            f"--bind relative_path:s:{sh_quote(rel_folder)}")
        rc, out = self.adb_sh(f"content query --uri content://media/external/file/{iid} --projection _data")
        m = re.search(r"_data=(.*)$", out)
        return m.group(1).strip() if m else None

    def apply_one(self, iid):
        it = self.items[iid]
        d = self.decisions.get(iid) or {}
        if d.get("d") not in ("delete", "pc") or d.get("apply") == "done":
            return
        album = safe_name(it["album"])
        size, md5 = self.remote_info(it["path"])
        if size is None:
            return self.set_apply(iid, "error", error="El archivo ya no está donde estaba en el móvil.")
        dst = None
        if d["d"] == "pc":
            self.set_apply(iid, "copiando al PC")
            folder = os.path.join(self.export_dir, album)
            dst = d.get("dest") if d.get("dest") and os.path.exists(d["dest"]) else self.unique_dest(folder, it["name"], size)
            if not (os.path.exists(dst) and os.path.getsize(dst) == size):
                src = self.cached(iid)
                if src and os.path.getsize(src) == size:
                    shutil.copyfile(src, dst + ".part")
                    os.replace(dst + ".part", dst)
                else:
                    self.pull(it, dst)
            self.set_apply(iid, "comprobando copia", dest=dst)
            if not os.path.exists(dst) or os.path.getsize(dst) != size or self.local_md5(dst) != md5:
                return self.set_apply(iid, "error", error="La copia en el PC no coincide con el original. No se ha movido nada.")
        self.set_apply(iid, "moviendo en el móvil")
        target = (PHONE_PC_DIR if d["d"] == "pc" else PHONE_DEL_DIR) + "/" + album + "/"
        if it.get("wa"):
            new_path = self.mv_on_phone(it["path"], "/storage/emulated/0/" + target)
        else:
            new_path = self.move_on_phone(iid, target)
        if not new_path or "/" + target not in new_path:
            return self.set_apply(iid, "error", error="El móvil no dejó mover el archivo. Sigue en su sitio.")
        self.set_apply(iid, "done", done_at=int(time.time()), phone_now=new_path, phone_before=it["path"])
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps({"t": int(time.time()), "id": iid, "accion": d["d"], "antes": it["path"],
                                "ahora": new_path, "pc": dst, "bytes": size, "md5": md5}, ensure_ascii=False) + "\n")

    def mv_on_phone(self, src, dst_folder):
        """Mueve un archivo dentro del móvil sin sobrescribir nada. Devuelve la ruta nueva."""
        name = src.rsplit("/", 1)[1]
        base, ext = os.path.splitext(name)
        script = (f"mkdir -p {sh_quote(dst_folder)} && d={sh_quote(dst_folder.rstrip('/') + '/' + name)}; n=1; "
                  f"while [ -e \"$d\" ]; do d={sh_quote(dst_folder.rstrip('/') + '/' + base)}\" ($n)\"{sh_quote(ext)}; n=$((n+1)); done; "
                  f"mv -n -- {sh_quote(src)} \"$d\" && [ -e \"$d\" ] && [ ! -e {sh_quote(src)} ] && echo \"$d\"")
        rc, out = self.adb_sh(script)
        new_path = out.strip().splitlines()[-1] if out.strip() else None
        if new_path:
            # que la galería vea el archivo en su sitio nuevo
            self.adb_sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d " + sh_quote("file://" + new_path))
        return new_path

    def restore_one(self, iid):
        """Devuelve un archivo movido a su carpeta original."""
        d = self.decisions.get(iid) or {}
        before = d.get("phone_before")
        if d.get("apply") != "done" or not before:
            return False
        it = self.items.get(iid) or {}
        if it.get("wa"):
            now = d.get("phone_now")
            rc, out = self.adb_sh(f"[ -e {sh_quote(before)} ] && echo OCUPADO || (mv -n -- {sh_quote(now)} {sh_quote(before)} && echo OK)")
            if out.strip().endswith("OK"):
                with lock:
                    self.decisions.pop(iid, None)
                    save_json(DEC_PATH, self.decisions)
                return True
            return False
        rel = re.sub(r"^/storage/emulated/0/", "", before).rsplit("/", 1)[0] + "/"
        now = self.move_on_phone(iid, rel)
        if now == before:
            with lock:
                self.decisions.pop(iid, None)
                save_json(DEC_PATH, self.decisions)
            return True
        return False

    def apply_loop(self):
        while True:
            iid = self.applies.get()
            try:
                self.apply_one(iid)
            except Exception as e:  # noqa: BLE001 - nunca dejar el hilo muerto
                self.set_apply(iid, "error", error=f"Error inesperado: {e}")

    # ---------- tandas ----------
    def make_batch(self, mode, album, size):
        pool = [it for it in self.items.values() if it["id"] not in self.decisions]
        if mode == "whatsapp":
            pool = [it for it in pool if it.get("wa")]
            pool.sort(key=lambda it: -it["size"])
        elif mode == "pesados":
            pool = [it for it in pool if it["type"] == "video" and not it.get("wa")]
            pool.sort(key=lambda it: -it["size"])
        elif mode == "album":
            pool = [it for it in pool if it["album"] == album]
            pool.sort(key=lambda it: it["date"])
        elif mode == "grandes":
            pool.sort(key=lambda it: -it["size"])
        else:
            pool.sort(key=lambda it: it["date"])
        pool = pool[:size]
        # agrupar por álbum manteniendo el orden en que aparece cada álbum
        order = {}
        for it in pool:
            order.setdefault(it["album"], len(order))
        pool.sort(key=lambda it: order[it["album"]])
        with lock:
            self.batch = [it["id"] for it in pool]
            self.pos = 0
        self.wake.set()
        return pool

    def albums(self):
        out = {}
        for it in self.items.values():
            a = out.setdefault(it["album"], {"album": it["album"], "n": 0, "bytes": 0, "videos": 0, "pending": 0})
            a["n"] += 1
            a["bytes"] += it["size"]
            a["videos"] += it["type"] == "video"
            a["pending"] += it["id"] not in self.decisions
        return sorted(out.values(), key=lambda a: -a["bytes"])

    def decide(self, iid, d):
        it = self.items.get(iid)
        if not it:
            return False
        with lock:
            cur = self.decisions.get(iid) or {}
            if cur.get("apply") and cur.get("apply") != "error":
                return False   # ya se está quitando del móvil o ya está hecho: no se puede cambiar
            if d is None:
                self.decisions.pop(iid, None)
            else:
                self.decisions[iid] = {"d": d, "album": it["album"], "path": it["path"],
                                       "size": it["size"], "t": int(time.time())}
            save_json(DEC_PATH, self.decisions)
        if d == "pc":
            self.exports.put(iid)
        return True

    def summary(self):
        s = {"keep": [0, 0], "delete": [0, 0], "pc": [0, 0], "skip": [0, 0], "pc_ok": 0, "pc_err": 0, "freed": [0, 0]}
        for v in self.decisions.values():
            if v.get("apply") == "done":
                s["freed"][0] += 1
                s["freed"][1] += v.get("size") or 0
            k = v["d"] if v["d"] in ("keep", "delete", "pc") else "skip"
            s[k][0] += 1
            s[k][1] += v.get("size") or 0
            if k == "pc":
                s["pc_ok"] += v.get("export") == "ok"
                s["pc_err"] += v.get("export") == "error"
        return s


def open_phone_folder(folder):
    """Abre la carpeta del móvil en el Explorador. Si Windows no ve el móvil,
    activa la transferencia de archivos (MTP) por ADB y lo vuelve a intentar."""
    script = os.path.join(HERE, "abrir_carpeta_movil.ps1")

    def run():
        p = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script,
                            "-Carpeta", folder], capture_output=True, timeout=60)
        return p.stdout.decode("utf-8", "replace").strip()

    out = run()
    if "NO_MOVIL" in out or "NO_ALMACENAMIENTO" in out:
        subprocess.run([ADB, "shell", "svc usb setFunctions mtp"], capture_output=True, timeout=30)
        subprocess.run([ADB, "wait-for-device"], capture_output=True, timeout=30)
        for _ in range(6):
            time.sleep(2)
            out = run()
            if out.startswith("OK"):
                break
    if "NO_CARPETA" in out:
        return {"ok": True, "msg": f"Todavía no hay carpeta {folder}; abro el almacenamiento del móvil."}
    if "OK" in out:
        return {"ok": True, "msg": "Abierto en el Explorador."}
    return {"ok": False, "msg": "Windows no ve el móvil. Desbloquéalo y elige “Transferencia de archivos” en el aviso USB."}


ST = None


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        pass

    def send_json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path in ("/", "/index.html"):
            return self.send_file(os.path.join(STATIC, "index.html"), "text/html; charset=utf-8")
        if u.path == "/api/albums":
            return self.send_json({"albums": ST.albums(), "summary": ST.summary(), "export_dir": ST.export_dir})
        if u.path == "/api/batch":
            size = max(1, min(2000, int(q.get("size", ["200"])[0])))
            pool = ST.make_batch(q.get("mode", ["pesados"])[0], q.get("album", [""])[0], size)
            return self.send_json({"items": pool})
        if u.path == "/api/status":
            with lock:
                ahead = ST.batch[ST.pos + 1: ST.pos + PREFETCH_AHEAD]
            ready = sum(1 for i in ahead if ST.cached(i))
            return self.send_json({"cache_gb": round(ST.cache_bytes / 1e9, 2), "ready_ahead": ready,
                                   "window": len(ahead), "summary": ST.summary()})
        m = re.fullmatch(r"/media/(w?[0-9a-f]+)", u.path)
        if m and m.group(1) in ST.items:
            iid = m.group(1)
            it = ST.items[iid]
            fp = ST.cached(iid)
            if fp:
                return self.send_file(fp, it["mime"] or "application/octet-stream")
            if it["type"] == "video":
                return self.stream_from_phone(it)
            fp = ST.ensure(iid)
            if not fp:
                return self.send_json({"error": ST.errors.get(iid, "no se pudo copiar")}, 502)
            return self.send_file(fp, it["mime"] or "application/octet-stream")
        self.send_error(404)

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/decision":
            b = self.body()
            ok = all(ST.decide(str(i), b.get("d")) for i in b.get("ids", []))
            return self.send_json({"ok": ok, "summary": ST.summary()})
        if u.path == "/api/position":
            with lock:
                ST.pos = max(0, int(self.body().get("pos", 0)))
            ST.wake.set()
            return self.send_json({"ok": True})
        if u.path == "/api/apply":
            ids = [str(i) for i in self.body().get("ids", [])]
            queued = []
            for iid in ids:
                d = ST.decisions.get(iid)
                if iid in ST.items and d and d.get("d") in ("delete", "pc") and d.get("apply") in (None, "error"):
                    ST.set_apply(iid, "en cola")
                    ST.applies.put(iid)
                    queued.append(iid)
            return self.send_json({"queued": queued})
        if u.path == "/api/apply-status":
            ids = [str(i) for i in self.body().get("ids", [])]
            out = {}
            for iid in ids:
                d = ST.decisions.get(iid) or {}
                out[iid] = {"d": d.get("d"), "apply": d.get("apply"), "error": d.get("error"), "dest": d.get("dest")}
            return self.send_json({"items": out})
        if u.path == "/api/restore":
            ok = [str(i) for i in self.body().get("ids", []) if ST.restore_one(str(i))]
            return self.send_json({"restored": ok})
        if u.path == "/api/open-phone":
            folder = str(self.body().get("folder", ""))
            if folder not in ("", PHONE_DEL_DIR, PHONE_PC_DIR):
                return self.send_json({"ok": False, "msg": "Carpeta no válida."}, 400)
            return self.send_json(open_phone_folder(folder))
        if u.path == "/api/open-export":
            os.makedirs(ST.export_dir, exist_ok=True)
            os.startfile(ST.export_dir)
            return self.send_json({"ok": True})
        self.send_error(404)

    def parse_range(self, size):
        rng = self.headers.get("Range")
        if not rng:
            return None
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        if not m:
            return None
        if m.group(1):
            start = int(m.group(1))
            end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
        else:
            start, end = max(0, size - int(m.group(2) or 0)), size - 1
        return start, end

    def head(self, code, ctype, start, end, size):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

    def stream_from_phone(self, it):
        """Sirve un trozo del vídeo leyéndolo directamente del móvil, sin esperar a copiarlo entero."""
        size = it["size"]
        r = self.parse_range(size)
        start, end = r if r else (0, size - 1)
        # trozos de 4 MB como máximo: el navegador pide el siguiente cuando lo necesita
        # y ninguna conexión se queda colgada ocupando sitio
        end = min(end, start + MAX_CHUNK - 1)
        r = True
        if start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.head(206 if r else 200, it["mime"] or "video/mp4", start, end, size)
        cmd = f"tail -c +{start + 1} {sh_quote(it['path'])} | head -c {end - start + 1}"
        p = subprocess.Popen([ADB, "exec-out", cmd], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            while True:
                chunk = p.stdout.read(256 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError, OSError):
            pass
        finally:
            p.kill()
            p.wait()

    def send_file(self, fp, ctype):
        size = os.path.getsize(fp)
        r = self.parse_range(size)
        start, end = r if r else (0, size - 1)
        if ctype.startswith("video/"):
            end = min(end, start + MAX_CHUNK - 1)
            r = True
        if start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{size}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self.head(206 if r else 200, ctype, start, end, size)
        try:
            with open(fp, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(1 << 20, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (ConnectionResetError, BrokenPipeError, ConnectionAbortedError):
            pass


def main():
    global ST
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="volver a leer el inventario del móvil")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--export", default=EXPORT_DEFAULT, help="carpeta del PC para 'Guardar en el PC'")
    a = ap.parse_args()

    class Server(ThreadingHTTPServer):
        allow_reuse_address = False   # en Windows, True dejaría arrancar dos servidores en el mismo puerto
        daemon_threads = True

        def handle_error(self, request, client_address):
            # el navegador cancela peticiones de vídeo a menudo: no es un error
            if isinstance(sys.exc_info()[1], (ConnectionError, TimeoutError)):
                return
            super().handle_error(request, client_address)
    try:
        srv = Server(("127.0.0.1", a.port), H)
    except OSError:
        print("La Criba ya está abierta en otra ventana. Abro la página.", flush=True)
        os.startfile(f"http://127.0.0.1:{a.port}")
        return
    rows = None
    if a.refresh or not os.path.exists(INV_PATH):
        rows = inventario.query()
        save_json(INV_PATH, rows)
    ST = State(rows if rows is not None else load_json(INV_PATH, []), a.export)
    threading.Thread(target=ST.prefetch_loop, daemon=True).start()
    threading.Thread(target=ST.export_loop, daemon=True).start()
    threading.Thread(target=ST.apply_loop, daemon=True).start()
    # reanudar lo que quedó a medias si se cerró el servidor
    for iid, d in ST.decisions.items():
        if d.get("apply") in ("en cola", "copiando al PC", "comprobando copia", "moviendo en el móvil") and iid in ST.items:
            ST.applies.put(iid)
    print(f"Criba lista: {len(ST.items)} archivos. Abre http://127.0.0.1:{a.port}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
