"""SITL only: plays the pilot's RC role for the week 2-3 exit check. Never part of the GCS (spec S9).

Sends what MAVProxy sends for `mode guided`, `arm throttle`, `takeoff <alt>` and `mode auto`, so the check can run
unattended. By hand, type the same four commands into the MAVProxy console instead.

Plane uses ArduPlane's own TAKEOFF mode instead (MAVProxy: `arm throttle`, `mode takeoff`, then `mode auto`): it
climbs out along the runway heading to TKOFF_ALT and works on every ArduPlane version, unlike GUIDED takeoff.

    py -3.9 tools/sitl/sitl_pilot.py tcp:127.0.0.1:5763 --alt 20

With --arm-only it stops once armed (Copter in GUIDED, on the ground): the params SITL check (Pass 18) uses that to
see the GCS refuse PARAM_SET while armed. Copter disarms by itself about 10 s later (DISARM_DELAY).

With --photos photos.csv it then stays connected and writes one line per CAMERA_FEEDBACK (the photos the GCS counts)
until the vehicle leaves AUTO, disarms or finishes the mission, for tools/sitl/photo_check.py.

With --low-battery-after N (needs --photos) the simulated battery runs low after the N-th photo: before arming it sets
the battery failsafe to RTL (BATT_FS_LOW_ACT 2; BATT_LOW_VOLT stays at its 10.5 V default), and after the N-th photo
it drops the simulator's battery to 10.0 V (SIM_BATT_VOLTAGE). ArduPilot's failsafe then switches to RTL about 10 s
later (BATT_LOW_TIMER). That is the Pass 31 check: a survey that stops part-way and is resumed by another drone.
These are simulator and vehicle settings made by the "pilot", not by the GCS.

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
    parser.add_argument("--arm-only", action="store_true", help="arm, then exit (no takeoff)")
    parser.add_argument("--photos", help="CSV file for the photo positions (CAMERA_FEEDBACK) until the mission ends")
    parser.add_argument("--low-battery-after", type=int, help="after this many photos, run the simulated battery low (failsafe: RTL)")
    args = parser.parse_args()

    master = mavutil.mavlink_connection(args.link, source_system=254)  # 254: not the GCS's 255
    heartbeat = master.wait_heartbeat()
    print(f"vehicle {master.target_system}, {master.flightmode}")

    plane = heartbeat.type == mavutil.mavlink.MAV_TYPE_FIXED_WING
    if args.low_battery_after:
        # A full battery to start with (an earlier run may have left it low), and RTL as the low-battery action.
        set_param(master, "SIM_BATT_VOLTAGE", 12.6)
        set_param(master, "BATT_FS_LOW_ACT", 2)
        time.sleep(2)
    if not plane:
        master.set_mode("GUIDED")                    # MAVProxy: mode guided
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
    if args.arm_only:
        return

    if plane:
        master.set_mode("TAKEOFF")                   # MAVProxy: mode takeoff
    else:
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
    if args.photos:
        log_photos(master, args.photos, args.low_battery_after)


def set_param(master, name, value):
    master.mav.param_set_send(master.target_system, master.target_component, name.encode(), value,
                              mavutil.mavlink.MAV_PARAM_TYPE_REAL32)


def log_photos(master, path, low_battery_after=None):
    """Records CAMERA_FEEDBACK (ardupilotmega.xml #180) until the mission is over: out of AUTO, disarmed, or
    MISSION_CURRENT reporting MISSION_STATE_COMPLETE (5)."""
    n = 0
    with open(path, "w") as out:
        out.write("img_idx,lat,lon,alt_rel,roll\n")
        while True:
            m = master.recv_match(type=["CAMERA_FEEDBACK", "HEARTBEAT", "MISSION_CURRENT"], blocking=True, timeout=5)
            if m is None:
                continue
            t = m.get_type()
            if t == "CAMERA_FEEDBACK":
                n += 1
                out.write(f"{m.img_idx},{m.lat / 1e7:.7f},{m.lng / 1e7:.7f},{m.alt_rel:.1f},{m.roll:.1f}\n")
                out.flush()
                if n == low_battery_after:
                    print(f"photo {n}: the simulated battery drops to 10.0 V", flush=True)
                    set_param(master, "SIM_BATT_VOLTAGE", 10.0)
            elif t == "HEARTBEAT" and m.get_srcComponent() == 1 and (master.flightmode != "AUTO" or not master.motors_armed()):
                break
            elif t == "MISSION_CURRENT" and getattr(m, "mission_state", 0) == 5:
                break
    print(f"{n} photos written to {path} (mission over: {master.flightmode})")


if __name__ == "__main__":
    main()
