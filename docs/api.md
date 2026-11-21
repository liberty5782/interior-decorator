# API & data conventions

The contract shared by the backend, the Android app and the Three.js viewer.
Interactive docs for every endpoint live at `http://<server>:8000/docs` (OpenAPI at `/openapi.json`).

## Basics

- Base path: `/api`. Media files: `/media/...` (URLs in responses are server-relative).
- JSON everywhere, except uploads (multipart).
- No auth: the server is meant for LAN/VPN only. Don't expose it to the internet.
- IDs are integers. Timestamps are ISO-8601 UTC.

## Coordinates & matrices

- Units: **metres**. Axes follow ARCore world space: **+Y up**, right-handed.
- Every 4×4 transform is **16 floats in column-major order**. This is what ARCore's
  `Pose.toMatrix(float[], 0)` writes and what Three.js `Matrix4.fromArray()` reads, so
  neither side needs to transpose. The translation is at indices 12, 13, 14.
- `Snapshot.pose` is the **world-from-camera** pose (ARCore `Camera.getPose()`, or
  `getDisplayOrientedPose()` if the saved image is display-rotated; the app must be consistent).
- `Placement.transform` is **item-to-world**. The item's local origin is the centre of its
  footprint on the floor (for wall decor: the centre of its back face), with local +Y up and
  local +Z facing out from the wall / towards the item's front.

## Rooms, layouts, placements

```
Room 1─* Snapshot
Room 1─* Layout 1─* Placement *─1 Item
                    Placement *─0..1 Snapshot (where it was placed; informational)
```

- A **layout** is one option to compare (option A vs B). `POST /layouts/{id}/duplicate`
  copies a layout with its placements.
- `PUT /layouts/{id}/placements` replaces all placements atomically, which suits a "save"
  button in an editor. If any entry is invalid, nothing changes.
- Deleting a room deletes its snapshots, layouts, placements and files.
- Deleting a snapshot keeps placements made on it (their `snapshot_id` becomes `null`).
- An item used by a placement can't be deleted (`409`).

## Snapshot upload

`POST /api/rooms/{room_id}/snapshots`, `multipart/form-data`:

| part    | required | content |
|---------|----------|---------|
| `meta`  | yes | JSON string, see below |
| `image` | yes | JPEG, PNG or WebP camera image |
| `depth` | no  | 16-bit PNG, or a raw buffer (stored as `.bin`) described by `meta.depth_meta` |

```jsonc
{
  "pose": [16 floats],                      // world-from-camera
  "intrinsics": {                           // pixels, for an image of width x height
    "fx": 1000, "fy": 1000, "cx": 640, "cy": 360, "width": 1280, "height": 720
  },
  "planes": [{
    "id": "optional-stable-id",
    "type": "horizontal_up | horizontal_down | vertical",
    "center_pose": [16 floats],             // plane-to-world; local +Y is the normal
    "extent_x": 3.0, "extent_z": 4.0,
    "polygon": [[x, z], ...],               // boundary in the plane's local frame
    "label": "floor"                        // optional, free-form
  }],
  "depth_meta": { "format": "uint16_mm", "width": 160, "height": 120 },  // optional, free-form
  "capture_source": "arcore"                // or "manual" (reserved for non-ARCore devices)
}
```

Notes:
- The intrinsics must describe the uploaded image. If the app crops or rotates the image,
  adjust the intrinsics (ARCore's `Camera.getImageIntrinsics()` is for the CPU image, and
  `getTextureIntrinsics()` is for the GPU texture).
- Extra fields on a plane are kept, so the app can add data without a server change.
- `capture_source: "manual"` leaves room for a future non-ARCore capture path (e.g. a
  de-Googled phone), where scale comes from a user-entered reference instead of ARCore.

## Item assets

- `PUT /api/items/{id}/image`: product image (JPEG/PNG/WebP). Used as the texture for wall decor.
- `PUT /api/items/{id}/model`: GLB model (optional; otherwise clients draw a W×D×H box).
- `PUT /api/rooms/{id}/model`: box-room GLB for the walkthrough viewer.
- File types are checked by their magic bytes. The size limit is `ROOMPLANNER_MAX_UPLOAD_MB`.
