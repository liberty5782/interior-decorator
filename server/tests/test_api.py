import json

from conftest import GLB, IDENTITY, JPEG, PNG, snapshot_meta


def upload_snapshot(client, room_id, meta=None, depth=True):
    files = {"image": ("frame.jpg", JPEG, "image/jpeg")}
    if depth:
        files["depth"] = ("depth.png", PNG, "image/png")
    return client.post(
        f"/api/rooms/{room_id}/snapshots",
        data={"meta": json.dumps(meta or snapshot_meta())},
        files=files,
    )


def test_health(client):
    r = client.get("/api/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"


# ---------------------------------------------------------------- rooms


def test_room_crud(client):
    r = client.post("/api/rooms", json={"name": "Bedroom"})
    assert r.status_code == 201
    room = r.json()
    assert room["model_url"] is None

    assert [x["name"] for x in client.get("/api/rooms").json()] == ["Bedroom"]

    r = client.patch(f"/api/rooms/{room['id']}", json={"name": "Main bedroom"})
    assert r.json()["name"] == "Main bedroom"

    assert client.delete(f"/api/rooms/{room['id']}").status_code == 204
    assert client.get(f"/api/rooms/{room['id']}").status_code == 404


def test_room_name_required(client):
    assert client.post("/api/rooms", json={"name": ""}).status_code == 422


def test_room_model_upload_and_replace(client, room, settings):
    r = client.put(f"/api/rooms/{room['id']}/model", files={"file": ("room.glb", GLB)})
    assert r.status_code == 200
    url = r.json()["model_url"]
    assert url.endswith(".glb")
    assert client.get(url).content == GLB

    r = client.put(f"/api/rooms/{room['id']}/model", files={"file": ("room.glb", GLB)})
    assert r.json()["model_url"] != url
    assert client.get(url).status_code == 404  # old file removed

    r = client.put(f"/api/rooms/{room['id']}/model", files={"file": ("x.glb", JPEG)})
    assert r.status_code == 415


# ---------------------------------------------------------------- snapshots


def test_snapshot_upload_roundtrip(client, room):
    r = upload_snapshot(client, room["id"], snapshot_meta(depth_meta={"format": "uint16_mm"}))
    assert r.status_code == 201, r.text
    snap = r.json()
    assert snap["pose"] == IDENTITY
    assert snap["intrinsics"]["fx"] == 1000
    assert snap["planes"][0]["label"] == "floor"
    assert snap["capture_source"] == "arcore"
    assert snap["depth_meta"] == {"format": "uint16_mm"}
    assert client.get(snap["image_url"]).content == JPEG
    assert client.get(snap["depth_url"]).content == PNG

    listed = client.get(f"/api/rooms/{room['id']}/snapshots").json()
    assert [s["id"] for s in listed] == [snap["id"]]


def test_snapshot_raw_depth_and_no_depth(client, room):
    r = client.post(
        f"/api/rooms/{room['id']}/snapshots",
        data={"meta": json.dumps(snapshot_meta())},
        files={"image": ("f.jpg", JPEG), "depth": ("d.raw", b"\x01\x02" * 100)},
    )
    assert r.json()["depth_url"].endswith(".bin")
    assert upload_snapshot(client, room["id"], depth=False).json()["depth_url"] is None


def test_snapshot_rejects_bad_meta(client, room, settings):
    r = upload_snapshot(client, room["id"], snapshot_meta(pose=[1, 2, 3]))
    assert r.status_code == 422
    assert r.json()["detail"][0]["loc"][:3] == ["body", "meta", "pose"]

    r = client.post(
        f"/api/rooms/{room['id']}/snapshots",
        data={"meta": "not json"},
        files={"image": ("f.jpg", JPEG)},
    )
    assert r.status_code == 422
    # nothing should have been written to disk
    assert list(settings.media_dir.glob("snapshots/*")) == []


def test_snapshot_rejects_non_image(client, room):
    r = client.post(
        f"/api/rooms/{room['id']}/snapshots",
        data={"meta": json.dumps(snapshot_meta())},
        files={"image": ("f.txt", b"hello world, not an image")},
    )
    assert r.status_code == 415


def test_upload_size_limit(client, room):
    big = JPEG + b"\x00" * (1024 * 1024 + 1)  # settings fixture caps uploads at 1 MB
    r = client.post(
        f"/api/rooms/{room['id']}/snapshots",
        data={"meta": json.dumps(snapshot_meta())},
        files={"image": ("big.jpg", big)},
    )
    assert r.status_code == 413


def test_delete_room_cascades_and_removes_files(client, room, sofa, settings):
    snap = upload_snapshot(client, room["id"]).json()
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A"}).json()
    client.post(f"/api/layouts/{layout['id']}/placements", json={"item_id": sofa["id"]})

    assert client.delete(f"/api/rooms/{room['id']}").status_code == 204
    assert client.get(f"/api/snapshots/{snap['id']}").status_code == 404
    assert client.get(f"/api/layouts/{layout['id']}").status_code == 404
    assert client.get(snap["image_url"]).status_code == 404
    # the item itself survives, and is now deletable
    assert client.delete(f"/api/items/{sofa['id']}").status_code == 204


def test_delete_snapshot_keeps_placements(client, room, sofa):
    snap = upload_snapshot(client, room["id"]).json()
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A"}).json()
    p = client.post(
        f"/api/layouts/{layout['id']}/placements",
        json={"item_id": sofa["id"], "snapshot_id": snap["id"]},
    ).json()
    assert p["snapshot_id"] == snap["id"]

    assert client.delete(f"/api/snapshots/{snap['id']}").status_code == 204
    placements = client.get(f"/api/layouts/{layout['id']}").json()["placements"]
    assert placements[0]["id"] == p["id"]
    assert placements[0]["snapshot_id"] is None


# ---------------------------------------------------------------- items


def test_item_crud_and_filter(client, sofa):
    r = client.post(
        "/api/items",
        json={
            "name": "Print",
            "kind": "wall_decor",
            "width_m": 0.5,
            "depth_m": 0,
            "height_m": 0.7,
            "source_url": "https://example.com/print",
            "price": 39.0,
        },
    )
    assert r.status_code == 201
    decor = r.json()

    assert len(client.get("/api/items").json()) == 2
    assert [i["name"] for i in client.get("/api/items?kind=wall_decor").json()] == ["Print"]

    r = client.patch(f"/api/items/{decor['id']}", json={"price": None, "width_m": 0.6})
    assert r.json()["price"] is None and r.json()["width_m"] == 0.6
    assert client.patch(f"/api/items/{decor['id']}", json={"width_m": None}).status_code == 422
    assert client.patch(f"/api/items/{decor['id']}", json={"width_m": -1}).status_code == 422


def test_item_assets(client, sofa):
    r = client.put(f"/api/items/{sofa['id']}/image", files={"file": ("p.png", PNG)})
    assert r.json()["image_url"].endswith(".png")
    r = client.put(f"/api/items/{sofa['id']}/model", files={"file": ("m.glb", GLB)})
    assert r.json()["model_url"].endswith(".glb")
    assert (
        client.put(f"/api/items/{sofa['id']}/model", files={"file": ("m", PNG)}).status_code == 415
    )

    image_url = r.json()["image_url"]
    r = client.delete(f"/api/items/{sofa['id']}/image")
    assert r.json()["image_url"] is None
    assert client.get(image_url).status_code == 404


def test_item_in_use_cannot_be_deleted(client, room, sofa):
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A"}).json()
    client.post(f"/api/layouts/{layout['id']}/placements", json={"item_id": sofa["id"]})
    assert client.delete(f"/api/items/{sofa['id']}").status_code == 409


# ---------------------------------------------------------------- layouts & placements


def test_layout_and_placements(client, room, sofa):
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "Option A"}).json()
    assert client.get(f"/api/rooms/{room['id']}/layouts").json()[0]["name"] == "Option A"

    moved = list(IDENTITY)
    moved[12:15] = [1.0, 0.0, -2.0]  # translation lives in the last column
    p = client.post(
        f"/api/layouts/{layout['id']}/placements",
        json={"item_id": sofa["id"], "transform": moved},
    )
    assert p.status_code == 201
    pid = p.json()["id"]

    rotated = list(moved)
    rotated[0], rotated[2], rotated[8], rotated[10] = 0, -1, 1, 0  # 90° about Y
    r = client.patch(f"/api/placements/{pid}", json={"transform": rotated})
    assert r.json()["transform"] == rotated

    detail = client.get(f"/api/layouts/{layout['id']}").json()
    assert detail["placements"][0]["transform"] == rotated
    assert detail["updated_at"] >= layout["updated_at"]

    assert client.delete(f"/api/placements/{pid}").status_code == 204
    assert client.get(f"/api/layouts/{layout['id']}").json()["placements"] == []


def test_placement_validation(client, room, sofa):
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A"}).json()
    url = f"/api/layouts/{layout['id']}/placements"
    assert client.post(url, json={"item_id": 999}).status_code == 422
    assert client.post(url, json={"item_id": sofa["id"], "transform": [1, 2]}).status_code == 422

    other = client.post("/api/rooms", json={"name": "Kitchen"}).json()
    other_snap = upload_snapshot(client, other["id"]).json()
    r = client.post(url, json={"item_id": sofa["id"], "snapshot_id": other_snap["id"]})
    assert r.status_code == 422

    p = client.post(url, json={"item_id": sofa["id"]}).json()
    assert client.patch(f"/api/placements/{p['id']}", json={"transform": None}).status_code == 422


def test_replace_placements(client, room, sofa):
    layout = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A"}).json()
    url = f"/api/layouts/{layout['id']}/placements"
    client.post(url, json={"item_id": sofa["id"]})

    r = client.put(url, json=[{"item_id": sofa["id"]}, {"item_id": sofa["id"]}])
    assert r.status_code == 200
    assert len(r.json()["placements"]) == 2

    # an invalid entry rejects the whole save and leaves the layout untouched
    r = client.put(url, json=[{"item_id": 999}])
    assert r.status_code == 422
    assert len(client.get(f"/api/layouts/{layout['id']}").json()["placements"]) == 2


def test_duplicate_layout(client, room, sofa):
    a = client.post(f"/api/rooms/{room['id']}/layouts", json={"name": "A", "notes": "n"}).json()
    client.post(f"/api/layouts/{a['id']}/placements", json={"item_id": sofa["id"]})

    r = client.post(f"/api/layouts/{a['id']}/duplicate", json={"name": "B"})
    assert r.status_code == 201
    b = r.json()
    assert b["name"] == "B" and b["notes"] == "n" and b["room_id"] == room["id"]
    assert len(b["placements"]) == 1

    # editing B leaves A alone
    client.delete(f"/api/placements/{b['placements'][0]['id']}")
    assert len(client.get(f"/api/layouts/{a['id']}").json()["placements"]) == 1

    assert client.delete(f"/api/layouts/{a['id']}").status_code == 204
    assert client.get(f"/api/layouts/{a['id']}").status_code == 404
