# -*- coding: utf-8 -*-
"""迷你 FTP 服务器（真机联调桩）：支持 USER/PASS/TYPE/OPTS/PASV/LIST/STOR/RETR/MKD/DELE/RMD/RNFR/RNTO/QUIT。
用法: python mini_ftp_server.py <端口> <根目录> [用户] [密码]
"""
import os, socket, sys, threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2121
ROOT = sys.argv[2] if len(sys.argv) > 2 else "."
USER = sys.argv[3] if len(sys.argv) > 3 else "test"
PASS = sys.argv[4] if len(sys.argv) > 4 else "pass123"

os.makedirs(ROOT, exist_ok=True)
with open(os.path.join(ROOT, "readme.txt"), "w", encoding="utf-8") as f:
    f.write("HexFile v1.60.0 FTP 真机联调")

def safe(rel):
    p = os.path.normpath(os.path.join(ROOT, rel))
    if not p.startswith(os.path.abspath(ROOT)):
        return None
    return p

def list_line(full, name):
    st = os.stat(full)
    d = os.path.isdir(full)
    import time
    tm = time.strftime("%b %d %H:%M", time.localtime(st.st_mtime))
    return "%srwxr-xr-x 1 owner group %d %s %s" % ("d" if d else "-", 0 if d else st.st_size, tm, name)

def handle(ctrl):
    def send(s):
        ctrl.sendall((s + "\r\n").encode("utf-8"))
    f = ctrl.makefile("rb")
    send("220 Mini FTP ready")
    pasv = None
    rnfr = None
    authenticated = False
    for raw in f:
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        up = line.upper()
        try:
            if up.startswith("USER"):
                send("331 need pass" if line[4:].strip() == USER else "230 logged in")
                authenticated = line[4:].strip() == USER
            elif up.startswith("PASS"):
                send("230 logged in" if (authenticated and line[4:].strip() == PASS) else "530 bad login")
            elif up.startswith("TYPE") or up.startswith("OPTS"):
                send("200 ok")
            elif up.startswith("PASV"):
                p = socket.socket()
                p.bind(("0.0.0.0", 0))
                p.listen(1)
                port = p.getsockname()[1]
                ip = ctrl.getsockname()[0]
                octs = ".".join(str(int(x)) for x in ip.split("."))
                send("227 Entering Passive Mode (%s,%d,%d)" % (",".join(octs.split(".")), port // 256, port % 256))
                pasv = p
            elif up.startswith("LIST"):
                if not pasv:
                    send("425 no pasv"); continue
                send("150 opening data")
                rel = line[4:].strip()
                base = safe(rel if rel != "." else "") or ROOT
                lines = []
                for name in sorted(os.listdir(base)):
                    lines.append(list_line(os.path.join(base, name), name))
                d, _ = pasv.accept()
                d.sendall(("\r\n".join(lines) + "\r\n").encode("utf-8"))
                d.close(); pasv.close(); pasv = None
                send("226 transfer complete")
            elif up.startswith("STOR"):
                if not pasv:
                    send("425 no pasv"); continue
                send("150 ready")
                rel = line[4:].strip()
                target = safe(rel)
                if not target:
                    send("550 denied"); continue
                d, _ = pasv.accept()
                buf = b""
                while True:
                    chunk = d.recv(65536)
                    if not chunk: break
                    buf += chunk
                d.close(); pasv.close(); pasv = None
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as w:
                    w.write(buf)
                send("226 transfer complete")
            elif up.startswith("RETR"):
                if not pasv:
                    send("425 no pasv"); continue
                target = safe(line[4:].strip())
                if not target or not os.path.isfile(target):
                    send("550 not found"); continue
                send("150 opening data")
                d, _ = pasv.accept()
                with open(target, "rb") as r:
                    while True:
                        chunk = r.read(65536)
                        if not chunk: break
                        d.sendall(chunk)
                d.close(); pasv.close(); pasv = None
                send("226 transfer complete")
            elif up.startswith("MKD"):
                t = safe(line[3:].strip())
                if t and not os.path.isdir(t):
                    os.makedirs(t); send("257 created")
                else:
                    send("550 exists or denied")
            elif up.startswith("DELE"):
                t = safe(line[4:].strip())
                if t and os.path.isfile(t):
                    os.remove(t); send("250 deleted")
                else:
                    send("550 not found")
            elif up.startswith("RMD"):
                t = safe(line[3:].strip())
                if t and os.path.isdir(t):
                    os.rmdir(t); send("250 removed")
                else:
                    send("550 not found")
            elif up.startswith("RNFR"):
                rnfr = safe(line[4:].strip()); send("350 ready")
            elif up.startswith("RNTO"):
                t = safe(line[4:].strip())
                if rnfr and t and os.path.exists(rnfr):
                    os.rename(rnfr, t); send("250 renamed")
                else:
                    send("550 failed")
                rnfr = None
            elif up.startswith("QUIT"):
                send("221 bye"); ctrl.close(); return
            else:
                send("502 not implemented: " + line)
        except Exception as e:
            send("550 error: %s" % e)
    ctrl.close()

srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(5)
print("Mini FTP on port %d root=%s" % (PORT, os.path.abspath(ROOT)), flush=True)
while True:
    c, _ = srv.accept()
    threading.Thread(target=handle, args=(c,), daemon=True).start()
