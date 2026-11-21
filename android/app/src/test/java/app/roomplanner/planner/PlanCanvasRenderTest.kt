package app.roomplanner.planner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.roomplanner.api.Intrinsics
import app.roomplanner.api.Item
import app.roomplanner.api.ItemKinds
import app.roomplanner.api.PlaneData
import app.roomplanner.api.Snapshot
import app.roomplanner.geometry.ItemKind
import app.roomplanner.geometry.Mat4
import app.roomplanner.geometry.PlaceResult
import app.roomplanner.geometry.PlacementRules
import app.roomplanner.geometry.SnapshotCamera
import app.roomplanner.geometry.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the planner's canvas with a synthetic room photo and writes PNGs to
 * app/build/screenshots/ for eyeballing. It also checks pixels: the sofa's outline colour must
 * appear where the geometry says the sofa is, in both a landscape and a portrait (sideways)
 * capture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// mdpi: 1 dp = 1 px, and a screen big enough for the largest render.
@Config(sdk = [35], qualifiers = "w900dp-h900dp-mdpi")
class PlanCanvasRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val sofa = Item(1, "Sofa", ItemKinds.FURNITURE, 2.1f, 0.9f, 0.8f)
    private val table = Item(2, "Side table", ItemKinds.FURNITURE, 0.5f, 0.5f, 0.55f)
    private val print = Item(3, "Print", ItemKinds.WALL_DECOR, 0.5f, 0f, 0.7f)
    private val catalog = listOf(sofa, table, print).associateBy { it.id }

    private val wallZ = -3.5f

    private fun rotX(a: Float) = Mat4(
        floatArrayOf(1f, 0f, 0f, 0f, 0f, cos(a), sin(a), 0f, 0f, -sin(a), cos(a), 0f, 0f, 0f, 0f, 1f)
    )

    private fun rotZ(a: Float) = Mat4(
        floatArrayOf(cos(a), sin(a), 0f, 0f, -sin(a), cos(a), 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    )

    /** Standing 1.5 m up, looking 20° down; [roll] simulates holding the phone in portrait. */
    private fun snapshot(roll: Float): Snapshot {
        val pose = Mat4.translation(Vec3(0f, 1.5f, 0f)) * rotX(-0.35f) * rotZ(roll)
        val floorPoly = listOf(listOf(-2f, -1.75f), listOf(2f, -1.75f), listOf(2f, 1.75f), listOf(-2f, 1.75f))
        val wallPose = Mat4.fromBasis(Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, -1f, 0f), Vec3(0f, 1.3f, wallZ))
        return Snapshot(
            id = 1,
            roomId = 1,
            imageUrl = "/x.jpg",
            pose = pose.toList(),
            intrinsics = Intrinsics(520f, 520f, 320f, 240f, 640, 480),
            planes = listOf(
                PlaneData(
                    type = "horizontal_up",
                    centerPose = Mat4.translation(Vec3(0f, 0f, -1.75f)).toList(),
                    extentX = 4f, extentZ = 3.5f, polygon = floorPoly,
                ),
                PlaneData(type = "vertical", centerPose = wallPose.toList(), extentX = 4f, extentZ = 2.6f),
            ),
            sessionId = "s",
        )
    }

    /** Draws floor, back wall and a floor grid as the camera would see them. */
    private fun syntheticPhoto(cam: SnapshotCamera, scale: Int = 2): Bitmap {
        val bmp = Bitmap.createBitmap(cam.width / scale, cam.height / scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(1f / scale, 1f / scale)
        c.drawColor(android.graphics.Color.rgb(60, 60, 70))
        fun poly(vararg w: Vec3, color: Int) {
            val pts = w.map { cam.project(it)!! }
            val path = Path().apply {
                moveTo(pts[0].u, pts[0].v)
                pts.drop(1).forEach { lineTo(it.u, it.v) }
                close()
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        poly(Vec3(-2f, 0f, -0.6f), Vec3(2f, 0f, -0.6f), Vec3(2f, 0f, wallZ), Vec3(-2f, 0f, wallZ), color = android.graphics.Color.rgb(176, 141, 103))
        poly(Vec3(-2f, 0f, wallZ), Vec3(2f, 0f, wallZ), Vec3(2f, 2.6f, wallZ), Vec3(-2f, 2.6f, wallZ), color = android.graphics.Color.rgb(222, 218, 205))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(90, 0, 0, 0); strokeWidth = 2f }
        var x = -2f
        while (x <= 2f) {
            val a = cam.project(Vec3(x, 0f, -0.6f))!!
            val b = cam.project(Vec3(x, 0f, wallZ))!!
            c.drawLine(a.u, a.v, b.u, b.v, line)
            x += 0.5f
        }
        var z = -0.6f
        while (z >= wallZ) {
            val a = cam.project(Vec3(-2f, 0f, z))!!
            val b = cam.project(Vec3(2f, 0f, z))!!
            c.drawLine(a.u, a.v, b.u, b.v, line)
            z -= 0.5f
        }
        return bmp
    }

    private fun artwork(): Bitmap {
        val bmp = Bitmap.createBitmap(100, 140, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 100f, 140f, android.graphics.Color.rgb(30, 90, 160), android.graphics.Color.rgb(220, 80, 60), Shader.TileMode.CLAMP)
        })
        c.drawCircle(50f, 50f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(250, 220, 90) })
        return bmp
    }

    private fun render(name: String, roll: Float, viewW: Int, viewH: Int): Pair<Bitmap, List<ScreenItem>> {
        val scene = SnapshotScene(snapshot(roll))
        val fit = scene.fit(viewW.toFloat(), viewH.toFloat())
        val cam = scene.camera

        fun placeAt(item: Item, world: Vec3): EditPlacement {
            // Tap where the world point appears, exactly as a user would.
            val p = cam.project(world)!!
            val screen = fit.map(p.u, p.v)
            val r = PlacementRules.place(item.geomKind(), scene.rayAt(fit, screen.x, screen.y), scene.planes, cam.position)
            return EditPlacement(item.id, item.id, (r as PlaceResult.Placed).hit.transform, 1)
        }

        val placements = listOf(
            placeAt(sofa, Vec3(-0.3f, 0f, -2.6f)),
            placeAt(table, Vec3(1.2f, 0f, -2.3f)).let {
                it.copy(transform = PlacementRules.rotateYaw(it.transform, (PI / 6).toFloat()))
            },
            placeAt(print, Vec3(0.9f, 1.5f, wallZ)),
        )
        val items = placements.mapNotNull { scene.project(it, catalog.getValue(it.itemId), fit) }
        val photo = syntheticPhoto(cam)

        compose.setContent {
            PlanCanvas(
                scene = scene,
                fit = fit,
                photo = photo,
                items = items,
                selectedKey = sofa.id,
                placingKind = ItemKind.Furniture,
                decorImages = mapOf(print.id to artwork()),
                modifier = Modifier.size(viewW.dp, viewH.dp),
            )
        }
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals("render size", viewW to viewH, shot.width to shot.height)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shot to items
    }

    private fun assertSofaOutlineVisible(shot: Bitmap, items: List<ScreenItem>) {
        assertEquals(3, items.size)
        val sofaItem = items.first { it.item.id == sofa.id }
        // Selected items are outlined in yellow; look for it along the sofa's bottom-front edge.
        val a = sofaItem.corners[2]
        val b = sofaItem.corners[3]
        val hits = (0..20).count { i ->
            val x = (a.x + (b.x - a.x) * i / 20f).toInt().coerceIn(0, shot.width - 1)
            val y = (a.y + (b.y - a.y) * i / 20f).toInt().coerceIn(0, shot.height - 1)
            (-2..2).any { dy ->
                val px = shot.getPixel(x, (y + dy).coerceIn(0, shot.height - 1))
                android.graphics.Color.red(px) > 200 && android.graphics.Color.green(px) > 200 &&
                    android.graphics.Color.blue(px) < 120
            }
        }
        assertTrue("sofa outline drawn where projected ($hits/21 samples)", hits >= 15)
    }

    @Test
    fun landscapeCapture() {
        val (shot, items) = render("planner_landscape", roll = 0f, viewW = 800, viewH = 600)
        assertSofaOutlineVisible(shot, items)
    }

    @Test
    fun portraitCaptureIsShownUpright() {
        // Phone held in portrait: the sensor image is sideways, and the planner turns it upright.
        val (shot, items) = render("planner_portrait", roll = (PI / 2).toFloat(), viewW = 480, viewH = 800)
        assertSofaOutlineVisible(shot, items)
        // Upright means the floor (sofa) is below the wall decor on screen.
        val sofaY = items.first { it.item.id == sofa.id }.corners.map { it.y }.average()
        val printY = items.first { it.item.id == print.id }.corners.map { it.y }.average()
        assertTrue("sofa below print on screen", sofaY > printY)
    }
}
