"""Signing S9: three ArduCopter SITLs (KFT fork, S8) behind the S7 mavlink-router, for SigningSitlRun.

Runs inside WSL (Linux SITL and router), python3 with pymavlink. Two subcommands:

  up     Starts SITL instances 0, 1, 2 (system ids 1, 2, 3) with --wipe, installs drone A's key on instance 0 and drone
         B's on instance 1 over SERIAL0 (SETUP_SIGNING, disarmed USB provisioning as provision.py does), leaves instance 2
         unsigned, puts a logging tap in front of each SERIAL1, and starts mavlink-router with the S7 config's GCS side.
         Writes ready.json, then runs until SIGTERM/SIGINT.
  spoof  T5: sends unsigned and wrong-key HEARTBEAT + GLOBAL_POSITION_INT claiming --sysid into the router, which
         forwards them to the GCS endpoints. Exits after --seconds.

Topology (every port is loopback inside WSL except the two GCS servers, which Windows reaches at the WSL address):

  GCS 1 ─UDP 14550─┐                         ┌─ tap 6762 ─ SITL 0 SERIAL1 (tcp 5762)  key A, sysid 1
  GCS 2 ─UDP 14551─┼─ mavlink-router (S7) ───┼─ tap 6772 ─ SITL 1 SERIAL1 (tcp 5772)  key B, sysid 2
  spoof ─UDP 14560─┘                         └─ tap 6782 ─ SITL 2 SERIAL1 (tcp 5782)  unsigned, sysid 3

Differences from the production router config (platform/router/mavlink-router.conf), all forced by SITL:
TCP endpoints to three SITLs instead of one UART; a second GCS server (the S7 README says one server endpoint serves
one GCS peer) and a loopback injection server. TCP listening stays disabled and the router holds no key, as in S7.

Each tap logs every frame travelling INTO its SITL to taps.log, one line per frame:
    t=<unix s> tap=<instance> sys=<sysid> comp=<compid> msg=<msgid> sig=<A|B|wrong|unsigned> link=<link_id> ts=<ticks>
"sig" is found by checking the signature against each key in keys.json, so heartbeat fan-out and link_ids are
visible without trusting the GCS's own account of what it sent. This file is the "router log" of the S9 brief.

Refuses to run unless every instance reports SIM_ parameters (it only ever talks to a simulator).
"""
import argparse
import hashlib
import json
import os
import signal
import socket
import subprocess
import threading
import time
from pathlib import Path

os.environ["MAVLINK20"] = "1"
from pymavlink import mavutil  # noqa: E402

HOME = (-35.363261, 149.165230, 584, 353)  # CMAC, as start-sitl.ps1
INSTANCES = 3
GCS_PORTS = (14550, 14551)
INJECT_PORT = 14560


def ticks():
    """MAVLink signing timestamp: 10 µs units since 2015-01-01 UTC."""
    return int((time.time() - 1420070400) * 100_000)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Keys:
    def __init__(self, work):
        k = json.loads((work / "keys.json").read_text())
        self.by_name = {d["name"]: bytes.fromhex(d["key_hex"]) for d in k["drones"]}
        self.by_sysid = {d["sysid"]: bytes.fromhex(d["key_hex"]) for d in k["drones"]}
        self.wrong = bytes.fromhex(k["wrong_key_hex"])

    def classify(self, frame):
        """A|B for a signature that verifies with that key, 'wrong' for any other signature, 'unsigned' otherwise."""
        if frame[0] != 0xFD or not frame[2] & 1:
            return "unsigned", "", ""
        n = 10 + frame[1] + 2
        link_id, ts, sig = frame[n], frame[n + 1:n + 7], frame[n + 7:n + 13]
        for name, key in self.by_name.items():
            if hashlib.sha256(key + frame[:n] + bytes([link_id]) + ts).digest()[:6] == sig:
                return name, link_id, int.from_bytes(ts, "little")
        return "wrong", link_id, int.from_bytes(ts, "little")


def frames(buf):
    """Splits complete MAVLink frames off the front of buf; returns (frames, rest)."""
    out, i = [], 0
    while i + 3 <= len(buf):
        if buf[i] not in (0xFD, 0xFE):
            i += 1
            continue
        n = buf[i + 1] + (12 + (13 if buf[i + 2] & 1 else 0) if buf[i] == 0xFD else 8)
        if i + n > len(buf):
            break
        out.append(bytes(buf[i:i + n]))
        i += n
    return out, buf[i:]


class Tap(threading.Thread):
    """TCP pass-through in front of one SITL SERIAL1: forwards bytes untouched and logs frames going into SITL."""

    def __init__(self, instance, listen_port, sitl_port, keys, logfile, lock):
        super().__init__(daemon=True)
        self.instance, self.keys, self.logfile, self.lock = instance, keys, logfile, lock
        self.server = socket.create_server(("127.0.0.1", listen_port))
        self.sitl_port = sitl_port

    def run(self):
        while True:  # the router reconnects if the TCP session drops
            router, _ = self.server.accept()
            sitl = socket.create_connection(("127.0.0.1", self.sitl_port))
            threading.Thread(target=self.pump, args=(sitl, router, False), daemon=True).start()
            self.pump(router, sitl, True)
            for s in (router, sitl):
                s.close()

    def pump(self, src, dst, into_sitl):
        pending = b""
        try:
            while data := src.recv(65536):
                dst.sendall(data)
                if not into_sitl:
                    continue
                found, pending = frames(pending + data)
                lines = []
                for f in found:
                    sig, link_id, ts = self.keys.classify(f)
                    msgid = int.from_bytes(f[7:10], "little") if f[0] == 0xFD else f[5]
                    sysid, comp = (f[5], f[6]) if f[0] == 0xFD else (f[3], f[4])
                    lines.append(f"t={time.time():.3f} tap={self.instance} sys={sysid} comp={comp} msg={msgid} sig={sig} link={link_id} ts={ts}\n")
                with self.lock:
                    self.logfile.writelines(lines)
                    self.logfile.flush()
        except OSError:
            pass


def drain(conn):
    """Keeps reading a SERIAL0 connection so SITL's TCP send buffer never fills."""
    while True:
        try:
            conn.recv_msg()
        except Exception:
            return
        time.sleep(0.01)


def wait_tcp(port, timeout=30):
    end = time.monotonic() + timeout
    while True:
        try:
            return mavutil.mavlink_connection(f"tcp:127.0.0.1:{port}", source_system=254, source_component=190)
        except OSError:
            if time.monotonic() > end:
                raise
            time.sleep(0.2)


def require_sim(conn, sysid):
    conn.mav.param_request_read_send(sysid, 1, b"SIM_SPEEDUP", -1)
    msg = conn.recv_match(type="PARAM_VALUE", blocking=True, timeout=10)
    if msg is None or msg.param_id != "SIM_SPEEDUP":
        raise SystemExit(f"instance with sysid {sysid} has no SIM_ parameters: refusing to run against it")


def up(args):
    work = args.work.resolve()
    keys = Keys(work)
    procs = []

    def stop(*_):
        for p in reversed(procs):
            p.terminate()
        for p in procs:
            try:
                p.wait(5)
            except subprocess.TimeoutExpired:
                p.kill()
        raise SystemExit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    defaults = work / "s9-defaults.parm"
    fork = Path(args.sitl).resolve().parents[3]  # .../ardupilot-kft/build/sitl/bin/arducopter
    defaults.write_text((fork / "Tools/autotest/default_params/copter.parm").read_text() + "\n"
                        + Path(args.kft_params).read_text() + "\nSERIAL1_PROTOCOL 2\n")
    usb = []
    for i in range(INSTANCES):
        d = work / f"sitl{i}"
        d.mkdir(exist_ok=True)
        f = open(d / "sitl.log", "w")
        home = f"{HOME[0]},{HOME[1] + 0.00011 * i},{HOME[2]},{HOME[3]}"  # 10 m east per instance
        procs.append(subprocess.Popen([args.sitl, "--model", "hexa", "--speedup", "1", "-I", str(i), "--sysid", str(i + 1),
                                       "--home", home, "--defaults", str(defaults), "--wipe"],
                                      cwd=d, stdout=f, stderr=subprocess.STDOUT))
        conn = wait_tcp(5760 + 10 * i)
        if not conn.wait_heartbeat(timeout=30):
            raise SystemExit(f"SITL {i}: no heartbeat on SERIAL0")
        require_sim(conn, i + 1)
        key = keys.by_sysid.get(i + 1)
        if key:
            conn.mav.setup_signing_send(i + 1, 1, key, ticks())  # disarmed SERIAL0: provisioning, as provision.py
            log(f"SITL {i} (sysid {i + 1}): key installed")
        else:
            log(f"SITL {i} (sysid {i + 1}): no key, unsigned")
        threading.Thread(target=drain, args=(conn,), daemon=True).start()
        usb.append(conn)

    taplog = open(work / "taps.log", "w")
    lock = threading.Lock()
    for i in range(INSTANCES):
        Tap(i, 6762 + 10 * i, 5762 + 10 * i, keys, taplog, lock).start()

    conf = ["[General]", "TcpServerPort = 0", "ReportStats = false", ""]
    for i in range(INSTANCES):
        conf += [f"[TcpEndpoint sitl{i}]", "Address = 127.0.0.1", f"Port = {6762 + 10 * i}", ""]
    for n, port in enumerate(GCS_PORTS, 1):
        conf += [f"[UdpEndpoint gcs{n}]", "Mode = Server", "Address = 0.0.0.0", f"Port = {port}", ""]
    conf += ["[UdpEndpoint inject]", "Mode = Server", "Address = 127.0.0.1", f"Port = {INJECT_PORT}", ""]
    (work / "router.conf").write_text("\n".join(conf))
    (work / "router.d").mkdir(exist_ok=True)  # empty: no system-wide fragments, as the S7 unit does
    f = open(work / "router.log", "w")
    procs.append(subprocess.Popen([args.router, "-c", str(work / "router.conf"), "-d", str(work / "router.d"), "-v"],
                                  stdout=f, stderr=subprocess.STDOUT))
    ip = subprocess.run(["hostname", "-I"], capture_output=True, text=True).stdout.split()[0]
    (work / "ready.json").write_text(json.dumps(dict(host=ip, gcs_ports=GCS_PORTS, started=time.time(),
        sitl_sha256=hashlib.sha256(Path(args.sitl).read_bytes()).hexdigest(),
        router_sha256=hashlib.sha256(Path(args.router).read_bytes()).hexdigest())))
    log(f"ready: GCS 1 udp {ip}:{GCS_PORTS[0]}, GCS 2 udp {ip}:{GCS_PORTS[1]}")
    while True:
        time.sleep(1)


def spoof(args):
    keys = Keys(args.work.resolve())
    unsigned = mavutil.mavlink_connection(f"udpout:127.0.0.1:{INJECT_PORT}", source_system=args.sysid, source_component=1)
    wrong = mavutil.mavlink_connection(f"udpout:127.0.0.1:{INJECT_PORT}", source_system=args.sysid, source_component=1)
    wrong.setup_signing(keys.wrong, sign_outgoing=True, link_id=1, initial_timestamp=ticks())
    sent = 0
    end = time.monotonic() + args.seconds
    while time.monotonic() < end:
        for c in (unsigned, wrong):
            c.mav.heartbeat_send(mavutil.mavlink.MAV_TYPE_HEXAROTOR, mavutil.mavlink.MAV_AUTOPILOT_ARDUPILOTMEGA, 0, 0, 0)
            # 10 N 10 E at 500 m: thousands of km from CMAC, unmistakable if the GCS ever believed it.
            c.mav.global_position_int_send(0, 100_000_000, 100_000_000, 500_000, 500_000, 0, 0, 0, 0)
            sent += 2
        time.sleep(0.2)
    log(f"spoof: sent {sent} frames claiming sysid {args.sysid} (half unsigned, half wrong key)")


ap = argparse.ArgumentParser()
sub = ap.add_subparsers(dest="cmd", required=True)
u = sub.add_parser("up")
u.add_argument("--work", type=Path, required=True)
u.add_argument("--sitl", required=True)
u.add_argument("--router", required=True)
u.add_argument("--kft-params", required=True, help="GNSS-Denied tools/sitl/kft_locked.parm")
s = sub.add_parser("spoof")
s.add_argument("--work", type=Path, required=True)
s.add_argument("--sysid", type=int, required=True)
s.add_argument("--seconds", type=float, default=5)
a = ap.parse_args()
up(a) if a.cmd == "up" else spoof(a)
