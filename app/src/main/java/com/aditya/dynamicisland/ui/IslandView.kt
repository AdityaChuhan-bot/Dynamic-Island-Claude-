package com.aditya.dynamicisland.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.aditya.dynamicisland.model.IslandContent
import com.aditya.dynamicisland.model.IslandContentType
import kotlin.math.abs
import kotlin.math.min

/** Spring-animated Dynamic Island pill with direct touch gestures. */
class IslandView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class IslandState { COLLAPSED, COMPACT, EXPANDED }
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var curWidth = 0f
    private var curHeight = 0f
    private var curRadius = 0f
    private val collapsedW get() = dp(120f)
    private val collapsedH get() = dp(36f)
    private val compactW get() = dp(190f)
    private val compactH get() = dp(40f)
    private val expandedW get() = min(width.toFloat(), dp(340f))
    private val expandedH get() = dp(110f)
    var state = IslandState.COLLAPSED
        private set
    private var content = IslandContent()
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f) }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180,255,255,255); textSize = dp(11f) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A84FF") }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(340f).toInt(), widthMeasureSpec), resolveSize(dp(126f).toInt(), heightMeasureSpec))
    }

    private val widthProp = object : FloatPropertyCompat<IslandView>("pillWidth") {
        override fun getValue(obj: IslandView) = obj.curWidth
        override fun setValue(obj: IslandView, value: Float) { obj.curWidth = value; obj.invalidate() }
    }
    private val heightProp = object : FloatPropertyCompat<IslandView>("pillHeight") {
        override fun getValue(obj: IslandView) = obj.curHeight
        override fun setValue(obj: IslandView, value: Float) { obj.curHeight = value; obj.invalidate() }
    }
    private val radiusProp = object : FloatPropertyCompat<IslandView>("pillRadius") {
        override fun getValue(obj: IslandView) = obj.curRadius
        override fun setValue(obj: IslandView, value: Float) { obj.curRadius = value; obj.invalidate() }
    }
    private fun spring(prop: FloatPropertyCompat<IslandView>) = SpringAnimation(this, prop).apply {
        spring = SpringForce().apply { stiffness = SpringForce.STIFFNESS_MEDIUM; dampingRatio = 0.62f }
    }
    private val widthSpring = spring(widthProp)
    private val heightSpring = spring(heightProp)
    private val radiusSpring = spring(radiusProp)
    private var tracker: VelocityTracker? = null
    private var dragging = false
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleExpandCollapse(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) < 600f) return false
            setState(if (velocityY > 0) IslandState.EXPANDED else if (content.type == IslandContentType.NONE) IslandState.COLLAPSED else IslandState.COMPACT, velocityY)
            return true
        }
    })

    init { setOnTouchListener { _, event -> handleTouch(event) } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (curWidth == 0f) { curWidth = collapsedW; curHeight = collapsedH; curRadius = collapsedH / 2f }
    }

    fun updateContent(newContent: IslandContent) {
        content = newContent
        setState(when {
            newContent.type == IslandContentType.NONE -> IslandState.COLLAPSED
            state == IslandState.EXPANDED -> IslandState.EXPANDED
            else -> IslandState.COMPACT
        })
        invalidate()
    }

    fun toggleExpandCollapse() {
        setState(if (state == IslandState.EXPANDED) {
            if (content.type == IslandContentType.NONE) IslandState.COLLAPSED else IslandState.COMPACT
        } else IslandState.EXPANDED)
    }

    fun setState(next: IslandState, velocity: Float = 0f) {
        state = next
        val (w,h) = when (next) {
            IslandState.COLLAPSED -> collapsedW to collapsedH
            IslandState.COMPACT -> compactW to compactH
            IslandState.EXPANDED -> expandedW to expandedH
        }
        widthSpring.cancel(); heightSpring.cancel(); radiusSpring.cancel()
        widthSpring.setStartVelocity(velocity * 0.3f).animateToFinalPosition(w)
        heightSpring.setStartVelocity(velocity).animateToFinalPosition(h)
        radiusSpring.animateToFinalPosition(h / 2f)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                startY = event.rawY; startW = curWidth; startH = curHeight; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dy = event.rawY - startY
                if (abs(dy) > dp(6f)) {
                    dragging = true; widthSpring.cancel(); heightSpring.cancel(); radiusSpring.cancel()
                    val t = (dy / dp(160f)).coerceIn(-1f,1f)
                    val baseW = if (content.type == IslandContentType.NONE) collapsedW else compactW
                    val baseH = if (content.type == IslandContentType.NONE) collapsedH else compactH
                    curWidth = if (t >= 0) lerp(startW, expandedW, t) else lerp(startW, baseW, -t)
                    curHeight = if (t >= 0) lerp(startH, expandedH, t) else lerp(startH, baseH, -t)
                    curRadius = curHeight / 2f; invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    val fraction = (curHeight - min(collapsedH,compactH)) / (expandedH - min(collapsedH,compactH))
                    val target = if (fraction > 0.5f || vy < -800f) IslandState.EXPANDED else if (content.type == IslandContentType.NONE) IslandState.COLLAPSED else IslandState.COMPACT
                    setState(target, vy)
                }
                tracker?.recycle(); tracker = null; dragging = false
            }
        }
        return true
    }

    private fun lerp(a: Float,b: Float,t: Float) = a + (b-a)*t
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = (width-curWidth)/2f
        rect.set(left, dp(4f), left+curWidth, dp(4f)+curHeight)
        canvas.drawRoundRect(rect,curRadius,curRadius,pill)
        if (state == IslandState.COLLAPSED) return
        val pad = dp(14f); val dot = dp(4f)
        canvas.drawCircle(rect.left+pad+dot,rect.centerY(),dot,accent)
        val x = rect.left+pad*2+dot*2
        val title = content.title.ifBlank { label(content.type) }
        canvas.drawText(elide(title,text,rect.width()-pad*3-dot*2),x,if(state==IslandState.COMPACT) rect.centerY()+text.textSize/3f else rect.top+pad+text.textSize,text)
        if (state == IslandState.EXPANDED) {
            if (content.subtitle.isNotBlank()) canvas.drawText(elide(content.subtitle,subText,rect.width()-pad*3-dot*2),x,rect.top+pad+text.textSize+dp(16f),subText)
            content.progress?.let { p ->
                val y=rect.bottom-dp(14f); val right=rect.right-pad
                val bg=Paint(subText).apply { color=Color.argb(70,255,255,255) }
                canvas.drawRoundRect(x,y,right,y+dp(3f),dp(2f),dp(2f),bg)
                canvas.drawRoundRect(x,y,x+(right-x)*p.coerceIn(0f,1f),y+dp(3f),dp(2f),dp(2f),accent)
            }
        }
    }
    private fun label(type: IslandContentType) = when(type) {
        IslandContentType.MEDIA -> "Now Playing"
        IslandContentType.CALL -> "Call"
        IslandContentType.TIMER -> "Timer"
        IslandContentType.GENERIC_NOTIFICATION -> "Notification"
        IslandContentType.NONE -> ""
    }
    private fun elide(value: String, paint: Paint, max: Float): String {
        if (max <= 0f) return ""
        if (paint.measureText(value) <= max) return value
        var end=value.length
        while(end>0 && paint.measureText(value.substring(0,end)+"…")>max) end--
        return value.substring(0,end)+"…"
    }
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        widthSpring.cancel(); heightSpring.cancel(); radiusSpring.cancel(); tracker?.recycle()
    }
}