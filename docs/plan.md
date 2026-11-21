# Room Planner App — Tech Stack & Build Plan

## Goal
An interior-decorating app for our house. Capture rooms with the phone camera and place true-to-scale furniture and wall decor, so we can compare several layout options before buying anything.

- **MVP:** place items on captured "photos" of rooms
- **Stretch 1:** live AR placement through the camera
- **Stretch 2:** a navigable 3D model of each room, to get a feel for space and furniture footprint

## Core design principle
**Never capture plain photos.** A plain photo has no scale. Every capture is an **AR snapshot** taken during an ARCore session. It stores:
- the camera image
- camera pose + intrinsics
- detected planes (floor, walls)
- depth image (when the device supports the ARCore Depth API)

Because every snapshot is metric, the photo MVP and live AR share the same placement code and the same data model. Live AR is the same feature running on the live session instead of a saved frame.

## Stack

### Mobile app (Android)
- **Kotlin + Jetpack Compose** (same as the PhotoShare app)
- **SceneView** (`io.github.sceneview:arsceneview`): Compose-native 3D/AR library with ARCore for perception and Filament for rendering. Successor to Sceneform, which Google archived. Check Maven Central for the current version.
- **ARCore**: plane detection, anchors, Depth API, hit-testing
- Requires Google Play Services for AR on the device

### Assets
glTF/GLB everywhere.
- **Furniture (MVP):** parametric "dimension boxes" generated from W×D×H taken from product listings. That answers the footprint question without needing real models.
- **Furniture (later):** real GLB models from CC0 sources, or user-imported
- **Wall decor:** a flat quad textured with the product image and scaled to the real dimensions. Snaps to detected wall planes.

### Backend (self-hosted on arrbox)
- **FastAPI** (same as PhotoShare)
- **SQLite to start** (Postgres if needed later): rooms, snapshots, layouts, item catalog
- Filesystem storage for images, depth maps and GLBs
- REST API consumed by both the Android app and the web viewer

### 3D walkthrough viewer
- **Three.js** web app served from the homelab
- Loads the room GLB plus a layout JSON (item IDs + transforms)
- First-person / orbit navigation; usable from desktop or phone

## Data model (draft)
```
Room        { id, name, created_at, room_model_glb? }
Snapshot    { id, room_id, image_path, depth_path?, pose(4x4), intrinsics, planes[json], created_at }
Item        { id, name, kind: furniture|wall_decor, width_m, depth_m, height_m,
              model_glb?, image_path?, source_url?, price? }
Layout      { id, room_id, name, created_at }       // one "option" to compare
Placement   { id, layout_id, item_id, transform(4x4, room/world space), snapshot_id? }
```
Layouts are the unit of comparison. Several layouts per room makes it easy to view option A vs. option B.

## Phases

### Phase 0: Skeleton
- Compose app + SceneView AR session running on device
- FastAPI service with CRUD for rooms, items and layouts
- Item entry form (name, dimensions, optional image/URL)

### Phase 1: AR snapshot MVP
- Capture an AR snapshot (image + pose + intrinsics + planes + depth) and upload it
- Snapshot viewer: render the still image as background with a virtual camera matching the saved pose/intrinsics
- Place dimension boxes on the floor plane and decor quads on wall planes; drag, rotate, delete
- Save placements to a named layout; switch between layouts
- Optional: occlusion using the saved depth map

### Phase 2: Live AR
- Reuse the placement and layout code on the live ARCore session
- Load a saved layout and see it in the room live
- Persist room anchors where feasible so layouts re-align on return visits

### Phase 3: Room model + walkthrough
- In AR, tap the room's corners on the floor plane to measure walls; ceiling height is entered manually or measured
- Extrude to a simple box-room mesh (floor, walls, ceiling, door/window cutouts later) and export as GLB
- Three.js viewer shows the room and the layout, with navigation
- **Optional far stretch:** photoreal capture via Gaussian splatting (e.g. Nerfstudio) on a homelab GPU

## Known risks / gotchas
- **ARCore dependency:** needs Google Play Services for AR. Verify early on every target phone, especially any de-Googled ROM.
- **No RoomPlan on Android:** Apple's RoomPlan (LiDAR) has no Android equivalent and most Android phones lack LiDAR. Expect wall measurements to be approximate; the corner-tapping flow is the pragmatic approach.
- **Wall plane detection** is weak on blank, featureless walls. Provide a manual fallback, such as tapping two points on the wall/floor seam.
- **Alternative considered:** Unity + AR Foundation for cross-platform iOS/Android. Rejected for now because it's heavier, closed-source and a new toolchain. Revisit if iOS (LiDAR + RoomPlan) becomes important.

## Preferences / constraints
- Self-hosted, open source where possible, no third-party cloud services
- Keep conventions consistent with the PhotoShare project (Kotlin/Compose + FastAPI)
