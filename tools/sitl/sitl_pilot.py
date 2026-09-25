"""SITL only: plays the pilot's RC role for the week 2-3 exit check. Never part of the GCS (spec S9).

Sends what MAVProxy sends for `mode guided`, `arm throttle`, `takeoff <alt>` and `mode auto`, so the check can run
unattended. By hand, type the same four commands into the MAVProxy console instead.

    py -3.9 tools/sitl/sitl_pilot.py tcp:127.0.0.1:5763 --alt 20

Needs pymavlink (it comes with `pip install MAVProxy`). Connect to a SITL port the GCS isn't using: SERIAL2 (5763).
"""
import argparse
import time

from pymavlink import mavutil


def wait_mode(master, mode, timeout=15):
    """Blocks until the heartbeat reports `mode` (ArduPilot mode name), or fails."""
    end = time.time() + timeout
    while time.time() < end:
        master.recv_match(type="HEARTBEAT", blocking=True, timeout=1)
        if master.flightmode == mode:
            return
    raise SystemExit(f"vehicle never reached {mode} (now {master.flightmode})")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("link", help="e.g. tcp:127.0.0.1:5763")
    parser.add_argument("--alt", type=float, default=20)
    args = parser.parse_args()

    master = mavutil.mavlink_connection(args.link, source_system=254)  # 254: not the GCS's 255
    master.wait_heartbeat()
    print(f"vehicle {master.target_system}, {master.flightmode}")

    master.set_mode("GUIDED")                        # MAVProxy: mode guided
    wait_mode(master, "GUIDED")
    print("GUIDED")

    # MAVProxy: arm throttle. SITL may refuse until its EKF and GPS are happy, so retry for a while.
    for _ in range(30):
        master.arducopter_arm()                      # COMMAND_LONG COMPONENT_ARM_DISARM(1), valid for Plane too
        time.sleep(1)
        master.recv_match(type="HEARTBEAT", blocking=True, timeout=1)
        if master.motors_armed():
            break
    else:
        raise SystemExit("arming refused (see STATUSTEXT in the GCS)")
    print("ARMED")

    # MAVProxy: takeoff <alt> -> COMMAND_LONG NAV_TAKEOFF, param7 = altitude.
    master.mav.command_long_send(master.target_system, master.target_component,
                                 mavutil.mavlink.MAV_CMD_NAV_TAKEOFF, 0, 0, 0, 0, 0, 0, 0, args.alt)
    end = time.time() + 60
    while time.time() < end:
        pos = master.recv_match(type="GLOBAL_POSITION_INT", blocking=True, timeout=1)
        if pos and pos.relative_alt / 1000 >= args.alt * 0.9:
            break
    print(f"climbed to ~{args.alt} m")

    master.set_mode("AUTO")                          # MAVProxy: mode auto
    wait_mode(master, "AUTO")
    print("AUTO: the mission is flying; watch the GCS")


if __name__ == "__main__":
    main()
