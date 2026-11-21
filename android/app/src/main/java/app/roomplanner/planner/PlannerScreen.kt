package app.roomplanner.planner

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import app.roomplanner.api.Layout
import app.roomplanner.api.PlacementCreate
import app.roomplanner.api.Room
import app.roomplanner.api.RoomPlannerApi
import app.roomplanner.api.Snapshot
import app.roomplanner.geometry.ImageFit
import app.roomplanner.geometry.ItemKind
import app.roomplanner.geometry.Mat4
import app.roomplanner.geometry.PlaceResult
import app.roomplanner.geometry.PlacementRules
import app.roomplanner.geometry.PlaneKind
import app.roomplanner.geometry.Point2
import app.roomplanner.geometry.Vec3
import app.roomplanner.ui.ItemForm
import kotlin.math.PI
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val FURNITURE_COLOR = Color(0xFF4FC3F7)
private val DECOR_COLOR = Color(0xFFFFB74D)
private val SELECTED_COLOR = Color(0xFFFFEB3B)
private const val ROTATE_STEP = (PI / 12).toFloat() // 15°

/**
 * Phase 1 planner: shows a saved AR snapshot with a virtual camera matching the real one, and
 * lets you place true-to-scale furniture boxes on the floor and decor on the walls. Each layout
 * is one option to compare; changes save automatically.
 */
@Composable
fun PlannerScreen(
    api: RoomPlannerApi,
    room: Room,
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val snapshots = remember { mutableStateListOf<Snapshot>() }
    var snapshotIndex by remember { mutableIntStateOf(0) }
    val catalog = remember { mutableStateMapOf<Long, Item>() }
    val layouts = remember { mutableStateListOf<Layout>() }
    var layoutId by remember { mutableStateOf<Long?>(null) }
    val placements = remember { mutableStateListOf<EditPlacement>() }
    var nextKey by remember { mutableLongStateOf(1L) }
    var selectedKey by remember { mutableStateOf<Long?>(null) }
    var placing by remember { mutableStateOf<Item?>(null) }
    var message by remember { mutableStateOf<String?>("Loading…") }
    var saveState by remember { mutableStateOf("") }

    val photo = remember { mutableStateMapOf<Long, Bitmap>() }
    val decorImages = remember { mutableStateMapOf<Long, Bitmap>() }

    // Saving: every edit bumps `version`; one saver at a time catches up to it.
    var version by remember { mutableIntStateOf(0) }
    val saveLock = remember { Mutex() }
    var savedVersion by remember { mutableIntStateOf(0) }

    suspend fun flush() {
        saveLock.withLock {
            val id = layoutId ?: return
            while (savedVersion < version) {
                val target = version
                saveState = "Saving…"
                try {
                    api.savePlacements(
                        id,
                        placements.map { PlacementCreate(it.itemId, it.transform.toList(), it.snapshotId) },
                    )
                    savedVersion = target
                    saveState = "Saved"
                } catch (e: Exception) {
                    saveState = "Not saved: ${e.message}"
                    return
                }
            }
        }
    }

    fun changed() {
        version++
        scope.launch { flush() }
    }

    suspend fun loadLayout(id: Long) {
        val detail = api.getLayout(id)
        layoutId = id
        placements.clear()
        detail.placements.forEach {
            placements += EditPlacement(nextKey++, it.itemId, Mat4.of(it.transform), it.snapshotId)
        }
        selectedKey = null
        savedVersion = version
        saveState = ""
    }

    suspend fun refreshLayouts() {
        layouts.clear()
        layouts += api.listLayouts(room.id)
    }

    LaunchedEffect(room.id) {
        try {
            snapshots.clear()
            snapshots += api.listSnapshots(room.id).filter(SnapshotScene::canPlan)
            snapshotIndex = (snapshots.size - 1).coerceAtLeast(0)
            catalog.clear()
            api.listItems().forEach { catalog[it.id] = it }
            refreshLayouts()
            if (layouts.isEmpty()) layouts += api.createLayout(room.id, "Option A")
            loadLayout(layouts.first().id)
            message = if (snapshots.isEmpty()) "No snapshots of this room yet. Capture some first." else null
        } catch (e: Exception) {
            message = "Couldn't load: ${e.message}"
        }
    }

    val snapshot = snapshots.getOrNull(snapshotIndex)
    val scene = remember(snapshot?.id) { snapshot?.let { runCatching { SnapshotScene(it) }.getOrNull() } }
    val sessionOf = remember(snapshots.toList()) { snapshots.associate { it.id to it.sessionId } }

    LaunchedEffect(snapshot?.id) {
        val s = snapshot ?: return@LaunchedEffect
        if (s.id !in photo) {
            try {
                photo.clear() // keep one full-size photo in memory
                photo[s.id] = api.fetchBitmap(s.imageUrl)
            } catch (e: Exception) {
                message = "Couldn't load the photo: ${e.message}"
            }
        }
    }

    val visible = placements.filter { p -> scene?.isVisible(p, sessionOf) == true && p.itemId in catalog }
    val hiddenCount = placements.size - visible.size

    // Fetch images for any wall decor on screen.
    val decorIds = visible.mapNotNull { catalog[it.itemId] }
        .filter { it.kind == ItemKinds.WALL_DECOR && it.imageUrl != null }
        .map { it.id }.toSet()
    LaunchedEffect(decorIds) {
        for (id in decorIds - decorImages.keys) {
            val url = catalog[id]?.imageUrl ?: continue
            runCatching { api.fetchBitmap(url, 1024) }.onSuccess { decorImages[id] = it }
        }
    }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = if (scene != null && viewSize.width > 0) {
        scene.fit(viewSize.width.toFloat(), viewSize.height.toFloat())
    } else {
        null
    }
    val screenItems = if (scene != null && fit != null) {
        visible.mapNotNull { p -> scene.project(p, catalog.getValue(p.itemId), fit) }
    } else {
        emptyList()
    }

    // ---------------------------------------------------------------- gestures

    fun onTap(at: Offset) {
        val sc = scene ?: return
        val f = fit ?: return
        val toPlace = placing
        if (toPlace != null) {
            val ray = sc.rayAt(f, at.x, at.y)
            when (val r = PlacementRules.place(toPlace.geomKind(), ray, sc.planes, sc.camera.position)) {
                is PlaceResult.Failed -> message = r.reason
                is PlaceResult.Placed -> {
                    val p = EditPlacement(nextKey++, toPlace.id, r.hit.transform, sc.snapshot.id)
                    placements += p
                    selectedKey = p.key
                    placing = null
                    message = null
                    changed()
                }
            }
            return
        }
        selectedKey = sc.hitTest(screenItems, at.x, at.y)?.placement?.key
    }

    // Drag state lives outside composition; it changes every pointer event.
    val drag = remember { DragState() }

    fun onDragStart(at: Offset) {
        val sc = scene ?: return
        val f = fit ?: return
        val hit = sc.hitTest(screenItems, at.x, at.y) ?: return drag.reset()
        selectedKey = hit.placement.key
        val kind = hit.item.geomKind()
        val ray = sc.rayAt(f, at.x, at.y)
        val (point, normal) = PlacementRules.dragPlane(kind, hit.placement.transform)
        val t = ray.intersectPlane(point, normal) ?: return drag.reset()
        drag.key = hit.placement.key
        drag.kind = kind
        drag.grabOffset = ray.at(t) - hit.placement.transform.origin
        drag.position = at
    }

    fun onDrag(delta: Offset) {
        val key = drag.key ?: return
        val sc = scene ?: return
        val f = fit ?: return
        drag.position += delta
        val i = placements.indexOfFirst { it.key == key }
        if (i < 0) return
        val ray = sc.rayAt(f, drag.position.x, drag.position.y)
        val moved = PlacementRules.drag(drag.kind, placements[i].transform, drag.grabOffset, ray) ?: return
        // Ignore drags toward the horizon that would fling the item far away.
        if ((moved.origin - sc.camera.position).length() > 20f) return
        placements[i] = placements[i].copy(transform = moved)
    }

    fun onDragEnd() {
        if (drag.key != null) changed()
        drag.reset()
    }

    val tapHandler by rememberUpdatedState(::onTap)
    val dragStartHandler by rememberUpdatedState(::onDragStart)
    val dragHandler by rememberUpdatedState(::onDrag)
    val dragEndHandler by rememberUpdatedState(::onDragEnd)

    fun leave(then: () -> Unit) {
        scope.launch {
            flush()
            then()
        }
    }
    BackHandler { leave(onBack) }

    // ---------------------------------------------------------------- layout dialogs

    var layoutMenu by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var itemPicker by remember { mutableStateOf(false) }

    fun switchLayout(id: Long) = scope.launch {
        flush()
        try {
            loadLayout(id)
        } catch (e: Exception) {
            message = "Couldn't open layout: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- UI

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { leave(onBack) }) { Text("Back") }
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { layoutMenu = true }) {
                    Text("${layouts.firstOrNull { it.id == layoutId }?.name ?: "Layout"} ▾")
                }
                DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                    layouts.forEach { l ->
                        DropdownMenuItem(
                            text = { Text(if (l.id == layoutId) "✓ ${l.name}" else l.name) },
                            onClick = {
                                layoutMenu = false
                                if (l.id != layoutId) switchLayout(l.id)
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("New empty layout…") }, onClick = {
                        layoutMenu = false
                        nameDialog = NameDialog.New(nextOptionName(layouts))
                    })
                    DropdownMenuItem(text = { Text("Duplicate this layout…") }, onClick = {
                        layoutMenu = false
                        nameDialog = NameDialog.Duplicate(nextOptionName(layouts))
                    })
                    DropdownMenuItem(text = { Text("Delete this layout…") }, onClick = {
                        layoutMenu = false
                        confirmDelete = true
                    })
                }
            }
            Text(saveState, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) { detectTapGestures(onTap = { tapHandler(it) }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragStartHandler(it) },
                        onDrag = { change, amount ->
                            if (drag.key != null) change.consume()
                            dragHandler(amount)
                        },
                        onDragEnd = { dragEndHandler() },
                        onDragCancel = { dragEndHandler() },
                    )
                },
        ) {
            val bmp = snapshot?.let { photo[it.id] }
            if (scene != null && fit != null && bmp != null) {
                PlanCanvas(
                    scene = scene,
                    fit = fit,
                    photo = bmp,
                    items = screenItems,
                    selectedKey = selectedKey,
                    placingKind = placing?.geomKind(),
                    decorImages = decorImages,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .padding(8.dp),
            ) {
                if (snapshots.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { snapshotIndex-- }, enabled = snapshotIndex > 0) { Text("◀") }
                        Text("Photo ${snapshotIndex + 1} of ${snapshots.size}", color = Color.White)
                        TextButton(
                            onClick = { snapshotIndex++ },
                            enabled = snapshotIndex < snapshots.size - 1,
                        ) { Text("▶") }
                    }
                }
                val hint = message
                    ?: placing?.let {
                        if (it.kind == ItemKinds.WALL_DECOR) "Tap a wall to hang “${it.name}”."
                        else "Tap the floor where “${it.name}” should stand."
                    }
                    ?: if (hiddenCount > 0) {
                        "$hiddenCount item(s) in this layout were placed on photos from another capture session, " +
                            "so they can't be lined up here."
                    } else {
                        null
                    }
                hint?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
            }
        }

        val selected = placements.firstOrNull { it.key == selectedKey }
        val selectedItem = selected?.let { catalog[it.itemId] }
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected != null && selectedItem != null) {
                Text(
                    "${selectedItem.name} · ${ItemForm.dimensions(selectedItem)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectedItem.kind != ItemKinds.WALL_DECOR) {
                        OutlinedButton(onClick = { rotate(placements, selected.key, ROTATE_STEP); changed() }) {
                            Text("⟲ 15°")
                        }
                        OutlinedButton(onClick = { rotate(placements, selected.key, -ROTATE_STEP); changed() }) {
                            Text("⟳ 15°")
                        }
                    }
                    OutlinedButton(onClick = {
                        placements.removeAll { it.key == selected.key }
                        selectedKey = null
                        changed()
                    }) { Text("Remove") }
                    TextButton(onClick = { selectedKey = null }) { Text("Done") }
                }
                Text("Drag it to move.", style = MaterialTheme.typography.bodySmall)
            } else if (placing != null) {
                OutlinedButton(onClick = { placing = null; message = null }) { Text("Cancel") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { itemPicker = true },
                        enabled = scene != null && layoutId != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Add item") }
                    OutlinedButton(onClick = { leave(onOpenCatalog) }) { Text("Catalog") }
                }
                if (saveState.startsWith("Not saved")) {
                    TextButton(onClick = { scope.launch { flush() } }) { Text("Retry saving") }
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs

    if (itemPicker) {
        AlertDialog(
            onDismissRequest = { itemPicker = false },
            title = { Text("Add item") },
            text = {
                if (catalog.isEmpty()) {
                    Text("The catalog is empty. Add items with their dimensions first.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(catalog.values.sortedBy { it.name }, key = { it.id }) { item ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    itemPicker = false
                                    selectedKey = null
                                    message = null
                                    placing = item
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(item.name)
                                Text(
                                    (if (item.kind == ItemKinds.WALL_DECOR) "Wall decor · " else "") +
                                        ItemForm.dimensions(item),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { itemPicker = false; leave(onOpenCatalog) }) { Text("Open catalog") }
            },
            dismissButton = { TextButton(onClick = { itemPicker = false }) { Text("Cancel") } },
        )
    }

    nameDialog?.let { dialog ->
        var name by remember(dialog) { mutableStateOf(dialog.initial) }
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (dialog is NameDialog.New) "New layout" else "Duplicate layout") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    nameDialog = null
                    scope.launch {
                        try {
                            flush()
                            val newId = when (dialog) {
                                is NameDialog.New -> api.createLayout(room.id, name.trim()).id
                                is NameDialog.Duplicate -> api.duplicateLayout(layoutId!!, name.trim()).id
                            }
                            refreshLayouts()
                            loadLayout(newId)
                        } catch (e: Exception) {
                            message = "Couldn't create layout: ${e.message}"
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        val current = layouts.firstOrNull { it.id == layoutId }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${current?.name}”?") },
            text = { Text("Its placements are deleted too. The items stay in the catalog.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = layoutId ?: return@TextButton
                    scope.launch {
                        try {
                            saveLock.withLock { api.deleteLayout(id) }
                            savedVersion = version
                            refreshLayouts()
                            if (layouts.isEmpty()) layouts += api.createLayout(room.id, "Option A")
                            loadLayout(layouts.first().id)
                        } catch (e: Exception) {
                            message = "Couldn't delete: ${e.message}"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private sealed interface NameDialog {
    val initial: String

    data class New(override val initial: String) : NameDialog
    data class Duplicate(override val initial: String) : NameDialog
}

private class DragState {
    var key: Long? = null
    var kind: ItemKind = ItemKind.Furniture
    var grabOffset: Vec3 = Vec3.ZERO
    var position: Offset = Offset.Zero

    fun reset() {
        key = null
    }
}

/** "Option B" after "Option A", and so on. */
internal fun nextOptionName(layouts: List<Layout>): String {
    val used = layouts.map { it.name }.toSet()
    return ('A'..'Z').map { "Option $it" }.firstOrNull { it !in used } ?: "Option ${layouts.size + 1}"
}

private fun rotate(placements: MutableList<EditPlacement>, key: Long, radians: Float) {
    val i = placements.indexOfFirst { it.key == key }
    if (i >= 0) placements[i] = placements[i].copy(transform = PlacementRules.rotateYaw(placements[i].transform, radians))
}

// ---------------------------------------------------------------- drawing

/**
 * The snapshot photo with items drawn in perspective. [fit] must match this composable's size in
 * pixels. Separate from [PlannerScreen] so it can be rendered in tests.
 */
@Composable
internal fun PlanCanvas(
    scene: SnapshotScene,
    fit: ImageFit,
    photo: Bitmap,
    items: List<ScreenItem>,
    selectedKey: Long?,
    placingKind: ItemKind?,
    decorImages: Map<Long, Bitmap>,
    modifier: Modifier = Modifier,
) {
    val labelPx = with(LocalDensity.current) { 12.sp.toPx() }
    Canvas(modifier) {
        drawPhoto(photo, scene, fit)
        placingKind?.let { drawPlaneHints(scene, fit, it) }
        items.sortedByDescending { it.depth }.forEach { si ->
            drawItem(si, selected = si.placement.key == selectedKey, decorImages[si.item.id], labelPx)
        }
    }
}

private fun DrawScope.drawPhoto(bmp: Bitmap, scene: SnapshotScene, fit: ImageFit) {
    val m = android.graphics.Matrix().apply {
        setValues(fit.affineValues())
        // The bitmap may be downsampled; intrinsics are in full-resolution pixels.
        preScale(scene.camera.width.toFloat() / bmp.width, scene.camera.height.toFloat() / bmp.height)
    }
    drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
}

/** Outlines the surfaces the item can go on, so it's clear where to tap. */
private fun DrawScope.drawPlaneHints(scene: SnapshotScene, fit: ImageFit, kind: ItemKind) {
    val wanted = if (kind == ItemKind.WallDecor) PlaneKind.Vertical else PlaneKind.HorizontalUp
    for (plane in scene.planes.filter { it.kind == wanted }) {
        val pts = scene.outline(plane).map { w ->
            val p = scene.camera.project(w) ?: return@map null
            fit.map(p.u, p.v)
        }
        if (pts.any { it == null } || pts.size < 3) continue
        val path = pathOf(pts.filterNotNull())
        drawPath(path, Color.White.copy(alpha = 0.18f))
        drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(width = 2f))
    }
}

private fun DrawScope.drawItem(si: ScreenItem, selected: Boolean, decorImage: Bitmap?, labelPx: Float) {
    val base = if (si.item.kind == ItemKinds.WALL_DECOR) DECOR_COLOR else FURNITURE_COLOR
    val edge = if (selected) SELECTED_COLOR else base
    val strokeWidth = if (selected) 5f else 3f

    if (si.item.kind == ItemKinds.WALL_DECOR) {
        val c = si.corners // TL, TR, BR, BL
        if (decorImage != null) {
            val m = android.graphics.Matrix()
            val w = decorImage.width.toFloat()
            val h = decorImage.height.toFloat()
            val ok = m.setPolyToPoly(
                floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0,
                floatArrayOf(c[0].x, c[0].y, c[1].x, c[1].y, c[2].x, c[2].y, c[3].x, c[3].y), 0,
                4,
            )
            if (ok) {
                drawIntoCanvas { it.nativeCanvas.drawBitmap(decorImage, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
        } else {
            drawPath(pathOf(c), base.copy(alpha = 0.45f))
        }
        drawPath(pathOf(c), edge, style = Stroke(width = strokeWidth))
    } else {
        // Painter's algorithm: farthest faces first.
        PlacementRules.BOX_FACES
            .sortedByDescending { f -> f.sumOf { si.depths[it].toDouble() } }
            .forEach { f ->
                val path = pathOf(f.map { si.corners[it] })
                drawPath(path, base.copy(alpha = 0.22f))
                drawPath(path, edge, style = Stroke(width = strokeWidth))
            }
    }

    // Label above the item.
    val top = si.corners.minBy { it.y }
    val cx = si.corners.map { it.x }.average().toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.toArgb()
        textSize = labelPx
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawIntoCanvas {
        it.nativeCanvas.drawText(si.item.name, cx, top.y - labelPx * 1.4f, paint)
        it.nativeCanvas.drawText(ItemForm.dimensions(si.item), cx, top.y - labelPx * 0.3f, paint)
    }
}

private fun pathOf(points: List<Point2>) = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}
