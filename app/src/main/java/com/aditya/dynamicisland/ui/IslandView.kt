package com.aditya.dynamicisland.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.aditya.dynamicisland.model.IslandContent
import com.aditya.dynamicisland.model.IslandContentType
import com.aditya.dynamicisland.model.IslandRepository
import com.aditya.dynamicisland.model.MediaCommand
import kotlin.math.abs
import kotlin.math.min

/**
 * Spring-animated Dynamic Island view using Android DynamicAnimation (SpringAnimation).
 * Handles fluid physical expansion, contraction, and touch event handling to:
 * - Media playback controls (Previous, Play/Pause, Next) with real-time transport actions.
 * - Tap to open associated application via PendingIntent or package launcher.
 * - Tap dismiss button (✕) or swipe up to dismiss notifications.
 */
class IslandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class IslandState { COLLAPSED, COMPACT, EXPANDED }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Animated geometry properties
    private var curWidth = 0f
    private var curHeight = 0f
    private var curRadius = 0f
    private var curExpandedAlpha = 0f
    private var curCompactAlpha = 0f

    // Dimensions for states
    private val collapsedW get() = dp(120f)
    private val collapsedH get() = dp(36f)
    private val compactW get() = dp(200f)
    private val compactH get() = dp(40f)
    private val expandedW get() = if (width > 0) min(width.toFloat(), dp(340f)) else dp(340f)
    private val expandedH get() = if (content.type == IslandContentType.MEDIA) dp(142f) else dp(122f)

    var state = IslandState.COLLAPSED
        private set

    private var content = IslandContent()

    // Cached paints for onDraw performance
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(13f) }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(180, 255, 255, 255); textSize = dp(11f) }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = dp(9.5f) }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 255, 255, 255); textSize = dp(9f) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A84FF") }
    private val progressBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val dismissBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 255, 255, 255) }
    private val dismissIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
    }

    // Media controls paints & paths
    private val playPauseBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playPauseIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val mediaIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val audioWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1DB954")
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private val dismissHitRect = RectF()
    private val playPauseBtnHitRect = RectF()
    private val prevBtnHitRect = RectF()
    private val nextBtnHitRect = RectF()

    private var onOutsideTouchListener: (() -> Unit)? = null
    private var onStateChangeListener: ((IslandState) -> Unit)? = null
    private var autoCollapseRunnable: Runnable? = null

    fun setOnOutsideTouchListener(listener: () -> Unit) {
        onOutsideTouchListener = listener
    }

    fun setOnStateChangeListener(listener: (IslandState) -> Unit) {
        onStateChangeListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(340f).toInt(), widthMeasureSpec),
            resolveSize(dp(152f).toInt(), heightMeasureSpec)
        )
    }

    // DynamicAnimation Float Properties
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

    private val expandedAlphaProp = object : FloatPropertyCompat<IslandView>("expandedAlpha") {
        override fun getValue(obj: IslandView) = obj.curExpandedAlpha
        override fun setValue(obj: IslandView, value: Float) { obj.curExpandedAlpha = value.coerceIn(0f, 1f); obj.invalidate() }
    }

    private val compactAlphaProp = object : FloatPropertyCompat<IslandView>("compactAlpha") {
        override fun getValue(obj: IslandView) = obj.curCompactAlpha
        override fun setValue(obj: IslandView, value: Float) { obj.curCompactAlpha = value.coerceIn(0f, 1f); obj.invalidate() }
    }

    private fun createSpring(prop: FloatPropertyCompat<IslandView>) = SpringAnimation(this, prop).apply {
        spring = SpringForce().apply {
            stiffness = SpringForce.STIFFNESS_MEDIUM
            dampingRatio = 0.72f
        }
    }

    private val widthSpring = createSpring(widthProp)
    private val heightSpring = createSpring(heightProp)
    private val radiusSpring = createSpring(radiusProp)
    private val expandedAlphaSpring = createSpring(expandedAlphaProp)
    private val compactAlphaSpring = createSpring(compactAlphaProp)

    private var tracker: VelocityTracker? = null
    private var dragging = false
    private var startY = 0f
    private var startW = 0f
    private var startH = 0f

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            cancelAutoCollapse()

            if (state == IslandState.EXPANDED) {
                // 1. If tapped within the dismiss button hit area: dismiss notification
                if (dismissHitRect.contains(e.x, e.y)) {
                    dismissNotification()
                    return true
                }

                // 2. If media content is showing, check media control buttons
                if (content.type == IslandContentType.MEDIA) {
                    if (prevBtnHitRect.contains(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        IslandRepository.sendMediaCommand(MediaCommand.PREVIOUS)
                        return true
                    }
                    if (playPauseBtnHitRect.contains(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        IslandRepository.sendMediaCommand(MediaCommand.PLAY_PAUSE)
                        return true
                    }
                    if (nextBtnHitRect.contains(e.x, e.y)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        IslandRepository.sendMediaCommand(MediaCommand.NEXT)
                        return true
                    }
                }

                // 3. Tapping anywhere else in the expanded card: open the associated app
                openAssociatedApp()
                return true
            }

            // In compact or collapsed state: expand to view details
            toggleExpandCollapse()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (abs(velocityY) < 350f) return false
            cancelAutoCollapse()

            // Swiping upwards on the expanded island dismisses the notification
            if (state == IslandState.EXPANDED && velocityY < -350f) {
                dismissNotification()
                return true
            }

            val target = if (velocityY > 0) {
                IslandState.EXPANDED
            } else if (content.type == IslandContentType.NONE) {
                IslandState.COLLAPSED
            } else {
                IslandState.COMPACT
            }
            setState(target, velocityY)
            return true
        }
    })

    init {
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (curWidth == 0f) {
            val isNone = content.type == IslandContentType.NONE
            val initialW = if (isNone) collapsedW else compactW
            val initialH = if (isNone) collapsedH else compactH
            curWidth = initialW
            curHeight = initialH
            curRadius = initialH / 2f
            curExpandedAlpha = 0f
            curCompactAlpha = if (isNone) 0f else 1f
        }
    }

    fun updateContent(newContent: IslandContent) {
        content = newContent
        val targetState = when {
            newContent.type == IslandContentType.NONE -> IslandState.COLLAPSED
            state == IslandState.EXPANDED -> IslandState.EXPANDED
            else -> IslandState.COMPACT
        }
        setState(targetState)
    }

    fun cancelAutoCollapse() {
        autoCollapseRunnable?.let { removeCallbacks(it) }
        autoCollapseRunnable = null
    }

    /**
     * Temporarily expands the island (e.g. for incoming notifications)
     * and automatically collapses it after durationMs unless interacted with.
     */
    fun expandTemporarily(durationMs: Long = 4500L) {
        cancelAutoCollapse()
        setState(IslandState.EXPANDED)
        autoCollapseRunnable = Runnable {
            if (state == IslandState.EXPANDED) {
                val next = if (content.type == IslandContentType.NONE) {
                    IslandState.COLLAPSED
                } else {
                    IslandState.COMPACT
                }
                setState(next)
            }
        }.also {
            postDelayed(it, durationMs)
        }
    }

    /**
     * Opens the associated application via its PendingIntent or package launcher intent,
     * then gracefully contracts the island.
     */
    fun openAssociatedApp(): Boolean {
        cancelAutoCollapse()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        var opened = false

        // 1. Try launching the notification's PendingIntent
        if (content.intent != null) {
            val result = runCatching {
                content.intent?.send()
            }
            if (result.isSuccess) {
                opened = true
            }
        }

        // 2. Fallback to package launcher intent if PendingIntent is absent or failed
        if (!opened && !content.packageName.isNullOrBlank()) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(content.packageName!!)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val result = runCatching {
                    context.startActivity(launchIntent)
                }
                if (result.isSuccess) {
                    opened = true
                }
            }
        }

        // 3. Contract the island after launching
        val next = if (content.type == IslandContentType.NONE) {
            IslandState.COLLAPSED
        } else {
            IslandState.COMPACT
        }
        setState(next)
        return opened
    }

    /**
     * Dismisses the active notification from the system shade and contracts the island.
     */
    fun dismissNotification() {
        cancelAutoCollapse()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // Dismiss through repository and notification listener
        IslandRepository.dismissCurrent()

        // Contract back to idle or ongoing media/timer
        val next = if (content.type == IslandContentType.NONE) {
            IslandState.COLLAPSED
        } else {
            IslandState.COMPACT
        }
        setState(next)
    }

    fun toggleExpandCollapse() {
        cancelAutoCollapse()
        val next = if (state == IslandState.EXPANDED) {
            if (content.type == IslandContentType.NONE) IslandState.COLLAPSED else IslandState.COMPACT
        } else {
            IslandState.EXPANDED
        }
        setState(next)
    }

    /**
     * Updates target state and launches physics-based DynamicAnimation springs.
     * Tunes stiffness and damping for fluid, organic expansion and crisp, snappy contraction.
     */
    fun setState(next: IslandState, velocity: Float = 0f) {
        state = next
        onStateChangeListener?.invoke(next)

        val (targetW, targetH) = when (next) {
            IslandState.COLLAPSED -> collapsedW to collapsedH
            IslandState.COMPACT -> compactW to compactH
            IslandState.EXPANDED -> expandedW to expandedH
        }
        val targetRadius = targetH / 2f
        val targetExpandedAlpha = if (next == IslandState.EXPANDED) 1f else 0f
        val targetCompactAlpha = if (next == IslandState.COLLAPSED) 0f else 1f

        // Physics tuning: Slightly bouncy expansion, critically-damped snappy contraction
        val isExpanding = next == IslandState.EXPANDED
        val stiffness = if (isExpanding) 350f else 550f
        val damping = if (isExpanding) 0.70f else 0.82f

        listOf(widthSpring, heightSpring, radiusSpring, expandedAlphaSpring, compactAlphaSpring).forEach { anim ->
            anim.spring?.let {
                it.stiffness = stiffness
                it.dampingRatio = damping
            }
            anim.cancel()
        }

        widthSpring.setStartVelocity(velocity * 0.25f).animateToFinalPosition(targetW)
        heightSpring.setStartVelocity(velocity).animateToFinalPosition(targetH)
        radiusSpring.animateToFinalPosition(targetRadius)
        expandedAlphaSpring.setStartVelocity(velocity * 0.0015f).animateToFinalPosition(targetExpandedAlpha)
        compactAlphaSpring.animateToFinalPosition(targetCompactAlpha)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            cancelAutoCollapse()
            onOutsideTouchListener?.invoke()
            if (state == IslandState.EXPANDED) {
                toggleExpandCollapse()
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelAutoCollapse()
            if (!rect.contains(event.x, event.y)) {
                if (state == IslandState.EXPANDED) {
                    toggleExpandCollapse()
                    return true
                }
                return false
            }
        }

        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                startY = event.rawY
                startW = curWidth
                startH = curHeight
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dy = event.rawY - startY
                if (abs(dy) > dp(6f)) {
                    dragging = true
                    listOf(widthSpring, heightSpring, radiusSpring, expandedAlphaSpring, compactAlphaSpring).forEach { it.cancel() }

                    val t = (dy / dp(150f)).coerceIn(-1f, 1f)
                    val baseW = if (content.type == IslandContentType.NONE) collapsedW else compactW
                    val baseH = if (content.type == IslandContentType.NONE) collapsedH else compactH

                    if (t >= 0) {
                        curWidth = lerp(startW, expandedW, t)
                        curHeight = lerp(startH, expandedH, t)
                        curExpandedAlpha = lerp(curExpandedAlpha, 1f, t)
                    } else {
                        curWidth = lerp(startW, baseW, -t)
                        curHeight = lerp(startH, baseH, -t)
                        curExpandedAlpha = lerp(curExpandedAlpha, 0f, -t)
                    }
                    curRadius = curHeight / 2f
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f

                    // Swipe up on expanded view dismisses notification
                    if (state == IslandState.EXPANDED && vy < -500f) {
                        dismissNotification()
                    } else {
                        val minH = if (content.type == IslandContentType.NONE) collapsedH else compactH
                        val fraction = if (expandedH > minH) (curHeight - minH) / (expandedH - minH) else 0f
                        val target = if (fraction > 0.45f || vy > 700f) {
                            IslandState.EXPANDED
                        } else if (content.type == IslandContentType.NONE) {
                            IslandState.COLLAPSED
                        } else {
                            IslandState.COMPACT
                        }
                        setState(target, vy)
                    }
                }
                tracker?.recycle()
                tracker = null
                dragging = false
            }
        }
        return true
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = (width - curWidth) / 2f
        rect.set(left, dp(4f), left + curWidth, dp(4f) + curHeight)
        canvas.drawRoundRect(rect, curRadius, curRadius, pill)

        // If completely collapsed and alpha faded out, no internal content to draw
        if (curCompactAlpha <= 0.01f && curExpandedAlpha <= 0.01f) return

        val pad = dp(14f)
        val dot = dp(4f)

        // Accent indicator color based on content type
        val accentColor = when (content.type) {
            IslandContentType.CALL -> Color.parseColor("#34C759")
            IslandContentType.TIMER -> Color.parseColor("#FF9500")
            IslandContentType.MEDIA -> Color.parseColor("#1DB954")
            IslandContentType.GENERIC_NOTIFICATION -> Color.parseColor("#AF52DE")
            IslandContentType.NONE -> Color.parseColor("#0A84FF")
        }
        accent.color = accentColor

        // Draw indicator dot
        val dotAlpha = (curCompactAlpha * 255).toInt().coerceIn(0, 255)
        accent.alpha = dotAlpha
        canvas.drawCircle(rect.left + pad + dot, rect.centerY(), dot, accent)

        val x = rect.left + pad * 2 + dot * 2
        val maxAvailableWidth = (rect.width() - pad * 3 - dot * 2 - dp(28f)).coerceAtLeast(0f)

        // Interpolate title Y position from compact vertical center to expanded header position
        val compactY = rect.centerY() + text.textSize / 3f
        val expandedY = rect.top + pad + text.textSize
        val currentTextY = lerp(compactY, expandedY, curExpandedAlpha)

        val title = content.title.ifBlank { label(content.type) }
        text.alpha = (curCompactAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawText(elide(title, text, maxAvailableWidth), x, currentTextY, text)

        // Equalizer waveform bars in COMPACT mode for Media
        if (state == IslandState.COMPACT && content.type == IslandContentType.MEDIA) {
            audioWavePaint.alpha = (curCompactAlpha * 240).toInt().coerceIn(0, 240)
            val waveX = rect.right - pad - dp(4f)
            val h1 = if (content.isPlaying) dp(4f) else dp(2f)
            val h2 = if (content.isPlaying) dp(9f) else dp(3f)
            val h3 = if (content.isPlaying) dp(6f) else dp(2f)
            val sp = dp(3.5f)
            canvas.drawLine(waveX - sp * 2, rect.centerY() - h1, waveX - sp * 2, rect.centerY() + h1, audioWavePaint)
            canvas.drawLine(waveX - sp, rect.centerY() - h2, waveX - sp, rect.centerY() + h2, audioWavePaint)
            canvas.drawLine(waveX, rect.centerY() - h3, waveX, rect.centerY() + h3, audioWavePaint)
        }

        // Dismiss button ("✕") in expanded mode
        if (curExpandedAlpha > 0.05f) {
            val btnRadius = dp(11f)
            val btnX = rect.right - pad - btnRadius
            val btnY = rect.top + pad + btnRadius

            // 48dp minimum accessible touch target hit area
            val hitPadding = dp(14f)
            dismissHitRect.set(
                btnX - btnRadius - hitPadding,
                btnY - btnRadius - hitPadding,
                btnX + btnRadius + hitPadding,
                btnY + btnRadius + hitPadding
            )

            // Draw button background circle
            dismissBtnBg.alpha = (curExpandedAlpha * 55).toInt().coerceIn(0, 55)
            canvas.drawCircle(btnX, btnY, btnRadius, dismissBtnBg)

            // Draw "✕" icon
            dismissIconPaint.alpha = (curExpandedAlpha * 220).toInt().coerceIn(0, 220)
            val cross = dp(3.8f)
            canvas.drawLine(btnX - cross, btnY - cross, btnX + cross, btnY + cross, dismissIconPaint)
            canvas.drawLine(btnX + cross, btnY - cross, btnX - cross, btnY + cross, dismissIconPaint)

            // App badge beside dismiss button
            if (content.appName.isNotBlank()) {
                val tagAlpha = (curExpandedAlpha * 150).toInt().coerceIn(0, 150)
                tagText.alpha = tagAlpha
                val tag = content.appName.uppercase()
                val tagW = tagText.measureText(tag)
                val tagRight = btnX - btnRadius - dp(8f)
                if (tagRight - tagW > x + dp(50f)) {
                    canvas.drawText(tag, tagRight - tagW, expandedY, tagText)
                }
            }
        } else {
            dismissHitRect.setEmpty()
        }

        // Subtitle fades in smoothly during expansion
        if (curExpandedAlpha > 0.05f && content.subtitle.isNotBlank()) {
            val subAlpha = (curExpandedAlpha * 180).toInt().coerceIn(0, 180)
            subText.alpha = subAlpha
            val subY = expandedY + dp(18f)
            canvas.drawText(elide(content.subtitle, subText, maxAvailableWidth), x, subY, subText)
        }

        // Progress bar for media or timers
        content.progress?.let { p ->
            if (curExpandedAlpha > 0.05f) {
                val pAlpha = (curExpandedAlpha * 255).toInt().coerceIn(0, 255)
                val bgAlpha = (curExpandedAlpha * 70).toInt().coerceIn(0, 70)
                progressBg.alpha = bgAlpha
                accent.alpha = pAlpha

                val y = if (content.type == IslandContentType.MEDIA) rect.bottom - dp(48f) else rect.bottom - dp(14f)
                val right = rect.right - pad
                if (right > x) {
                    canvas.drawRoundRect(x, y, right, y + dp(3f), dp(2f), dp(2f), progressBg)
                    canvas.drawRoundRect(x, y, x + (right - x) * p.coerceIn(0f, 1f), y + dp(3f), dp(2f), dp(2f), accent)
                }
            }
        }

        // Media Control Buttons (Previous ⏮, Play/Pause ⏯, Next ⏭)
        if (curExpandedAlpha > 0.05f && content.type == IslandContentType.MEDIA) {
            val btnBarY = rect.bottom - dp(24f)
            val centerX = rect.centerX()
            val btnSpacing = dp(58f)

            // 1. Play / Pause Button (center)
            val ppRadius = dp(17f)
            playPauseBtnBgPaint.alpha = (curExpandedAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, btnBarY, ppRadius, playPauseBtnBgPaint)

            playPauseBtnHitRect.set(
                centerX - dp(24f),
                btnBarY - dp(24f),
                centerX + dp(24f),
                btnBarY + dp(24f)
            )

            playPauseIconPaint.alpha = (curExpandedAlpha * 255).toInt().coerceIn(0, 255)
            if (content.isPlaying) {
                // Pause Bars
                val barW = dp(3.5f)
                val barH = dp(11f)
                val gap = dp(4f)
                canvas.drawRoundRect(
                    centerX - gap / 2f - barW,
                    btnBarY - barH / 2f,
                    centerX - gap / 2f,
                    btnBarY + barH / 2f,
                    dp(1.5f), dp(1.5f),
                    playPauseIconPaint
                )
                canvas.drawRoundRect(
                    centerX + gap / 2f,
                    btnBarY - barH / 2f,
                    centerX + gap / 2f + barW,
                    btnBarY + barH / 2f,
                    dp(1.5f), dp(1.5f),
                    playPauseIconPaint
                )
            } else {
                // Play Triangle
                val triPath = Path().apply {
                    moveTo(centerX - dp(4f), btnBarY - dp(6.5f))
                    lineTo(centerX + dp(7f), btnBarY)
                    lineTo(centerX - dp(4f), btnBarY + dp(6.5f))
                    close()
                }
                canvas.drawPath(triPath, playPauseIconPaint)
            }

            // 2. Previous Button (left)
            val prevX = centerX - btnSpacing
            prevBtnHitRect.set(
                prevX - dp(24f),
                btnBarY - dp(24f),
                prevX + dp(24f),
                btnBarY + dp(24f)
            )
            mediaIconPaint.alpha = (curExpandedAlpha * 220).toInt().coerceIn(0, 220)

            // Vertical line on left
            canvas.drawRoundRect(
                prevX - dp(7.5f),
                btnBarY - dp(6f),
                prevX - dp(5.5f),
                btnBarY + dp(6f),
                dp(1f), dp(1f),
                mediaIconPaint
            )
            // Triangle pointing left
            val prevPath = Path().apply {
                moveTo(prevX + dp(6.5f), btnBarY - dp(6.5f))
                lineTo(prevX - dp(4.5f), btnBarY)
                lineTo(prevX + dp(6.5f), btnBarY + dp(6.5f))
                close()
            }
            canvas.drawPath(prevPath, mediaIconPaint)

            // 3. Next Button (right)
            val nextX = centerX + btnSpacing
            nextBtnHitRect.set(
                nextX - dp(24f),
                btnBarY - dp(24f),
                nextX + dp(24f),
                btnBarY + dp(24f)
            )

            // Vertical line on right
            canvas.drawRoundRect(
                nextX + dp(5.5f),
                btnBarY - dp(6f),
                nextX + dp(7.5f),
                btnBarY + dp(6f),
                dp(1f), dp(1f),
                mediaIconPaint
            )
            // Triangle pointing right
            val nextPath = Path().apply {
                moveTo(nextX - dp(6.5f), btnBarY - dp(6.5f))
                lineTo(nextX + dp(4.5f), btnBarY)
                lineTo(nextX - dp(6.5f), btnBarY + dp(6.5f))
                close()
            }
            canvas.drawPath(nextPath, mediaIconPaint)
        } else {
            prevBtnHitRect.setEmpty()
            playPauseBtnHitRect.setEmpty()
            nextBtnHitRect.setEmpty()
        }

        // Action hint for notifications
        if (curExpandedAlpha > 0.1f && content.type == IslandContentType.GENERIC_NOTIFICATION) {
            val hintAlpha = (curExpandedAlpha * 110).toInt().coerceIn(0, 110)
            hintText.alpha = hintAlpha
            canvas.drawText("Tap to open • ✕ or swipe up to dismiss", x, rect.bottom - dp(12f), hintText)
        }
    }

    private fun label(type: IslandContentType) = when (type) {
        IslandContentType.MEDIA -> "Now Playing"
        IslandContentType.CALL -> "Call"
        IslandContentType.TIMER -> "Timer"
        IslandContentType.GENERIC_NOTIFICATION -> if (content.appName.isNotBlank()) content.appName else "Notification"
        IslandContentType.NONE -> ""
    }

    private fun elide(value: String, paint: Paint, max: Float): String {
        if (max <= 0f) return ""
        if (paint.measureText(value) <= max) return value
        var end = value.length
        while (end > 0 && paint.measureText(value.substring(0, end) + "…") > max) end--
        return value.substring(0, end) + "…"
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAutoCollapse()
        listOf(widthSpring, heightSpring, radiusSpring, expandedAlphaSpring, compactAlphaSpring).forEach { it.cancel() }
        tracker?.recycle()
        tracker = null
    }
}