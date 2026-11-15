package com.memorylink.photoframe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the 3D transitions: the old and the new screen (two screenshots) as
 * cards in space, seen in perspective, for a progress t from 0 to 1.
 *
 * Each card is a rectangle of the screen plane, turned about a pivot and
 * moved; its four corners are projected from a viewer in front of the
 * middle of the screen, and the screenshot is drawn onto that quadrilateral.
 * The poses were worked out (and checked frame by frame) in a Python model.
 */
class Transition3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** The 3D transitions, by the names used in the web UI. */
    enum class Kind { LEAPFROG, TURNAROUND, CUBE, DOOR, FALL_AWAY, CAROUSEL, CARD_STACK, BLINDS }

    private class Card(
        val image: Int,       // 0 = old screen, 1 = new screen
        val strip: Int,       // slat index for BLINDS, else -1
        val pts: FloatArray,  // projected corners: TL, TR, BR, BL
        val depth: Float,
        val shade: Float,     // 0 = as is, 1 = black
        val alpha: Float,
    )

    private var kind = Kind.CUBE
    private var direction = 1
    private var progress = 0f
    private var images: Array<Bitmap?> = arrayOf(null, null)
    private var strips: Array<Array<Bitmap>> = arrayOf(emptyArray(), emptyArray())
    private var slats = 0
    private var animator: ValueAnimator? = null
    private var onEnd: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val src = FloatArray(8)

    val running: Boolean
        get() = animator != null

    /**
     * Play [kind] from [old] to [new] (both screen-sized), then hide and call
     * [done]. [direction] picks one of the variants (left/right, up/down).
     */
    fun start(kind: Kind, old: Bitmap, new: Bitmap, durationMs: Long, direction: Int, done: () -> Unit) {
        cancel()
        this.kind = kind
        this.direction = direction
        images = arrayOf(old, new)
        if (kind == Kind.BLINDS) {
            slats = slatCount(old.width, old.height)
            strips = arrayOf(slice(old, slats), slice(new, slats))
        } else {
            slats = 0
            strips = arrayOf(emptyArray(), emptyArray())
        }
        onEnd = done
        progress = 0f
        visibility = VISIBLE
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs
        a.interpolator = LinearInterpolator() // every transition eases itself
        a.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false
            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!canceled) finish()
            }
        })
        animator = a
        a.start()
    }

    /** Stop at once; the screen underneath already shows the new photo. */
    fun cancel() {
        val a = animator ?: return
        animator = null
        a.cancel()
        finish()
    }

    private fun finish() {
        animator = null
        visibility = GONE
        images = arrayOf(null, null)
        strips = arrayOf(emptyArray(), emptyArray())
        val done = onEnd
        onEnd = null
        done?.invoke()
    }

    private fun slice(bmp: Bitmap, n: Int): Array<Bitmap> = Array(n) { i ->
        val x0 = (i * bmp.width / n.toFloat()).roundToInt()
        val x1 = ((i + 1) * bmp.width / n.toFloat()).roundToInt()
        Bitmap.createBitmap(bmp, x0, 0, max(1, x1 - x0), bmp.height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val old = images[0] ?: return
        val w = old.width.toFloat()
        val h = old.height.toFloat()
        for (card in cards(progress, w, h)) {
            val bmp = if (card.strip >= 0) strips[card.image].getOrNull(card.strip)
            else images[card.image]
            if (bmp == null || card.alpha <= 0f) continue
            src[0] = 0f; src[1] = 0f
            src[2] = bmp.width.toFloat(); src[3] = 0f
            src[4] = bmp.width.toFloat(); src[5] = bmp.height.toFloat()
            src[6] = 0f; src[7] = bmp.height.toFloat()
            if (!matrix.setPolyToPoly(src, 0, card.pts, 0, 4)) continue
            // Scale the screenshot to this view if it was taken at another size.
            val scaleX = width / w
            val scaleY = height / h
            if (scaleX != 1f || scaleY != 1f) matrix.postScale(scaleX, scaleY)
            val light = ((1f - card.shade) * 255f).roundToInt().coerceIn(0, 255)
            paint.colorFilter = if (light < 255) {
                LightingColorFilter(Color.rgb(light, light, light), 0)
            } else null
            paint.alpha = (card.alpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }

    // ---- the transitions -----------------------------------------------------

    private fun cards(t: Float, w: Float, h: Float): List<Card> {
        val d = 1.6f * max(w, h)
        val cx = w / 2f
        val cy = h / 2f
        val full = floatArrayOf(0f, 0f, w, h)
        val res = ArrayList<Card>(slats.coerceAtLeast(2))

        fun add(image: Int, pose: Pair<FloatArray, Float>, shade: Float = 0f, alpha: Float = 1f, strip: Int = -1) {
            res.add(Card(image, strip, pose.first, pose.second, shade.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f)))
        }

        fun place(
            rect: FloatArray, px: Float, py: Float, pz: Float,
            rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
            tx: Float = 0f, ty: Float = 0f, tz: Float = 0f,
        ) = place(w, h, d, rect, px, py, pz, rx, ry, rz, tx, ty, tz)

        when (kind) {
            Kind.LEAPFROG -> {
                val u = seg(t, 0f, 0.5f)
                add(0, place(full, cx, cy, 0f, tz = 0.55f * d * ease(u)), 0.5f * ease(u))
                val e = ease(seg(t, 0.1f, 0.8f))
                val tz: Float
                val ty: Float
                val rx: Float
                if (t < 0.8f) {
                    tz = 0.9f * d * (1 - e) - 0.07f * d * e
                    ty = -0.75f * h * sinPi(e)
                    rx = 14f * sinPi(e)
                } else {
                    // Land slightly too big, then settle with a bounce.
                    val u2 = seg(t, 0.8f, 1f)
                    tz = -0.07f * d * cos(2.5f * PI * u2) * (1 - u2).pow(1.5f)
                    ty = 0f
                    rx = 0f
                }
                add(1, place(full, cx, cy, 0f, rx = rx, ty = ty, tz = tz), 0.3f * (1 - e))
            }
            Kind.TURNAROUND -> {
                val tz = 0.45f * d * (ease(seg(t, 0f, 0.3f)) - easeOut(seg(t, 0.7f, 1f)))
                val ang = 180f * ease(seg(t, 0.3f, 0.7f)) * direction
                val shade = 0.45f * abs(sinDeg(ang))
                if (abs(ang) <= 90f) {
                    add(0, place(full, cx, cy, 0f, ry = ang, tz = tz), shade)
                } else {
                    // The back of the card: the new photo, the right way round.
                    add(1, place(full, cx, cy, 0f, ry = ang - 180f * direction, tz = tz), shade)
                }
            }
            Kind.CUBE -> {
                val a = 90f * ease(seg(t, 0.05f, 0.95f))
                val tz = 0.3f * d * sinPi(t)
                val sa = sinDeg(a)
                val ca = cosDeg(a)
                if (direction == 1 || direction == -1) {
                    add(0, place(full, cx, cy, w / 2f, ry = -a * direction, tz = tz), 0.55f * sa)
                    add(1, place(full, cx, cy, w / 2f, ry = (90f - a) * direction, tz = tz), 0.55f * ca)
                } else {
                    val dv = if (direction == 2) 1 else -1
                    add(0, place(full, cx, cy, h / 2f, rx = -a * dv, tz = tz), 0.55f * sa)
                    add(1, place(full, cx, cy, h / 2f, rx = (90f - a) * dv, tz = tz), 0.55f * ca)
                }
            }
            Kind.DOOR -> {
                val ang = -90f * ease(seg(t, 0f, 0.8f))
                val hingeX = if (direction == 1) 0f else w
                val u = ease(seg(t, 0.15f, 1f))
                add(1, place(full, cx, cy, 0f, tz = 0.12f * d * (1 - u)), 0.5f * (1 - u))
                add(
                    0, place(full, hingeX, cy, 0f, ry = ang * direction),
                    0.4f * seg(t, 0.3f, 0.8f), alpha = 1 - seg(t, 0.65f, 0.85f),
                )
            }
            Kind.FALL_AWAY -> {
                val u = seg(t, 0f, 0.6f)
                var ang = -90f * u * u // falls faster and faster
                if (t > 0.6f) ang = -90f + 8f * sinPi(seg(t, 0.6f, 0.75f)) // small bounce
                val e = ease(seg(t, 0.55f, 1f))
                add(1, place(full, cx, h, 0f, tz = 1.05f * h * (1 - e)), 0.45f * (1 - e))
                add(0, place(full, cx, h, 0f, rx = ang), 0.5f * u, alpha = 1 - seg(t, 0.75f, 0.95f))
            }
            Kind.CAROUSEL -> {
                val span = 60f
                val r = 1.0f * w
                val a = span * ease(seg(t, 0.1f, 0.9f))
                val tz = 0.25f * d * sinPi(t)
                add(0, place(full, cx, cy, r, ry = -a * direction, tz = tz), 0.6f * a / span)
                add(1, place(full, cx, cy, r, ry = (span - a) * direction, tz = tz), 0.6f * (span - a) / span)
            }
            Kind.CARD_STACK -> {
                val e = ease(seg(t, 0.3f, 1f))
                add(1, place(full, cx, cy, 0f, tz = 0.12f * d * (1 - e)), 0.5f * (1 - e))
                val u1 = ease(seg(t, 0f, 0.25f))
                val u2 = seg(t, 0.25f, 1f).let { it * it }
                add(
                    0, place(
                        full, cx, cy, 0f,
                        rz = (-4f * u1 - 30f * u2) * direction, ry = -25f * u2 * direction,
                        tx = -1.5f * w * u2 * direction, ty = 0.15f * h * u2, tz = -0.12f * d * u1,
                    ),
                )
            }
            Kind.BLINDS -> {
                val n = slats
                for (i in 0 until n) {
                    val x0 = (i * w / n).roundToInt().toFloat()
                    val x1 = ((i + 1) * w / n).roundToInt().toFloat()
                    val k = if (direction == 1) i else n - 1 - i
                    val start = k * 0.45f / max(1, n - 1)
                    val u = seg(t, start, start + 0.55f)
                    val ang = 180f * ease(u)
                    val tz = 0.06f * d * sinPi(u)
                    val rect = floatArrayOf(x0, 0f, x1, h)
                    val shade = 0.5f * abs(sinDeg(ang))
                    if (ang <= 90f) {
                        add(0, place(rect, (x0 + x1) / 2f, cy, 0f, ry = ang, tz = tz), shade, strip = i)
                    } else {
                        add(1, place(rect, (x0 + x1) / 2f, cy, 0f, ry = ang - 180f, tz = tz), shade, strip = i)
                    }
                }
            }
        }
        // Farthest first.
        res.sortByDescending { it.depth }
        return res
    }

    companion object {
        private const val PI = Math.PI.toFloat()

        /** Number of slats for BLINDS: more for a wide screen. */
        fun slatCount(w: Int, h: Int): Int = max(4, (w.toFloat() / h * 5f).roundToInt())

        private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
        private fun ease(u: Float) = u * u * (3 - 2 * u)
        private fun easeOut(u: Float) = 1 - (1 - u).pow(3)
        private fun sinPi(u: Float) = sin(PI * u)
        private fun sinDeg(a: Float) = sin(Math.toRadians(a.toDouble())).toFloat()
        private fun cosDeg(a: Float) = cos(Math.toRadians(a.toDouble())).toFloat()

        /**
         * Corners of [rect] (x0, y0, x1, y1 in the screen plane), turned about
         * the pivot by rz, then rx, then ry (degrees), moved by (tx, ty, tz),
         * and projected from a viewer at (w/2, h/2, -d). Screen coordinates:
         * x right, y down, z away from the viewer. Returns the 4 projected
         * corners (TL, TR, BR, BL) and their mean depth.
         */
        private fun place(
            w: Float, h: Float, d: Float, rect: FloatArray,
            px: Float, py: Float, pz: Float,
            rx: Float, ry: Float, rz: Float,
            tx: Float, ty: Float, tz: Float,
        ): Pair<FloatArray, Float> {
            val cx = w / 2f
            val cy = h / 2f
            val ax = Math.toRadians(rx.toDouble())
            val ay = Math.toRadians(ry.toDouble())
            val az = Math.toRadians(rz.toDouble())
            val out = FloatArray(8)
            var depth = 0.0
            val xs = floatArrayOf(rect[0], rect[2], rect[2], rect[0])
            val ys = floatArrayOf(rect[1], rect[1], rect[3], rect[3])
            for (i in 0 until 4) {
                var x = (xs[i] - px).toDouble()
                var y = (ys[i] - py).toDouble()
                var z = (-pz).toDouble()
                val x1 = x * cos(az) - y * sin(az)
                y = x * sin(az) + y * cos(az)
                x = x1
                val y1 = y * cos(ax) - z * sin(ax)
                z = y * sin(ax) + z * cos(ax)
                y = y1
                val x2 = x * cos(ay) - z * sin(ay)
                z = x * sin(ay) + z * cos(ay)
                x = x2
                x += px + tx
                y += py + ty
                z += pz + tz
                val s = d / max(d + z, d * 0.05)
                out[2 * i] = (cx + (x - cx) * s).toFloat()
                out[2 * i + 1] = (cy + (y - cy) * s).toFloat()
                depth += z / 4
            }
            return Pair(out, depth.toFloat())
        }
    }
}
