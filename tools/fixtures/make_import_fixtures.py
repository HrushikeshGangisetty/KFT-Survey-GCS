"""Writes the import test fixtures in core/geo-io/src/jvmTest/resources/import (Pass 24).

Run from the repo root:  pip install pyproj pyshp  then  python tools/fixtures/make_import_fixtures.py

The fixtures come from independent tools, so the tests compare our readers against something we didn't write:
pyproj (PROJ) projects the known latitudes/longitudes below into UTM 44N, pyshp writes the shapefiles, and zipfile
builds the KMZ and the zipped shapefile. The tests expect the FIELD / HOLE / CANAL / POINTS values printed here.
"""
import json
import os
import zipfile

import shapefile  # pyshp
from pyproj import CRS, Transformer
from pyproj.enums import WktVersion

OUT = "core/geo-io/src/jvmTest/resources/import"

# A field near Hyderabad (UTM 44N), as (lat, lon). Clockwise seen from above: NW, NE, SE, SW.
FIELD = [(17.4100, 78.4700), (17.4100, 78.4800), (17.4020, 78.4810), (17.4010, 78.4690)]
HOLE = [(17.4060, 78.4740), (17.4060, 78.4760), (17.4050, 78.4760), (17.4050, 78.4740)]  # a pond
CANAL = [(17.4000, 78.4650), (17.4050, 78.4700), (17.4120, 78.4720)]
POINTS = [("Gate", 17.4015, 78.4695), ("Pump", 17.4090, 78.4790)]

to_utm = Transformer.from_crs("EPSG:4326", "EPSG:32644", always_xy=True)


def utm(ring):
    return [to_utm.transform(lon, lat) for lat, lon in ring]


def lonlat(ring):
    return [[lon, lat] for lat, lon in ring]


def closed(ring):
    return ring + ring[:1]


def ccw(ring):
    return list(reversed(ring))


os.makedirs(OUT, exist_ok=True)

# ---- KML, the way Google Earth writes it: namespaces, a Document, folders, a polygon with a hole, MultiGeometry.
def coords(ring, close=False):
    r = closed(ring) if close else ring
    return " ".join(f"{lon},{lat},0" for lat, lon in r)


kml = f"""<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
<Document>
  <name>Farm survey.kml</name>
  <Folder>
    <name>Areas</name>
    <Placemark>
      <name>North field</name>
      <Polygon><tessellate>1</tessellate>
        <outerBoundaryIs><LinearRing><coordinates>
          {coords(FIELD, close=True)}
        </coordinates></LinearRing></outerBoundaryIs>
        <innerBoundaryIs><LinearRing><coordinates>{coords(HOLE, close=True)}</coordinates></LinearRing></innerBoundaryIs>
      </Polygon>
    </Placemark>
    <Placemark>
      <name>Twin plots</name>
      <MultiGeometry>
        <Polygon><outerBoundaryIs><LinearRing><coordinates>78.46,17.39 78.461,17.39 78.461,17.391 78.46,17.39</coordinates></LinearRing></outerBoundaryIs></Polygon>
        <Polygon><outerBoundaryIs><LinearRing><coordinates>78.462,17.39 78.463,17.39 78.463,17.391 78.462,17.39</coordinates></LinearRing></outerBoundaryIs></Polygon>
      </MultiGeometry>
    </Placemark>
  </Folder>
  <Placemark><name>Canal</name><LineString><coordinates>{coords(CANAL)}</coordinates></LineString></Placemark>
""" + "".join(
    f"  <Placemark><name>{n}</name><Point><coordinates>{lon},{lat},0</coordinates></Point></Placemark>\n" for n, lat, lon in POINTS
) + """</Document>
</kml>
"""
open(f"{OUT}/farm.kml", "w", encoding="utf-8").write(kml)
with zipfile.ZipFile(f"{OUT}/farm.kmz", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("doc.kml", kml)
    z.writestr("files/icon.png", b"\x89PNG not really")  # KMZs carry icons; they must be ignored

# ---- GeoJSON (RFC 7946, WGS 84)
geojson = {
    "type": "FeatureCollection",
    "features": [
        {"type": "Feature", "properties": {"name": "North field"},
         "geometry": {"type": "Polygon", "coordinates": [lonlat(closed(FIELD)), lonlat(closed(ccw(HOLE)))]}},
        {"type": "Feature", "properties": {"Name": "Canal"}, "geometry": {"type": "LineString", "coordinates": lonlat(CANAL)}},
        {"type": "Feature", "properties": {},
         "geometry": {"type": "MultiPoint", "coordinates": [[lon, lat] for _, lat, lon in POINTS]}},
    ],
}
json.dump(geojson, open(f"{OUT}/farm.geojson", "w"), indent=1)

# ---- GeoJSON with the 2008 "crs" member: the field in UTM 44N metres
old = {"type": "FeatureCollection",
       "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::32644"}},
       "features": [{"type": "Feature", "properties": {"name": "North field"},
                     "geometry": {"type": "Polygon", "coordinates": [[list(p) for p in closed(utm(FIELD))]]}}]}
json.dump(old, open(f"{OUT}/farm_utm_crs.geojson", "w"), indent=1)

# ---- Shapefiles
utm_prj = CRS.from_epsg(32644).to_wkt(WktVersion.WKT1_ESRI)
wgs_prj = CRS.from_epsg(4326).to_wkt(WktVersion.WKT1_ESRI)
# Kalianpur 1975 / India zone I (Lambert conic on an Everest datum): needs a datum shift we do not do.
kalianpur_prj = CRS.from_epsg(24378).to_wkt(WktVersion.WKT1_ESRI)


def write_shp(base, kind, shapes, prj):
    w = shapefile.Writer(f"{OUT}/{base}", shapeType=kind)
    w.field("name", "C")
    for name, geom in shapes:
        if kind == shapefile.POLYGON:
            w.poly(geom)
        elif kind == shapefile.POLYLINE:
            w.line(geom)
        else:
            w.point(*geom)
        w.record(name)
    w.close()
    if prj:
        open(f"{OUT}/{base}.prj", "w").write(prj)


# The spec's orientation: outer ring clockwise, hole counter-clockwise (pyshp writes rings as given).
write_shp("field_utm", shapefile.POLYGON, [("North field", [closed(utm(FIELD)), closed(utm(ccw(HOLE)))])], utm_prj)
write_shp("canal_wgs84", shapefile.POLYLINE, [("Canal", [[(lon, lat) for lat, lon in CANAL]])], wgs_prj)
write_shp("points_noprj", shapefile.POINT, [(n, (lon, lat)) for n, lat, lon in POINTS], None)
open(f"{OUT}/kalianpur.prj", "w").write(kalianpur_prj)
with zipfile.ZipFile(f"{OUT}/field_utm.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for ext in ("shp", "shx", "dbf", "prj"):
        z.write(f"{OUT}/field_utm.{ext}", f"Field survey/field_utm.{ext}")  # inside a folder, as people zip them

print("UTM prj:", utm_prj[:80], "...")
print("field in UTM:", utm(FIELD))
