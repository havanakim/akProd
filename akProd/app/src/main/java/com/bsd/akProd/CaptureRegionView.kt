package com.bsd.akProd

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Regions use the supplied panel photograph as a starting template; each can be adjusted. */
class CaptureRegionView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var selectedKey = "MONITOR"
        set(value) { field = value; invalidate() }
    var onRegionChanged: (() -> Unit)? = null
    var onMonitorChanged: ((RectF) -> Unit)? = null
    val regions = linkedMapOf<String, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imageRect = RectF()
    private var startX = 0f
    private var startY = 0f
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * scale; val h = image.height * scale
        imageRect.set((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2)
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(image, null, imageRect, paint)
        paint.style = Paint.Style.STROKE
        regions.forEach { (key, box) ->
            paint.color = if (key == selectedKey) Color.YELLOW else Color.RED
            paint.strokeWidth = if (key == selectedKey) 5f else 2f
            canvas.drawRect(imageRect.left + box.left*w, imageRect.top + box.top*h,
                imageRect.left + box.right*w, imageRect.top + box.bottom*h, paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null || imageRect.width() <= 0) return false
        val x = ((event.x-imageRect.left)/imageRect.width()).coerceIn(0f, 1f)
        val y = ((event.y-imageRect.top)/imageRect.height()).coerceIn(0f, 1f)
        when(event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX=x; startY=y
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (abs(x-startX) > .005f && abs(y-startY) > .005f) {
                    regions[selectedKey] = RectF(min(startX,x), min(startY,y), max(startX,x), max(startY,y))
                    if (selectedKey == "MONITOR") onMonitorChanged?.invoke(RectF(regions.getValue(selectedKey)))
                    invalidate()
                    onRegionChanged?.invoke()
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    parent.requestDisallowInterceptTouchEvent(false); performClick()
                }
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun crop(key: String): Bitmap {
        val image = requireNotNull(bitmap)
        val r = requireNotNull(regions[key])
        val left = (r.left*image.width).toInt().coerceIn(0,image.width-1)
        val top = (r.top*image.height).toInt().coerceIn(0,image.height-1)
        val w = ((r.right-r.left)*image.width).toInt().coerceIn(1,image.width-left)
        val h = ((r.bottom-r.top)*image.height).toInt().coerceIn(1,image.height-top)
        val cropped = Bitmap.createBitmap(image,left,top,w,h)
        val scale = (80f/h).coerceIn(1f,8f)
        val scaled=Bitmap.createScaledBitmap(cropped, (w*scale).toInt(), (h*scale).toInt(), true)
        // The caller owns/recycles the ROI, even if Android reuses the original bitmap.
        val result=if(scaled===image) checkNotNull(image.copy(Bitmap.Config.ARGB_8888,false)) else scaled
        if(cropped!==image && cropped!==result) cropped.recycle()
        return result
    }
}

