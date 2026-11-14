package com.memorylink.photoframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Touch layer over the photo, and the photo grid.
 *
 * While a single photo is shown the view is transparent and only turns
 * gestures into callbacks: a horizontal swipe for the next or previous photo,
 * a pinch to open the grid and a long press for the settings. With the grid
 * open it draws the photos as N x N cells (N = 2..8, changed by pinching) and
 * scrolls vertically; tapping a cell, or pinching in on the 2 x 2 grid, opens
 * that photo.
 */
class BrowseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** A horizontal swipe on the photo: +1 for the next photo, -1 for the previous. */
        fun onBrowseSwipe(step: Int)

        /** A pinch on the photo: open the grid. */
        fun onBrowsePinchOut()

        /** A cell of the grid was chosen. */
        fun onBrowseOpen(index: Int)

        fun onBrowseLongPress()

        /** Any touch; used to keep browsing alive. */
        fun onBrowseActivity()
    }

    /** Gives thumbnails of [width] x [height] or more, or null while one is loading. */
    fun interface ThumbSource {
        fun thumb(ref: ImageRef, width: Int, height: Int): Bitmap?
    }

    var listener: Listener? = null
    var thumbs: ThumbSource? = null

    var gridVisible = false
        private set

    /** Space kept free above the first row (for the tag chips). */
    var topInset = 0f
        set(value) {
            field = value
            if (gridVisible) scrollTo(scrollPos)
        }
    var columns = 2
        private set

    private var items: List<ImageRef> = emptyList()
    private var current = -1
    private var scrollPos = 0f

    private val density = resources.displayMetrics.density
    private val gap = 6f * density
    private val minSwipe = 60f * density
    private val scroller = OverScroller(context)
    private val cell = RectF()
    private val src = Rect()

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint().apply { color = 0xff26282e.toInt() }
    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.WHITE
    }

    private var pinch = 1f
    private var scaledThisGesture = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinch = 1f
                scaledThisGesture = true
                scroller.forceFinished(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinch *= detector.scaleFactor
                if (!gridVisible) {
                    if (pinch < PINCH_OUT) {
                        pinch = 1f
                        listener?.onBrowsePinchOut()
                    }
                    return true
                }
                if (pinch < PINCH_OUT) {
                    pinch = 1f
                    if (columns < MAX_COLUMNS) {
                        setColumns(columns + 1, detector.focusX, detector.focusY)
                    }
                } else if (pinch > PINCH_IN) {
                    pinch = 1f
                    if (columns > MIN_COLUMNS) {
                        setColumns(columns - 1, detector.focusX, detector.focusY)
                    } else {
                        val index = nearestIndex(detector.focusX, detector.focusY)
                        if (index >= 0) listener?.onBrowseOpen(index)
                    }
                }
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!gridVisible || scaleDetector.isInProgress || e2.pointerCount > 1) {
                    return false
                }
                scrollTo(scrollPos + distanceY)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (scaledThisGesture) return false
                if (gridVisible) {
                    scroller.fling(
                        0, scrollPos.toInt(), 0, (-velocityY).toInt(),
                        0, 0, 0, maxScroll().toInt(),
                    )
                    postInvalidateOnAnimation()
                    return true
                }
                val dx = if (e1 != null) e2.x - e1.x else velocityX
                val dy = if (e1 != null) e2.y - e1.y else velocityY
                if (abs(dx) > abs(dy) * 1.2f && (e1 == null || abs(dx) > minSwipe)) {
                    listener?.onBrowseSwipe(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!gridVisible) return false
                val index = indexAt(e.x, e.y)
                if (index >= 0) listener?.onBrowseOpen(index)
                return index >= 0
            }

            override fun onLongPress(e: MotionEvent) {
                if (!gridVisible && !scaledThisGesture) listener?.onBrowseLongPress()
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scaledThisGesture = false
            scroller.forceFinished(true)
            listener?.onBrowseActivity()
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** Open the grid on [list], with [selected] marked and scrolled into view. */
    fun showGrid(list: List<ImageRef>, selected: Int, cols: Int) {
        items = list
        current = selected
        columns = cols.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        gridVisible = true
        scroller.forceFinished(true)
        scrollPos = 0f
        if (selected >= 0) {
            val row = selected / columns
            scrollTo(rowTop(row) - (height + topInset - cellHeight()) / 2f)
        }
        invalidate()
    }

    fun hideGrid() {
        if (!gridVisible) return
        gridVisible = false
        scroller.forceFinished(true)
        items = emptyList()
        invalidate()
    }

    private fun cellWidth(): Float = (width - gap * (columns + 1)) / columns

    /** Cells have the screen's proportions, so they look like small frames. */
    private fun cellHeight(): Float =
        if (width > 0) cellWidth() * height / width else 0f

    private fun rowTop(row: Int): Float = gap + topInset + row * (cellHeight() + gap)

    private fun maxScroll(): Float {
        val rows = (items.size + columns - 1) / columns
        return max(0f, rowTop(rows) - height)
    }

    private fun scrollTo(pos: Float) {
        scrollPos = pos.coerceIn(0f, maxScroll())
        invalidate()
    }

    private fun cellRect(index: Int, out: RectF) {
        val w = cellWidth()
        val left = gap + (index % columns) * (w + gap)
        val top = rowTop(index / columns) - scrollPos
        out.set(left, top, left + w, top + cellHeight())
    }

    private fun indexAt(x: Float, y: Float): Int {
        val pitchX = cellWidth() + gap
        val pitchY = cellHeight() + gap
        if (pitchX <= 0f || pitchY <= 0f) return -1
        val col = floor((x - gap) / pitchX).toInt()
        val row = floor((y + scrollPos - gap - topInset) / pitchY).toInt()
        if (col !in 0 until columns || row < 0) return -1
        val index = row * columns + col
        if (index >= items.size) return -1
        cellRect(index, cell)
        return if (cell.contains(x, y)) index else -1
    }

    /** The cell under (x, y), or the closest one when the point falls in a gap. */
    private fun nearestIndex(x: Float, y: Float): Int {
        if (items.isEmpty()) return -1
        val pitchX = cellWidth() + gap
        val pitchY = cellHeight() + gap
        if (pitchX <= 0f || pitchY <= 0f) return -1
        val col = floor((x - gap / 2) / pitchX).toInt().coerceIn(0, columns - 1)
        val row = floor((y + scrollPos - gap / 2 - topInset) / pitchY).toInt().coerceAtLeast(0)
        return min(row * columns + col, items.size - 1)
    }

    /** Change the number of columns, keeping the photo under the fingers in place. */
    private fun setColumns(cols: Int, focusX: Float, focusY: Float) {
        val anchor = nearestIndex(focusX, focusY)
        var fraction = 0.5f
        if (anchor >= 0) {
            cellRect(anchor, cell)
            if (cell.height() > 0f) {
                fraction = ((focusY - cell.top) / cell.height()).coerceIn(0f, 1f)
            }
        }
        columns = cols
        if (anchor >= 0) {
            val top = rowTop(anchor / columns)
            scrollTo(top + fraction * cellHeight() - focusY)
        } else {
            scrollTo(scrollPos)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPos = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (gridVisible) scrollTo(scrollPos)
    }

    override fun onDraw(canvas: Canvas) {
        if (!gridVisible) return
        canvas.drawColor(0xff101114.toInt())
        val w = cellWidth()
        val h = cellHeight()
        if (w <= 0f || h <= 0f) return
        val pitchY = h + gap
        val firstRow = max(0, floor((scrollPos - gap - topInset) / pitchY).toInt())
        val lastRow = ceil((scrollPos + height) / pitchY).toInt()
        val source = thumbs
        for (row in firstRow..lastRow) {
            for (col in 0 until columns) {
                val index = row * columns + col
                if (index >= items.size) break
                cellRect(index, cell)
                val bmp = source?.thumb(items[index], ceil(w).toInt(), ceil(h).toInt())
                if (bmp != null && !bmp.isRecycled) {
                    // Fill the cell, cropping the photo's overflow evenly.
                    val scale = max(w / bmp.width, h / bmp.height)
                    val sw = (w / scale).toInt().coerceAtMost(bmp.width)
                    val sh = (h / scale).toInt().coerceAtMost(bmp.height)
                    val sx = (bmp.width - sw) / 2
                    val sy = (bmp.height - sh) / 2
                    src.set(sx, sy, sx + sw, sy + sh)
                    canvas.drawBitmap(bmp, src, cell, photoPaint)
                } else {
                    canvas.drawRect(cell, emptyPaint)
                }
                if (index == current) {
                    val inset = currentPaint.strokeWidth / 2
                    cell.inset(inset, inset)
                    canvas.drawRect(cell, currentPaint)
                }
            }
        }
    }

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 8
        private const val PINCH_OUT = 0.8f
        private const val PINCH_IN = 1.25f
    }
}
