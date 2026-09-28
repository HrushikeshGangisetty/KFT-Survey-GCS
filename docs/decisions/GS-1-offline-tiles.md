# GS-1 — Offline tiles: what each provider allows

**Date checked:** 2026-09-28 · **Pass:** 23 · **Status:** decided for P0, one item open (OpenFreeMap confirmation)

I'm not a lawyer. This records what the providers' own pages say, and what the app does because of it. Check with
KFT's legal/regulatory contact before a commercial release.

## Summary

| Source | Download an area in the app? | Why | What the app does |
|---|---|---|---|
| **Esri World Imagery** (Satellite) | **No** | Esri: "You can take ArcGIS tiles offline when using Esri software that supports offline use. Systematically requesting ArcGIS tiles for offline use through other apps or services is prohibited." ([ArcGIS Online help, "Take web maps offline"](https://doc.arcgis.com/en/arcgis-online/manage-data/take-maps-offline.htm)) | Satellite can't be chosen for download; the Maps tab shows the reason. Tiles already seen while online stay in MapLibre's ordinary cache (normal HTTP caching, not a bulk download). |
| **OpenFreeMap** (Street) | **Yes, operator-started and capped** | Commercial use allowed; they publish weekly planet MBTiles for offline use and support self-hosting ([openfreemap.org](https://openfreemap.org/)). Their [terms](https://openfreemap.org/tos/) forbid collecting data "in automated ways without permission" and say nothing specific about caching or offline regions. | Downloads happen only when the operator presses Download, for the area on screen, capped at 10 000 tiles. A field-sized area at z10–14 is a few hundred vector tiles. |
| **Imported MBTiles** | n/a (already on the device) | The operator brings the file and its licence (their own drone orthomosaic, a licensed imagery provider, a self-hosted OpenFreeMap extract). | Raster MBTiles import on the Maps tab; shown as a basemap on Fly. |

## Open item

- **OpenFreeMap:** email info@openfreemap.org describing the use (operator-started downloads of one survey area at a
  time, z10–14, a few hundred tiles each) and ask for explicit permission. If they say no, the fallback is already
  in place: download their planet/regional MBTiles once, host or import it, and point the Street style at it.

## What this means for satellite in the field

With no network, the satellite layer shows only what MapLibre cached while online. For offline imagery:
1. Import an MBTiles of your own imagery (a drone orthomosaic exported from ODM/Pix4D/QGIS, or a licensed
   provider's export) on the Maps tab; or
2. Buy imagery from a provider whose licence allows offline caching in third-party apps, add it as a
   `TileSourceConfig` with `OfflinePolicy(allowed = true, …)`, and it becomes downloadable (after a
   `TileMath`/download test for its tile size).

Mapbox and Google were not checked for offline use in this pass (the maps decision, 00, already notes Google's
strict caching rules). Check their terms before adding either as a downloadable source.

## Sources

- [Esri — Take web maps offline](https://doc.arcgis.com/en/arcgis-online/manage-data/take-maps-offline.htm) (the quote above)
- [Esri Product-Specific Terms of Use E300](https://www.esri.com/content/dam/esrisites/en-us/media/legal/product-specific-terms-of-use/e300.pdf) (Nov 2025; no clause that permits third-party offline caching)
- [OpenFreeMap](https://openfreemap.org/) · [OpenFreeMap terms of service](https://openfreemap.org/tos/) · [OpenFreeMap on GitHub](https://github.com/hyperknot/openfreemap)
