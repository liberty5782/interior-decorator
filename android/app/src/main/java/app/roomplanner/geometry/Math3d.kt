package app.roomplanner.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal 3D math for placement. Pure Kotlin, no Android or ARCore types, so the same code runs
 * against a saved snapshot (Phase 1) and a live AR frame (Phase 2), and is unit-testable.
 *
 * Conventions match docs/api.md: metres, +Y up, matrices are 16 floats in column-major order.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) this else this * (1f / l)
    }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** 4x4 matrix, column-major: element (row, col) is `m[col * 4 + row]`. */
class Mat4(val m: FloatArray) {
    init {
        require(m.size == 16) { "Mat4 needs 16 values, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int): Float = m[col * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += this[row, k] * o[k, c]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transformPoint(v: Vec3) = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z + this[0, 3],
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z + this[1, 3],
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z + this[2, 3],
    )

    fun transformDir(v: Vec3) = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z,
    )

    val origin get() = Vec3(m[12], m[13], m[14])
    val axisX get() = Vec3(m[0], m[1], m[2])
    val axisY get() = Vec3(m[4], m[5], m[6])
    val axisZ get() = Vec3(m[8], m[9], m[10])

    /** Inverse of a rigid transform (rotation + translation), which every pose here is. */
    fun inverseRigid(): Mat4 {
        val r = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) r[col * 4 + row] = this[col, row]
        val t = origin
        r[12] = -(r[0] * t.x + r[4] * t.y + r[8] * t.z)
        r[13] = -(r[1] * t.x + r[5] * t.y + r[9] * t.z)
        r[14] = -(r[2] * t.x + r[6] * t.y + r[10] * t.z)
        r[15] = 1f
        return Mat4(r)
    }

    fun withOrigin(p: Vec3): Mat4 = Mat4(m.copyOf().also { it[12] = p.x; it[13] = p.y; it[14] = p.z })

    fun toList(): List<Float> = m.toList()

    fun approxEquals(o: Mat4, eps: Float = 1e-4f) = m.indices.all { abs(m[it] - o.m[it]) <= eps }

    override fun equals(other: Any?) = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()
    override fun toString() = "Mat4(${m.joinToString()})"

    companion object {
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        fun of(values: List<Float>) = Mat4(values.toFloatArray())

        fun translation(t: Vec3) = IDENTITY.withOrigin(t)

        /** Rotation about +Y by [radians]; maps +Z to (sin, 0, cos). */
        fun rotationY(radians: Float): Mat4 {
            val c = cos(radians)
            val s = sin(radians)
            return Mat4(floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f))
        }

        /** Transform whose local axes are [x], [y], [z] (expected orthonormal) at [origin]. */
        fun fromBasis(x: Vec3, y: Vec3, z: Vec3, origin: Vec3) = Mat4(
            floatArrayOf(
                x.x, x.y, x.z, 0f,
                y.x, y.y, y.z, 0f,
                z.x, z.y, z.z, 0f,
                origin.x, origin.y, origin.z, 1f,
            )
        )
    }
}

data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float) = origin + dir * t

    /** Distance along the ray to the plane through [point] with [normal], or null if none ahead. */
    fun intersectPlane(point: Vec3, normal: Vec3): Float? {
        val denom = dir dot normal
        if (abs(denom) < 1e-6f) return null
        val t = ((point - origin) dot normal) / denom
        return if (t > 1e-4f) t else null
    }
}

data class Point2(val x: Float, val y: Float)

/** Even-odd point-in-polygon test. */
fun pointInPolygon(p: Point2, poly: List<Point2>): Boolean {
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/** Convex hull (Andrew's monotone chain), counter-clockwise in a y-up frame. */
fun convexHull(points: List<Point2>): List<Point2> {
    val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
    if (pts.size < 3) return pts
    fun cross(o: Point2, a: Point2, b: Point2) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = mutableListOf<Point2>()
    for (p in pts) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
        lower += p
    }
    val upper = mutableListOf<Point2>()
    for (p in pts.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
        upper += p
    }
    return lower.dropLast(1) + upper.dropLast(1)
}
