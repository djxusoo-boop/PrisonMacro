#!/usr/bin/env python3
"""Buzón de PrisonMacro en la red de casa.

Sirve la carpeta outputs/update (version.json, la APK y macros.json) y además
acepta macros enviadas desde el iPhone o desde Android:

  GET  /hello        → {"app": "PrisonMacro"}  (para que las apps lo reconozcan)
  GET  /macros.json  → lo último que se envió (o la macro preparada en outputs/macros)
  POST /macros.json  → guarda lo enviado en outputs/macros/ y lo deja listo para recibir

Solo se aceptan archivos JSON de PrisonMacro (una macro o una copia completa),
de hasta 60 MB. No se ejecuta nada de lo recibido.
"""
import http.server
import json
import os
import re
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
UPDATE_DIR = os.path.join(ROOT, "outputs", "update")
MACROS_DIR = os.path.join(ROOT, "outputs", "macros")
MAX_BYTES = 60_000_000
# Se cierra solo si nadie lo usa en este tiempo (0 = nunca). Lo cambia la variable PRISON_IDLE_MIN.
IDLE_SECONDS = int(float(os.environ.get("PRISON_IDLE_MIN", "15")) * 60)
last_use = time.time()


def describe(payload):
    """Devuelve (número de macros, nombre de la primera) o lanza ValueError."""
    if not isinstance(payload, dict):
        raise ValueError("no es un objeto JSON")
    if "prisonMacroBackup" in payload:
        projects = payload.get("projects")
        if not isinstance(projects, list) or not projects:
            raise ValueError("la copia no trae macros")
    else:
        projects = [payload]
    for p in projects:
        if not isinstance(p, dict) or not isinstance(p.get("actions"), list) or not isinstance(p.get("name"), str):
            raise ValueError("no parece una macro de PrisonMacro")
    return len(projects), projects[0]["name"]


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=UPDATE_DIR, **kwargs)

    def _json(self, code, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        pass  # sin líneas técnicas en la ventana

    def do_GET(self):
        global last_use
        last_use = time.time()
        what = self.path.split("?")[0]
        if what.endswith(".apk"):
            print(f"⬇ {self.client_address[0]} está bajando la actualización", flush=True)
        elif what == "/macros.json":
            print(f"📤 {self.client_address[0]} ha recogido las macros", flush=True)
        if self.path.split("?")[0] == "/hello":
            return self._json(200, {"app": "PrisonMacro"})
        return super().do_GET()

    def do_POST(self):
        global last_use
        last_use = time.time()
        if self.path.split("?")[0] == "/registro.txt":
            return self._save_log()
        if self.path.split("?")[0] == "/captura.png":
            return self._save_shot()
        if self.path.split("?")[0] != "/macros.json":
            return self._json(404, {"error": "Dirección desconocida"})
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BYTES:
            return self._json(413, {"error": "Archivo vacío o demasiado grande"})
        data = self.rfile.read(length)
        try:
            count, name = describe(json.loads(data.decode("utf-8")))
        except (ValueError, UnicodeDecodeError) as e:
            return self._json(400, {"error": f"No es una macro válida: {e}"})
        os.makedirs(MACROS_DIR, exist_ok=True)
        safe = re.sub(r"[^A-Za-z0-9_-]+", "-", name).strip("-")[:40] or "macro"
        stamp = time.strftime("%Y%m%d-%H%M%S")
        with open(os.path.join(MACROS_DIR, f"recibida-{stamp}-{safe}.json"), "wb") as f:
            f.write(data)
        tmp = os.path.join(UPDATE_DIR, "macros.json.tmp")
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, os.path.join(UPDATE_DIR, "macros.json"))
        print(f"📥 Recibida{'s' if count > 1 else ''} {count} macro(s) de {self.client_address[0]}: «{name}»", flush=True)
        return self._json(200, {"ok": True, "count": count, "name": name})


    def _save_log(self):
        """Registro de un aparato (qué hizo la macro, por qué se paró) → outputs/registros/."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > 1_000_000:
            return self._json(413, {"error": "Registro vacío o demasiado grande"})
        data = self.rfile.read(length)
        folder = os.path.join(os.path.dirname(MACROS_DIR), "registros")
        os.makedirs(folder, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        who = re.sub(r"[^0-9.]+", "-", self.client_address[0])
        with open(os.path.join(folder, f"registro-{stamp}-{who}.txt"), "wb") as f:
            f.write(data)
        print(f"📜 Registro recibido de {self.client_address[0]}", flush=True)
        return self._json(200, {"ok": True})

    def _save_shot(self):
        """Captura entera de un aparato (📸) → outputs/capturas/, para recortar botones en el Mac."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > 12_000_000:
            return self._json(413, {"error": "Captura vacía o demasiado grande"})
        data = self.rfile.read(length)
        if not data.startswith(b"\x89PNG"):
            return self._json(415, {"error": "No es una imagen PNG"})
        folder = os.path.join(os.path.dirname(MACROS_DIR), "capturas")
        os.makedirs(folder, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        who = re.sub(r"[^0-9.]+", "-", self.client_address[0])
        n = len([f for f in os.listdir(folder) if f.startswith(f"captura-{stamp}")])
        with open(os.path.join(folder, f"captura-{stamp}-{n}-{who}.png"), "wb") as f:
            f.write(data)
        print(f"📸 Captura recibida de {self.client_address[0]}", flush=True)
        return self._json(200, {"ok": True})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    os.makedirs(UPDATE_DIR, exist_ok=True)
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
    if IDLE_SECONDS > 0:
        import threading

        def watch():
            warned = False
            while True:
                time.sleep(5)
                idle = time.time() - last_use
                left = IDLE_SECONDS - idle
                if left <= 60 and not warned:
                    print("⏳ Nadie lo usa: el buzón se cerrará en 1 minuto.", flush=True)
                    warned = True
                if left > 60:
                    warned = False
                if left <= 0:
                    print(f"💤 Buzón cerrado: nadie lo ha usado en {IDLE_SECONDS // 60} minutos.", flush=True)
                    server.shutdown()
                    return

        threading.Thread(target=watch, daemon=True).start()
        print(f"(Se cerrará solo si nadie lo usa en {IDLE_SECONDS // 60} minutos.)", flush=True)
    server.serve_forever()
