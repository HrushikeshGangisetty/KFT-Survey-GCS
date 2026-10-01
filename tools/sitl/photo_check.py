"""SITL only: were the photos taken on the survey lines, and nowhere else? Part of the rapid-prototype exit check.

    py -3.9 tools/sitl/photo_check.py mission.waypoints photos.csv [--planned 144]
    py -3.9 tools/sitl/photo_check.py mission.waypoints drone1.csv drone2.csv --coverage

mission.waypoints: the GCS's own export of the uploaded plan (Plan -> Export -> Mission Planner .waypoints).
photos.csv: from `sitl_pilot.py --photos`.

A "photo line" is the path from the waypoint followed by DO_SET_CAM_TRIGG_DIST(d > 0) through every waypoint after it
until the camera is switched off (d = 0): one straight segment on an area survey, several on a corridor with bends
(Pass 25). Every photo should lie on one of them: its distance from the nearest photo line
is reported, and a photo more than --tolerance metres off any line (in a turn, on a run-in) counts as out of place.

--coverage (Pass 31: one survey flown by two drones, the second resuming where the first stopped) takes every photo
file together and checks the plan was covered: ArduPilot takes a photo at the start of each photo line and then every
d metres (d is the line's DO_SET_CAM_TRIGG_DIST), so along each line there must be a photo within d/2 of 0, d, 2d, …
A missing one is a gap. It also reports the largest step between neighbouring photos on a line, which at a hand-over
with no gap is at most about d. Exit code 1 if a planned photo position has no photo.
Plain Python, no dependencies: a flat local map is exact enough over a survey field (as in core:geo).
"""
import argparse
import math
import sys

R = 6371008.8  # mean earth radius, the same as core:geo Geodesy


def read_waypoints(path):
    """[(command, lat, lon, param1)] for seq 1.., from the "QGC WPL 110" format (seq 0 is home, S11)."""
    rows = [line.split("\t") for line in open(path).read().splitlines()[1:] if line.strip()]
    return [(int(r[3]), float(r[8]), float(r[9]), float(r[4])) for r in rows if r[0] != "0"]


def photo_lines(items, triggers=None):
    """Each path flown with the camera on, as a list of waypoint positions. `triggers`, if given, gets each line's
    trigger distance."""
    lines, current, last_wp = [], None, None
    for cmd, lat, lon, p1 in items:
        if cmd == 16:  # NAV_WAYPOINT
            if current is not None:
                current.append((lat, lon))
            last_wp = (lat, lon)
        elif cmd == 206 and p1 > 0:  # DO_SET_CAM_TRIGG_DIST on: from the waypoint just reached
            current = [last_wp]
            if triggers is not None:
                triggers.append(p1)
        elif cmd == 206 and current is not None:  # off: the path ends at the waypoint just reached
            lines.append(current)
            current = None
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("waypoints")
    ap.add_argument("photos", nargs="+", help="one CSV per drone")
    ap.add_argument("--coverage", action="store_true", help="check every planned photo position has a photo")
    ap.add_argument("--planned", type=int, help="the photo count the GCS planned")
    ap.add_argument("--tolerance", type=float, default=10.0, help="metres a photo may be off its line")
    a = ap.parse_args()

    triggers = []
    lines = photo_lines(read_waypoints(a.waypoints), triggers)
    photos = [tuple(map(float, l.split(",")[1:3])) for f in a.photos for l in open(f).read().splitlines()[1:] if l.strip()]
    lat0 = sum(p[0] for p in photos) / len(photos)

    def xy(p):
        return (math.radians(p[1]) * R * math.cos(math.radians(lat0)), math.radians(p[0]) * R)

    def distance_to_segment(p, a, b):
        (px, py), (ax, ay), (bx, by) = xy(p), xy(a), xy(b)
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - ax - t * dx, py - ay - t * dy)

    def distance_to_line(p, line):
        return min(distance_to_segment(p, a, b) for a, b in zip(line, line[1:]))

    off = [min(distance_to_line(p, line) for line in lines) for p in photos]
    outside = [d for d in off if d > a.tolerance]
    print(f"photo lines: {len(lines)}, photos: {len(photos)}" + (f", planned: {a.planned} (difference {len(photos) - a.planned:+d})" if a.planned else ""))
    print(f"distance from the nearest photo line: max {max(off):.1f} m, mean {sum(off) / len(off):.1f} m")
    print(f"photos more than {a.tolerance:.0f} m off every line (turns, run-ins): {len(outside)}")

    # Per line, in flight order: where a plane is still settling after its turn shows in the first photos (Pass 16/18).
    # Photos are logged in time order, so each line's first entries are the ones taken first.
    nearest = [min(range(len(lines)), key=lambda i: distance_to_line(p, lines[i])) for p in photos]
    for i in range(len(lines)):
        d = [off[k] for k in range(len(photos)) if nearest[k] == i]
        if d:
            print(f"line {i + 1}: {len(d)} photos, first three {', '.join(f'{x:.1f}' for x in d[:3])} m off, max {max(d):.1f} m")

    if a.coverage:
        def along(p, line):
            """How far along `line` the point of it nearest to p is."""
            best, walked = (float("inf"), 0.0), 0.0
            for s, e in zip(line, line[1:]):
                (px, py), (ax, ay), (bx, by) = xy(p), xy(s), xy(e)
                dx, dy = bx - ax, by - ay
                length = math.hypot(dx, dy)
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (length * length)))
                dist = math.hypot(px - ax - t * dx, py - ay - t * dy)
                if dist < best[0]:
                    best = (dist, walked + t * length)
                walked += length
            return best[1]

        missing, worst_step, expected_total = [], 0.0, 0
        for i, line in enumerate(lines):
            d = triggers[i]
            length = sum(math.hypot(xy(e)[0] - xy(s)[0], xy(e)[1] - xy(s)[1]) for s, e in zip(line, line[1:]))
            at = sorted(along(photos[k], line) for k in range(len(photos)) if nearest[k] == i and off[k] <= a.tolerance)
            # The camera is switched off half a trigger distance after the last planned photo, so the line as exported
            # is (n - 1/2) * d long: n planned photos at 0, d, ..., (n - 1) * d.
            n = int(length / d + 0.5 + 1e-6)
            expected_total += n
            for k in range(n):
                if not any(abs(x - k * d) <= d / 2 for x in at):
                    missing.append((i + 1, k * d))
            steps = [y - x for x, y in zip(at, at[1:])]
            if steps:
                worst_step = max(worst_step, max(steps))
        print(f"coverage: {expected_total} planned photo positions on {len(lines)} lines, {len(missing)} without a photo; "
              f"largest step between neighbouring photos on a line: {worst_step:.1f} m (trigger distance {max(triggers):.1f} m)")
        for line_no, where in missing[:20]:
            print(f"  GAP: line {line_no}, {where:.0f} m along it")
        if missing:
            sys.exit(1)


if __name__ == "__main__":
    main()
