"""SITL only: were the photos taken on the survey lines, and nowhere else? Part of the rapid-prototype exit check.

    py -3.9 tools/sitl/photo_check.py mission.waypoints photos.csv [--planned 144]

mission.waypoints: the GCS's own export of the uploaded plan (Plan -> Export -> Mission Planner .waypoints).
photos.csv: from `sitl_pilot.py --photos`.

A "photo line" is the stretch between the waypoint followed by DO_SET_CAM_TRIGG_DIST(d > 0) and the next waypoint
(followed by the camera switched off). Every photo should lie on one of them: its distance from the nearest photo line
is reported, and a photo more than --tolerance metres off any line (in a turn, on a run-in) counts as out of place.
Plain Python, no dependencies: a flat local map is exact enough over a survey field (as in core:geo).
"""
import argparse
import math

R = 6371008.8  # mean earth radius, the same as core:geo Geodesy


def read_waypoints(path):
    """[(command, lat, lon, param1)] for seq 1.., from the "QGC WPL 110" format (seq 0 is home, S11)."""
    rows = [line.split("\t") for line in open(path).read().splitlines()[1:] if line.strip()]
    return [(int(r[3]), float(r[8]), float(r[9]), float(r[4])) for r in rows if r[0] != "0"]


def photo_lines(items):
    """The (start, end) positions between which the camera is on."""
    lines, start, last_wp = [], None, None
    for cmd, lat, lon, p1 in items:
        if cmd == 16:  # NAV_WAYPOINT
            if start is not None:
                lines.append((start, (lat, lon)))
                start = None
            last_wp = (lat, lon)
        elif cmd == 206 and p1 > 0:  # DO_SET_CAM_TRIGG_DIST on: from the waypoint just reached
            start = last_wp
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("waypoints")
    ap.add_argument("photos")
    ap.add_argument("--planned", type=int, help="the photo count the GCS planned")
    ap.add_argument("--tolerance", type=float, default=10.0, help="metres a photo may be off its line")
    a = ap.parse_args()

    lines = photo_lines(read_waypoints(a.waypoints))
    photos = [tuple(map(float, l.split(",")[1:3])) for l in open(a.photos).read().splitlines()[1:] if l.strip()]
    lat0 = sum(p[0] for p in photos) / len(photos)

    def xy(p):
        return (math.radians(p[1]) * R * math.cos(math.radians(lat0)), math.radians(p[0]) * R)

    def distance_to_segment(p, s):
        (px, py), (ax, ay), (bx, by) = xy(p), xy(s[0]), xy(s[1])
        dx, dy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - ax - t * dx, py - ay - t * dy)

    off = [min(distance_to_segment(p, s) for s in lines) for p in photos]
    outside = [d for d in off if d > a.tolerance]
    print(f"photo lines: {len(lines)}, photos: {len(photos)}" + (f", planned: {a.planned} (difference {len(photos) - a.planned:+d})" if a.planned else ""))
    print(f"distance from the nearest photo line: max {max(off):.1f} m, mean {sum(off) / len(off):.1f} m")
    print(f"photos more than {a.tolerance:.0f} m off every line (turns, run-ins): {len(outside)}")

    # Per line, in flight order: where a plane is still settling after its turn shows in the first photos (Pass 16/18).
    # Photos are logged in time order, so each line's first entries are the ones taken first.
    nearest = [min(range(len(lines)), key=lambda i: distance_to_segment(p, lines[i])) for p in photos]
    for i in range(len(lines)):
        d = [off[k] for k in range(len(photos)) if nearest[k] == i]
        if d:
            print(f"line {i + 1}: {len(d)} photos, first three {', '.join(f'{x:.1f}' for x in d[:3])} m off, max {max(d):.1f} m")


if __name__ == "__main__":
    main()
