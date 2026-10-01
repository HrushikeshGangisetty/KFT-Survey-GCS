"""SITL only: waits until every vehicle says it can be armed, then exits 0 (1 after --timeout seconds).

    py -3.9 tools/sitl/wait_ready.py 5763 5773 5783

A SITL vehicle refuses to arm for a minute or more after it starts (longer after -Wipe) while its EKF settles, and a
check started too early fails for that reason alone. "Ready" is both of:
- the PREARM_CHECK bit of SYS_STATUS's sensor health (MAV_SYS_STATUS_PREARM_CHECK, common.xml), which ArduPilot sets
  when its pre-arm checks pass;
- the EKF reporting an absolute horizontal position (EKF_STATUS_REPORT flag EKF_POS_HORIZ_ABS, ardupilotmega.xml).
  The pre-arm bit alone isn't enough: it is judged for the current mode, and arming in GUIDED (what the pilot script
  does) also needs a position estimate ("Arm: Need Position Estimate").
Ports are each vehicle's SERIAL2 (5763 + 10 per instance), the one sitl_pilot.py uses afterwards.
"""
import argparse
import time

from pymavlink import mavutil

PREARM_CHECK = 0x10000000
EKF_POS_HORIZ_ABS = 16


def ready(port):
    try:
        m = mavutil.mavlink_connection(f"tcp:127.0.0.1:{port}", source_system=253)
        if m.wait_heartbeat(timeout=5) is None:
            return False
        m.mav.request_data_stream_send(m.target_system, m.target_component, mavutil.mavlink.MAV_DATA_STREAM_ALL, 2, 1)
        s = m.recv_match(type="SYS_STATUS", blocking=True, timeout=5)
        e = m.recv_match(type="EKF_STATUS_REPORT", blocking=True, timeout=5)
        m.close()
        return (s is not None and bool(s.onboard_control_sensors_health & PREARM_CHECK)
                and e is not None and bool(e.flags & EKF_POS_HORIZ_ABS))
    except OSError:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ports", nargs="+", type=int)
    ap.add_argument("--timeout", type=float, default=300)
    a = ap.parse_args()
    end = time.time() + a.timeout
    while time.time() < end:
        states = {p: ready(p) for p in a.ports}
        if all(states.values()):
            print("all ready to arm")
            return
        print("waiting: " + ", ".join(f"{p} {'ready' if ok else 'not yet'}" for p, ok in states.items()), flush=True)
        time.sleep(5)
    raise SystemExit("not ready in time")


if __name__ == "__main__":
    main()
