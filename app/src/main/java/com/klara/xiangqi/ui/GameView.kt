package com.klara.xiangqi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.klara.xiangqi.game.BOARD_SIZE
import com.klara.xiangqi.game.Difficulty
import com.klara.xiangqi.game.Game
import com.klara.xiangqi.game.Kind
import com.klara.xiangqi.game.MoveGen
import com.klara.xiangqi.game.Side
import com.klara.xiangqi.game.Ai
import com.klara.xiangqi.game.colOf
import com.klara.xiangqi.game.glyphOf
import com.klara.xiangqi.game.kindOf
import com.klara.xiangqi.game.rowOf
import com.klara.xiangqi.game.sideOf
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * 整个界面用一块 Canvas 绘制：主菜单 + 棋盘 + 对局信息，避免 Fragment/Compose 依赖。
 */
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, GAME }
    private enum class Mode { AI, PVP }

    // ---------- 状态 ----------
    private val game = Game()
    private val ai = Ai()
    private var screen = Screen.MENU
    private var mode = Mode.AI
    private var difficulty = Difficulty.NORMAL

    private var selected = -1
    private var targets = IntArray(0)
    private var dragging = false
    private var pressing = false
    private var pressSq = -1
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f

    private var thinking = false
    private var animating = false
    private var animFrom = -1
    private var animTo = -1
    private var animStartAt = 0L
    private var finishedAt = 0L
    private var generation = 0

    // ---------- 几何 ----------
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var topInset = 0
    private var bottomInset = 0
    private var cell = 0f
    private var boardLeft = 0f
    private var boardTop = 0f
    private var topBarH = 0f
    private var bottomBarH = 0f
    private var logTop = 0f
    private var logBottom = 0f
    /** 双人模式的棋谱弹层是否展开（人机模式始终显示棋谱，不用这个） */
    private var logVisible = false

    // ---------- 绘制资源 ----------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    /** 霞鹜文楷（OFL）：棋子、标题用的楷体 */
    private val kai: Typeface by lazy {
        try {
            Typeface.createFromAsset(context.assets, "font/kai.ttf")
        } catch (e: Exception) {
            Typeface.create("serif", Typeface.NORMAL)
        }
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = kai
    }
    /** 版本号，从 PackageManager 读取（与 build.gradle.kts 保持一致） */
    private val versionLabel: String by lazy {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "v" + (pi.versionName ?: "")
        } catch (e: Exception) {
            ""
        }
    }
    private val tmpRect = RectF()
    private val tmpRect2 = RectF()
    private val inkRect = Rect()

    private var bgShader: Shader? = null
    private var panelShader: Shader? = null
    private val pieceBitmaps = arrayOfNulls<Bitmap>(16)
    private var pieceBmpReady = false

    private val menuAiRect = RectF()
    private val menuPvpRect = RectF()
    private val diffRects = Array(Difficulty.entries.size) { RectF() }
    private val backRect = RectF()
    private val undoRect = RectF()
    private val restartRect = RectF()
    private val confirmCancelRect = RectF()
    private val confirmOkRect = RectF()
    private val againRect = RectF()
    private val toMenuRect = RectF()
    private val logToggleRect = RectF()
    private val logCardRect = RectF()

    /** 「退出对局？」二次确认是否打开 */
    private var confirmExit = false

    private val prefs = context.getSharedPreferences("xiangqi", Context.MODE_PRIVATE)

    init {
        difficulty = Difficulty.fromName(prefs.getString("difficulty", null))
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ============================================================
    // 生命周期 / 几何
    // ============================================================

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                // 状态栏已隐藏：顶部只留刘海高度（用 IgnoringVisibility 保证下拉临时唤出状态栏时布局不跳）
                topInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).top
                bottomInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            updateGeometry()
            invalidate()
            insets
        }
        ensurePieceBitmaps()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    private fun updateGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        topBarH = dp(54f)
        bottomBarH = dp(38f)

        val areaTop = topInset + topBarH
        val areaBottom = h - bottomInset - bottomBarH
        val availW = w - dp(40f)
        val availH = areaBottom - areaTop

        // 棋盘按宽度铺满；上下各留一条吃子行，下方再给棋谱面板留位置
        if (mode == Mode.PVP) {
            // 双人模式：棋谱收起，棋盘（含上下吃子行）在顶栏和状态条之间垂直居中
            val maxCellByHeight = (availH - dp(12f)) / 12.72f
            cell = min(availW / 9f, maxCellByHeight).coerceAtLeast(dp(16f))
            boardLeft = (w - cell * 8f) / 2f
            boardTop = areaTop + (availH - cell * 10.24f) / 2f + cell * 0.62f
            logTop = boardTop
            logBottom = boardTop
        } else {
            val maxCellByHeight = (availH - dp(168f)) / 11.1f
            cell = min(availW / 9f, maxCellByHeight).coerceAtLeast(dp(16f))
            boardLeft = (w - cell * 8f) / 2f
            // 上方吃子行（0.62 格）+ 棋盘木边（0.62 格）
            boardTop = areaTop + cell * 1.24f + dp(4f)

            logTop = boardTop + cell * 9f + cell * 1.24f + dp(6f)
            logBottom = areaBottom - dp(10f)
        }

        bgShader = LinearGradient(
            0f, 0f, w * 0.35f, h,
            intArrayOf(C_BG_TOP, C_BG_MID, C_BG_BOTTOM),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        if (cell > 0f) {
            panelShader = LinearGradient(
                0f, boardTop - cell, 0f, boardTop + cell * 10f,
                C_WOOD_A, C_WOOD_B, Shader.TileMode.CLAMP
            )
        }
    }

    private fun dp(v: Float): Float = v * density

    private fun cx(col: Int): Float = boardLeft + col * cell

    private fun cy(row: Int): Float = boardTop + row * cell

    // ============================================================
    // 触控
    // ============================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                if (screen == Screen.MENU) return true
                if (confirmExit) return true
                if (logVisible) return true
                if (game.finished) return true
                if (thinking) return true
                val sq = squareAt(x, y, 0.62f)
                if (sq >= 0 && game.isOwnPiece(sq)) {
                    selected = sq
                    targets = game.legalTargets(sq)
                    pressSq = sq
                    pressing = true
                    dragging = false
                    dragX = x
                    dragY = y
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressing) {
                    if (!dragging) {
                        val dx = x - downX
                        val dy = y - downY
                        if (dx * dx + dy * dy > touchSlop * touchSlop) dragging = true
                    }
                    if (dragging) {
                        dragX = x
                        dragY = y
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (screen == Screen.MENU) {
                    handleMenuTap(x, y)
                    return true
                }
                if (confirmExit) {
                    if (confirmCancelRect.contains(x, y)) {
                        confirmExit = false
                        invalidate()
                    } else if (confirmOkRect.contains(x, y)) {
                        confirmExit = false
                        goMenu()
                    }
                    return true
                }
                if (mode == Mode.PVP && logToggleRect.contains(x, y)) {
                    logVisible = !logVisible
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                if (logVisible) {
                    // 弹层打开时，点面板以外的地方收起
                    if (!logCardRect.contains(x, y)) {
                        logVisible = false
                        invalidate()
                    }
                    return true
                }
                if (game.finished) {
                    when {
                        againRect.contains(x, y) -> startNewGame(mode)
                        toMenuRect.contains(x, y) -> goMenu()
                        backRect.contains(x, y) -> goMenu()
                    }
                    return true
                }
                if (thinking) return true

                if (pressing) {
                    val wasDragging = dragging
                    pressing = false
                    dragging = false
                    val upSq = squareAt(x, y, 0.90f)
                    if (upSq >= 0 && upSq != pressSq && targets.contains(upSq)) {
                        applyMove(pressSq, upSq)
                    } else if (wasDragging) {
                        selected = -1
                        targets = IntArray(0)
                        invalidate()
                    } else {
                        invalidate()
                    }
                    return true
                }

                if (backRect.contains(x, y)) {
                    requestExitGame()
                    return true
                }
                if (restartRect.contains(x, y)) {
                    startNewGame(mode)
                    return true
                }
                if (undoRect.contains(x, y)) {
                    undo()
                    return true
                }

                val sq = squareAt(x, y, 0.62f)
                if (sq < 0) {
                    if (selected != -1) {
                        selected = -1
                        targets = IntArray(0)
                        invalidate()
                    }
                    return true
                }
                val inTargets = targets.contains(sq)
                if (selected != -1 && inTargets) {
                    applyMove(selected, sq)
                } else if (game.isOwnPiece(sq)) {
                    selected = sq
                    targets = game.legalTargets(sq)
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                } else {
                    selected = -1
                    targets = IntArray(0)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressing = false
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleMenuTap(x: Float, y: Float) {
        if (menuAiRect.contains(x, y)) {
            startNewGame(Mode.AI)
        } else if (menuPvpRect.contains(x, y)) {
            startNewGame(Mode.PVP)
        } else {
            Difficulty.entries.forEachIndexed { i, d ->
                if (diffRects[i].contains(x, y)) {
                    difficulty = d
                    prefs.edit().putString("difficulty", d.name).apply()
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
            }
        }
    }

    private fun squareAt(x: Float, y: Float, tol: Float): Int {
        if (cell <= 0f) return -1
        val c = round((x - boardLeft) / cell).toInt()
        val r = round((y - boardTop) / cell).toInt()
        if (c < 0 || c > 8 || r < 0 || r > 9) return -1
        val dx = x - cx(c)
        val dy = y - cy(r)
        val limit = cell * tol
        if (dx * dx + dy * dy > limit * limit) return -1
        return r * 9 + c
    }

    // ============================================================
    // 对局控制
    // ============================================================

    fun handleBack(): Boolean {
        return when {
            screen == Screen.GAME -> {
                if (confirmExit) {
                    confirmExit = false
                    invalidate()
                } else if (logVisible) {
                    // 棋谱弹层开着时，返回键先收起它
                    logVisible = false
                    invalidate()
                } else {
                    requestExitGame()
                }
                true
            }
            else -> false
        }
    }

    /** 回菜单；对局进行中先确认，避免误触丢掉棋局 */
    private fun requestExitGame() {
        if (game.plyCount > 0 && !game.finished) {
            confirmExit = true
            invalidate()
        } else {
            goMenu()
        }
    }

    private fun goMenu() {
        generation++
        thinking = false
        logVisible = false
        confirmExit = false
        screen = Screen.MENU
        selected = -1
        targets = IntArray(0)
        dragging = false
        pressing = false
        animating = false
        invalidate()
    }

    private fun startNewGame(m: Mode) {
        generation++
        mode = m
        game.reset()
        selected = -1
        targets = IntArray(0)
        dragging = false
        pressing = false
        thinking = false
        logVisible = false
        confirmExit = false
        animating = false
        animStartAt = 0L
        finishedAt = 0L
        screen = Screen.GAME
        // 双人 / 人机的棋盘布局不同（居中 vs 给棋谱留位置），换模式要重算几何
        updateGeometry()
        invalidate()
    }

    private fun applyMove(from: Int, to: Int) {
        if (!game.play(from, to)) return
        selected = -1
        targets = IntArray(0)
        startAnimation(from, to)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        if (game.finished) {
            finishedAt = SystemClock.uptimeMillis()
            // 等动画走完再弹出结算
            postDelayed({ invalidate() }, 420L)
        } else if (mode == Mode.AI && game.sideToMove == Side.BLACK) {
            startThinking()
        }
        invalidate()
    }

    private fun startAnimation(from: Int, to: Int) {
        animFrom = from
        animTo = to
        animStartAt = SystemClock.uptimeMillis()
        animating = true
    }

    private fun undo() {
        if (thinking) return
        generation++
        thinking = false
        // 人机模式下退回到自己该走棋的局面
        val steps = if (mode == Mode.AI && game.plyCount >= 2) 2 else 1
        repeat(steps) {
            if (game.canUndo()) game.undo()
        }
        selected = -1
        targets = IntArray(0)
        animating = false
        finishedAt = 0L
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun startThinking() {
        if (thinking) return
        thinking = true
        val gen = generation
        val snapshot = game.board.copy()
        val diff = difficulty
        postInvalidateOnAnimation()
        Thread {
            val t0 = SystemClock.uptimeMillis()
            val move = ai.chooseMove(snapshot, diff)
            val elapsed = SystemClock.uptimeMillis() - t0
            android.util.Log.d(
                "XiangqiAI",
                "level=${diff.name} depth=${ai.lastDepth} nodes=${ai.lastNodes} elapsed=${elapsed}ms move=$move"
            )
            val delay = (340L - elapsed).coerceAtLeast(0L)
            postDelayed({
                if (gen != generation) return@postDelayed
                thinking = false
                if (move != 0 && !game.finished && game.sideToMove == Side.BLACK) {
                    applyMove(MoveGen.fromOf(move), MoveGen.toOf(move))
                }
                invalidate()
            }, delay)
        }.apply { isDaemon = true }.start()
    }

    private fun haptic(kind: Int) {
        try {
            performHapticFeedback(kind)
        } catch (_: Exception) {
        }
    }

    // ============================================================
    // 绘制入口
    // ============================================================

    override fun onDraw(canvas: Canvas) {
        paint.alpha = 255
        drawBackground(canvas)
        if (screen == Screen.MENU) {
            drawMenu(canvas)
        } else {
            drawGame(canvas)
        }
        if (animating || thinking || game.inCheck) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawBackground(canvas: Canvas) {
        paint.shader = bgShader
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // 顶部暖光
        paint.shader = RadialGradient(
            width * 0.5f, height * 0.12f, width * 0.85f,
            intArrayOf(0x33FFC978, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    // ============================================================
    // 主菜单
    // ============================================================

    private fun drawMenu(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val usable = h - topInset - bottomInset
        var y = topInset + ((usable - dp(505f)) * 0.46f).coerceAtLeast(dp(18f))

        // 棋子徽标
        val emblemR = dp(42f)
        y += emblemR
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            centerX - emblemR * 0.3f, y - emblemR * 0.35f, emblemR * 1.6f,
            intArrayOf(0xFFFFF6E0.toInt(), 0xFFF0D8AC.toInt(), 0xFFD6B077.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, y, emblemR, paint)
        paint.shader = null
        linePaint.color = 0xFFA87944.toInt()
        linePaint.strokeWidth = dp(2f)
        canvas.drawCircle(centerX, y, emblemR, linePaint)
        linePaint.color = 0x88B01E24.toInt()
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(centerX, y, emblemR * 0.78f, linePaint)

        textPaint.color = C_RED
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = kai
        textPaint.textSize = emblemR * 1.20f
        val fm = textPaint.fontMetrics
        canvas.drawText("帥", centerX, y - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        // 标题
        y += emblemR + dp(56f)
        titlePaint.color = C_GOLD
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = dp(44f)
        titlePaint.letterSpacing = 0.16f
        canvas.drawText("中国象棋", centerX, y, titlePaint)
        titlePaint.letterSpacing = 0f

        y += dp(26f)
        textPaint.textSize = dp(10.5f)
        textPaint.color = C_TEXT_DIM
        textPaint.letterSpacing = 0.42f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("XIANGQI  ·  楚河汉界", centerX, y, textPaint)
        textPaint.letterSpacing = 0f

        // 按钮
        val btnW = min(w - dp(64f), dp(300f))
        val btnH = dp(56f)
        val left = centerX - btnW / 2f
        y += dp(62f)

        menuAiRect.set(left, y, left + btnW, y + btnH)
        drawPrimaryButton(canvas, menuAiRect, "人机对战", "与电脑对弈")
        y += btnH + dp(14f)

        menuPvpRect.set(left, y, left + btnW, y + btnH)
        drawSecondaryButton(canvas, menuPvpRect, "双人对弈", "同屏轮流走子")
        y += btnH + dp(34f)

        // 难度：分段控件
        textPaint.textSize = dp(11f)
        textPaint.color = C_TEXT_DIM
        textPaint.letterSpacing = 0.24f
        canvas.drawText("电脑强度", centerX, y, textPaint)
        textPaint.letterSpacing = 0f
        y += dp(16f)

        val segH = dp(40f)
        val segLeft = centerX - btnW / 2f
        val segRight = centerX + btnW / 2f

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0x12FFFFFF
        tmpRect.set(segLeft, y, segRight, y + segH)
        canvas.drawRoundRect(tmpRect, segH / 2f, segH / 2f, paint)
        linePaint.color = 0x22E2BE79
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(tmpRect, segH / 2f, segH / 2f, linePaint)

        val count = Difficulty.entries.size
        val pad = dp(3.5f)
        val itemW = (btnW - pad * 2) / count
        val itemH = segH - pad * 2
        Difficulty.entries.forEachIndexed { i, d ->
            val l = segLeft + pad + itemW * i
            val rect = diffRects[i]
            rect.set(l, y + pad, l + itemW, y + pad + itemH)
            if (d == difficulty) {
                paint.color = C_GOLD
                canvas.drawRoundRect(rect, itemH / 2f, itemH / 2f, paint)
            }
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(12f)
            textPaint.color = if (d == difficulty) 0xFF2A1E14.toInt() else C_TEXT_DIM
            val f = textPaint.fontMetrics
            canvas.drawText(
                d.label,
                rect.centerX(),
                rect.centerY() - (f.ascent + f.descent) / 2f,
                textPaint
            )
        }
        y += segH

        // 底部提示
        textPaint.textSize = dp(11f)
        textPaint.color = 0x66B7A489
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("点击棋子选中，再点目标格落子 · 支持拖动", centerX, h - bottomInset - dp(28f), textPaint)

        // 版本号（角落小字）
        val ver = versionLabel
        if (ver.isNotEmpty()) {
            textPaint.textSize = dp(9f)
            textPaint.color = 0x4DB7A489
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(ver, w - dp(18f), h - bottomInset - dp(10f), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    private fun drawPrimaryButton(canvas: Canvas, r: RectF, title: String, sub: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0x33E2BE79
        tmpRect.set(r)
        canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), paint)
        paint.color = C_GOLD
        tmpRect.inset(dp(1.5f), dp(1.5f))
        canvas.drawRoundRect(tmpRect, dp(17f), dp(17f), paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0xFF2A1E14.toInt()
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(18f)
        val fm = textPaint.fontMetrics
        val cyc = r.centerY() - dp(7f)
        canvas.drawText(title, r.centerX(), cyc - (fm.ascent + fm.descent) / 2f, textPaint)

        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = dp(10.5f)
        textPaint.color = 0xAA3A2A16.toInt()
        canvas.drawText(sub, r.centerX(), r.centerY() + dp(15f), textPaint)
    }

    private fun drawSecondaryButton(canvas: Canvas, r: RectF, title: String, sub: String) {
        paint.style = Paint.Style.FILL
        paint.color = 0x10FFFFFF
        tmpRect.set(r)
        canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), paint)
        linePaint.color = 0x55E2BE79
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), linePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = C_GOLD
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(18f)
        val fm = textPaint.fontMetrics
        val cyc = r.centerY() - dp(7f)
        canvas.drawText(title, r.centerX(), cyc - (fm.ascent + fm.descent) / 2f, textPaint)

        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = dp(10.5f)
        textPaint.color = 0x88B7A489.toInt()
        canvas.drawText(sub, r.centerX(), r.centerY() + dp(15f), textPaint)
    }

    // ============================================================
    // 对局界面
    // ============================================================

    private fun drawGame(canvas: Canvas) {
        drawTopBar(canvas)
        drawBoard(canvas)
        drawPieces(canvas)
        drawCapturedRows(canvas)
        drawLogPanel(canvas)
        drawBottomBar(canvas)
        if (game.finished && SystemClock.uptimeMillis() - finishedAt > 380) {
            drawResult(canvas)
        } else if (confirmExit) {
            drawConfirm(canvas)
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        val w = width.toFloat()
        val barTop = topInset + dp(12f)
        val barH = dp(36f)

        backRect.set(dp(12f), barTop, dp(12f) + dp(56f), barTop + barH)
        drawPill(canvas, backRect, "‹ 菜单", true)

        restartRect.set(w - dp(12f) - dp(56f), barTop, w - dp(12f), barTop + barH)
        drawPill(canvas, restartRect, "重开", true)

        undoRect.set(
            restartRect.left - dp(8f) - dp(56f), barTop,
            restartRect.left - dp(8f), barTop + barH
        )
        drawPill(canvas, undoRect, "悔棋", game.canUndo() && !thinking && !confirmExit)

        val title = when (mode) {
            Mode.AI -> "人机对战 · ${difficulty.label}"
            Mode.PVP -> "双人对弈"
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = C_GOLD
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(14f)
        val fm = textPaint.fontMetrics
        // 双人模式在「菜单」右边多一个棋谱开关
        var titleLeft = backRect.right
        if (mode == Mode.PVP) {
            logToggleRect.set(
                backRect.right + dp(8f), barTop,
                backRect.right + dp(8f) + dp(56f), barTop + barH
            )
            drawPill(canvas, logToggleRect, if (logVisible) "收起" else "棋谱", true)
            titleLeft = logToggleRect.right
        }
        val titleCx = (titleLeft + undoRect.left) / 2f
        canvas.drawText(title, titleCx, barTop + barH / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun drawPill(canvas: Canvas, r: RectF, label: String, enabled: Boolean) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = if (enabled) 0x24FFFFFF else 0x10FFFFFF
        tmpRect.set(r)
        canvas.drawRoundRect(tmpRect, r.height() / 2f, r.height() / 2f, paint)
        if (enabled) {
            linePaint.color = 0x33E2BE79
            linePaint.strokeWidth = dp(0.8f)
            canvas.drawRoundRect(tmpRect, r.height() / 2f, r.height() / 2f, linePaint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(12.5f)
        textPaint.color = if (enabled) 0xFFE2BE79.toInt() else 0x66B7A489
        val fm = textPaint.fontMetrics
        canvas.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    private fun drawBoard(canvas: Canvas) {
        val pad = cell * 0.62f
        val left = boardLeft - pad
        val top = boardTop - pad
        val right = boardLeft + cell * 8f + pad
        val bottom = boardTop + cell * 9f + pad

        // 柔和外阴影
        for (i in 3 downTo 1) {
            val o = dp(2.5f) * i
            paint.style = Paint.Style.FILL
            paint.color = 0x22000000
            tmpRect.set(left - o * 0.4f, top - o * 0.4f + o, right + o * 0.4f, bottom + o * 0.4f + o)
            canvas.drawRoundRect(tmpRect, dp(10f) + o, dp(10f) + o, paint)
        }

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.shader = panelShader
        tmpRect.set(left, top, right, bottom)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), paint)
        paint.shader = null

        // 木纹高光
        paint.alpha = 255
        paint.shader = LinearGradient(
            left, top, left, bottom,
            intArrayOf(0x22FFFFFF, 0x00FFFFFF, 0x11000000),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), paint)
        paint.shader = null

        // 外框
        linePaint.color = 0xAA7A5230.toInt()
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(tmpRect, dp(10f), dp(10f), linePaint)

        drawGrid(canvas)
        if (game.lastMove >= 0) drawLastMove(canvas, game.lastMove)
        drawTargets(canvas)
        drawSelectionRing(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        linePaint.color = 0xCC8A5F35.toInt()
        linePaint.strokeWidth = dp(1.05f)

        for (r in 0..9) {
            canvas.drawLine(cx(0), cy(r), cx(8), cy(r), linePaint)
        }
        canvas.drawLine(cx(0), cy(0), cx(0), cy(9), linePaint)
        canvas.drawLine(cx(8), cy(0), cx(8), cy(9), linePaint)
        for (c in 1..7) {
            canvas.drawLine(cx(c), cy(0), cx(c), cy(4), linePaint)
            canvas.drawLine(cx(c), cy(5), cx(c), cy(9), linePaint)
        }

        // 九宫
        linePaint.strokeWidth = dp(0.9f)
        canvas.drawLine(cx(3), cy(0), cx(5), cy(2), linePaint)
        canvas.drawLine(cx(5), cy(0), cx(3), cy(2), linePaint)
        canvas.drawLine(cx(3), cy(7), cx(5), cy(9), linePaint)
        canvas.drawLine(cx(5), cy(7), cx(3), cy(9), linePaint)

        // 炮位与兵位标记
        mark(canvas, 2, 1); mark(canvas, 2, 7); mark(canvas, 7, 1); mark(canvas, 7, 7)
        for (c in intArrayOf(0, 2, 4, 6, 8)) {
            mark(canvas, 3, c)
            mark(canvas, 6, c)
        }

        // 楚河汉界：四个字保持在原来的一行位置，各自在原地旋转——
        // 楚 / 河逆时针 90°，漢 / 界顺时针 90°。不整串转，也不改成竖排（那样会挤成一列）。
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = 0x998A5F35.toInt()
        textPaint.typeface = kai
        textPaint.textSize = cell * 0.40f
        textPaint.letterSpacing = 0f
        val riverY = cy(4) + cell / 2f
        val fm = textPaint.fontMetrics
        val charDx = cell * 0.28f
        fun riverChar(ch: String, x: Float, y: Float, degrees: Float) {
            canvas.save()
            canvas.rotate(degrees, x, y)
            canvas.drawText(ch, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
            canvas.restore()
        }
        riverChar("楚", cx(2) - charDx, riverY, -90f)
        riverChar("河", cx(2) + charDx, riverY, -90f)
        riverChar("漢", cx(6) - charDx, riverY, 90f)
        riverChar("界", cx(6) + charDx, riverY, 90f)
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun mark(canvas: Canvas, r: Int, c: Int) {
        val x = cx(c)
        val y = cy(r)
        val d = cell * 0.10f
        val len = cell * 0.16f
        linePaint.strokeWidth = dp(0.9f)
        linePaint.color = 0xAA8A5F35.toInt()
        for (sx in intArrayOf(-1, 1)) {
            if (sx < 0 && c == 0) continue
            if (sx > 0 && c == 8) continue
            for (sy in intArrayOf(-1, 1)) {
                val px = x + sx * d
                val py = y + sy * d
                canvas.drawLine(px, py, px + sx * len, py, linePaint)
                canvas.drawLine(px, py, px, py + sy * len, linePaint)
            }
        }
    }

    private fun drawLastMove(canvas: Canvas, move: Int) {
        val from = MoveGen.fromOf(move)
        val to = MoveGen.toOf(move)
        paint.style = Paint.Style.FILL
        paint.color = 0x2EE2BE79
        for (sq in intArrayOf(from, to)) {
            val x = cx(colOf(sq))
            val y = cy(rowOf(sq))
            tmpRect.set(x - cell * 0.46f, y - cell * 0.46f, x + cell * 0.46f, y + cell * 0.46f)
            canvas.drawRoundRect(tmpRect, cell * 0.14f, cell * 0.14f, paint)
        }
        linePaint.color = 0x66E2BE79
        linePaint.strokeWidth = dp(1.3f)
        val x = cx(colOf(to))
        val y = cy(rowOf(to))
        tmpRect.set(x - cell * 0.46f, y - cell * 0.46f, x + cell * 0.46f, y + cell * 0.46f)
        canvas.drawRoundRect(tmpRect, cell * 0.14f, cell * 0.14f, linePaint)
    }

    private fun drawTargets(canvas: Canvas) {
        if (targets.isEmpty()) return
        for (sq in targets) {
            val x = cx(colOf(sq))
            val y = cy(rowOf(sq))
            val occupied = game.board.cells[sq] != 0
            if (occupied) {
                linePaint.color = 0x882E7D4F.toInt()
                linePaint.strokeWidth = dp(3f)
                canvas.drawCircle(x, y, cell * 0.46f, linePaint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = 0x772E7D4F
                canvas.drawCircle(x, y, cell * 0.11f, paint)
                paint.color = 0x332E7D4F
                canvas.drawCircle(x, y, cell * 0.17f, paint)
            }
        }
    }

    private fun drawSelectionRing(canvas: Canvas) {
        if (selected < 0) return
        val x = cx(colOf(selected))
        val y = cy(rowOf(selected))
        linePaint.color = 0xCCFFD98A.toInt()
        linePaint.strokeWidth = dp(2.4f)
        canvas.drawCircle(x, y, cell * 0.47f, linePaint)
        if (dragging) {
            linePaint.color = 0x55FFD98A
            linePaint.strokeWidth = dp(1.4f)
            canvas.drawCircle(x, y, cell * 0.40f, linePaint)
        }
    }

    private fun drawPieces(canvas: Canvas) {
        paint.alpha = 255
        val size = cell * 0.92f
        for (sq in 0 until BOARD_SIZE) {
            val p = game.board.cells[sq]
            if (p == 0) continue
            if (animating && sq == animTo) continue
            if (dragging && sq == selected) continue
            val x = cx(colOf(sq))
            val y = cy(rowOf(sq))
            drawPieceAt(canvas, p, x, y, size)
        }

        // 走子动画
        if (animating) {
            val t = ((SystemClock.uptimeMillis() - animStartAt) / 190f).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            val fx = cx(colOf(animFrom))
            val fy = cy(rowOf(animFrom))
            val tx = cx(colOf(animTo))
            val ty = cy(rowOf(animTo))
            val p = game.board.cells[animTo]
            if (p != 0) {
                drawPieceAt(canvas, p, fx + (tx - fx) * e, fy + (ty - fy) * e, size)
            }
            if (t >= 1f) animating = false
        }

        // 拖动中的棋子
        if (dragging && selected >= 0) {
            val p = game.board.cells[selected]
            if (p != 0) drawPieceAt(canvas, p, dragX, dragY - dp(14f), size * 1.08f)
        }

        // 将军提示
        if (game.inCheck && !game.finished) {
            val k = game.kingSquare(game.sideToMove)
            if (k >= 0) {
                val t = (SystemClock.uptimeMillis() % 1000L) / 1000f
                val pulse = 1f + 0.10f * sin(t * Math.PI * 2).toFloat()
                linePaint.color = 0xCCD32F2F.toInt()
                linePaint.strokeWidth = dp(2.6f)
                canvas.drawCircle(cx(colOf(k)), cy(rowOf(k)), cell * 0.50f * pulse, linePaint)
            }
        }
    }

    private fun drawPieceAt(canvas: Canvas, piece: Int, x: Float, y: Float, size: Float) {
        val bmp = pieceBitmaps[piece] ?: return
        val h = size / 2f
        tmpRect2.set(x - h, y - h, x + h, y + h)
        // 双人对弈时两人对坐，黑方棋子转 180°，让对面的人正着看
        if (mode == Mode.PVP && sideOf(piece) == Side.BLACK) {
            canvas.save()
            canvas.rotate(180f, x, y)
            canvas.drawBitmap(bmp, null, tmpRect2, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, null, tmpRect2, paint)
        }
    }

    /**
     * 吃子行：黑方吃掉的子画在棋盘上方，红方吃掉的子画在棋盘下方。
     * 双人模式对坐，上方那一行整体转 180°，让黑方看着是正的。
     */
    private fun drawCapturedRows(canvas: Canvas) {
        val above = boardTop - cell * 0.62f - cell * 0.31f
        val below = boardTop + cell * 9f + cell * 0.62f + cell * 0.31f
        drawCapturedRow(canvas, game.capturedPieces(Side.BLACK), above, mode == Mode.PVP, "黑方吃子")
        drawCapturedRow(canvas, game.capturedPieces(Side.RED), below, false, "红方吃子")
    }

    private fun drawCapturedRow(
        canvas: Canvas,
        pieces: IntArray,
        y: Float,
        flip: Boolean,
        label: String
    ) {
        if (pieces.isEmpty()) return
        val w = width.toFloat()

        canvas.save()
        if (flip) canvas.rotate(180f, w / 2f, y)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = dp(9.5f)
        textPaint.color = 0x59B7A489
        val fm = textPaint.fontMetrics
        canvas.drawText(label, dp(18f), y - (fm.ascent + fm.descent) / 2f, textPaint)

        // 最多 15 个（将不会被吃），一排放得下
        val size = cell * 0.52f
        val gap = cell * 0.56f
        val regionLeft = dp(18f) + textPaint.measureText(label) + dp(10f)
        val regionRight = w - dp(18f)
        var x = (regionLeft + regionRight) / 2f - gap * (pieces.size - 1) / 2f

        paint.alpha = 255
        for (p in pieces) {
            val bmp = pieceBitmaps[p] ?: continue
            val h = size / 2f
            tmpRect2.set(x - h, y - h, x + h, y + h)
            canvas.drawBitmap(bmp, null, tmpRect2, paint)
            x += gap
        }
        canvas.restore()
    }

    private fun drawLogPanel(canvas: Canvas) {
        if (mode == Mode.PVP) {
            drawLogOverlay(canvas)
        } else {
            drawLogCard(
                canvas, dp(16f), logTop, width - dp(16f), logBottom,
                translucent = true, footer = null
            )
        }
    }

    /** 双人模式的棋谱弹层：半透明卡片盖在棋盘上，点空白处收起 */
    private fun drawLogOverlay(canvas: Canvas) {
        if (!logVisible) return
        val w = width.toFloat()
        val areaTop = topInset + topBarH
        val areaBottom = height - bottomInset - bottomBarH

        // 遮罩只盖棋盘区域，顶栏和底部状态条保持亮着
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0x99000000.toInt()
        canvas.drawRect(0f, areaTop, w, areaBottom, paint)

        val rowH = dp(22f)
        val cardH = min(
            dp(34f) + rowH * 12 + dp(26f),
            areaBottom - areaTop - dp(24f)
        ).coerceAtLeast(dp(60f))
        val top = areaTop + ((areaBottom - areaTop) - cardH) / 2f
        logCardRect.set(dp(16f), top, w - dp(16f), top + cardH)
        drawLogCard(
            canvas, logCardRect.left, logCardRect.top, logCardRect.right, logCardRect.bottom,
            translucent = false, footer = "轻触空白处收起"
        )
    }

    private fun drawLogCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        translucent: Boolean,
        footer: String?
    ) {
        val panelH = bottom - top
        if (panelH < dp(46f)) return

        tmpRect.set(left, top, right, bottom)

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = if (translucent) 0x12FFFFFF else 0xF21B140D.toInt()
        canvas.drawRoundRect(tmpRect, dp(16f), dp(16f), paint)
        linePaint.color = if (translucent) 0x22E2BE79 else 0x55E2BE79
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(tmpRect, dp(16f), dp(16f), linePaint)

        val headerBaseline = top + dp(22f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(11f)
        textPaint.color = 0x99B7A489.toInt()
        textPaint.letterSpacing = 0.16f
        canvas.drawText("棋谱", left + dp(14f), headerBaseline, textPaint)
        textPaint.letterSpacing = 0f
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(11f)
        textPaint.color = 0x66B7A489
        canvas.drawText("共 ${game.moveCount} 步", right - dp(14f), headerBaseline, textPaint)

        if (footer != null) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(10.5f)
            textPaint.color = 0x55B7A489
            canvas.drawText(footer, (left + right) / 2f, bottom - dp(9f), textPaint)
        }

        val rowH = dp(22f)
        val rowsTop = top + dp(34f)
        val rowsBottom = bottom - if (footer != null) dp(24f) else dp(10f)
        val rows = ((rowsBottom - rowsTop) / rowH).toInt()
        if (rows <= 0) return

        val total = game.moveCount
        if (total == 0) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(12.5f)
            textPaint.color = 0x44B7A489
            val cyText = (rowsTop + rowsBottom) / 2f
            val fm0 = textPaint.fontMetrics
            canvas.drawText(
                "走子后在这里记录中文棋谱",
                (left + right) / 2f,
                cyText - (fm0.ascent + fm0.descent) / 2f,
                textPaint
            )
            return
        }
        val totalRows = (total + 1) / 2
        val firstRow = (totalRows - rows).coerceAtLeast(0)

        textPaint.textSize = dp(13f)
        val fm = textPaint.fontMetrics
        val numX = left + dp(16f)
        val redX = left + dp(48f)
        val blackX = left + (right - left) * 0.52f

        for (row in firstRow until totalRows) {
            val y = rowsTop + rowH * (row - firstRow) + rowH / 2f
            val baseline = y - (fm.ascent + fm.descent) / 2f

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = 0x55B7A489
            canvas.drawText("${row + 1}.", numX, baseline, textPaint)

            val i0 = row * 2
            if (i0 < total) {
                textPaint.color = if (i0 == total - 1) C_RED else 0xCCC0392B.toInt()
                canvas.drawText(game.moveAt(i0), redX, baseline, textPaint)
            }
            if (i0 + 1 < total) {
                textPaint.color = if (i0 + 1 == total - 1) 0xFFFFFFFF.toInt() else 0xCCB9C6D2.toInt()
                canvas.drawText(game.moveAt(i0 + 1), blackX, baseline, textPaint)
            }
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val w = width.toFloat()
        val y = height - bottomInset - bottomBarH / 2f

        val turn = game.sideToMove
        val (label, color) = when {
            game.finished -> when (game.winner) {
                2 -> "和棋" to C_GOLD
                Side.RED -> "红方胜" to C_RED
                else -> "黑方胜" to 0xFF9FB3C8.toInt()
            }
            thinking -> "电脑思考中…" to C_GOLD
            game.inCheck -> "将军！${Side.name(turn)}应将" to 0xFFD96A5A.toInt()
            else -> "${Side.name(turn)}走棋" to C_GOLD
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(13f)
        textPaint.color = color
        val fm = textPaint.fontMetrics
        val baseY = y - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, w / 2f, baseY, textPaint)

        // 行棋方圆点
        val dotX = w / 2f - textPaint.measureText(label) / 2f - dp(10f)
        paint.style = Paint.Style.FILL
        paint.color = if (turn == Side.RED) C_RED else 0xFFB9C6D2.toInt()
        canvas.drawCircle(dotX, y, dp(3.2f), paint)
    }

    /** 退出对局的二次确认弹窗 */
    private fun drawConfirm(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0xA6000000.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val cardW = min(w - dp(64f), dp(300f))
        val cardH = dp(172f)
        val left = (w - cardW) / 2f
        val top = (h - cardH) / 2f
        tmpRect.set(left, top, left + cardW, top + cardH)

        paint.color = 0xF22B1F15.toInt()
        canvas.drawRoundRect(tmpRect, dp(22f), dp(22f), paint)
        linePaint.color = 0x66E2BE79
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(tmpRect, dp(22f), dp(22f), linePaint)

        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = dp(25f)
        titlePaint.color = C_GOLD
        canvas.drawText("退出对局？", w / 2f, top + dp(66f), titlePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textPaint.textSize = dp(12f)
        textPaint.color = C_TEXT_DIM
        canvas.drawText("当前棋局不会保存", w / 2f, top + dp(94f), textPaint)

        val btnH = dp(42f)
        val btnW = (cardW - dp(48f)) / 2f
        val by = top + cardH - dp(22f) - btnH
        confirmCancelRect.set(left + dp(16f), by, left + dp(16f) + btnW, by + btnH)
        confirmOkRect.set(
            confirmCancelRect.right + dp(16f), by,
            confirmCancelRect.right + dp(16f) + btnW, by + btnH
        )

        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(confirmCancelRect, btnH / 2f, btnH / 2f, paint)
        linePaint.color = 0x55E2BE79
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(confirmCancelRect, btnH / 2f, btnH / 2f, linePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(14f)
        textPaint.color = C_GOLD
        var fm = textPaint.fontMetrics
        canvas.drawText(
            "取消", confirmCancelRect.centerX(),
            confirmCancelRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint
        )

        paint.color = C_GOLD
        canvas.drawRoundRect(confirmOkRect, btnH / 2f, btnH / 2f, paint)
        textPaint.color = 0xFF2A1E14.toInt()
        canvas.drawText(
            "退出",
            confirmOkRect.centerX(),
            confirmOkRect.centerY() - (fm.ascent + fm.descent) / 2f, textPaint
        )
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun drawResult(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = 0xB3000000.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val cardW = min(w - dp(56f), dp(320f))
        val cardH = dp(228f)
        val left = (w - cardW) / 2f
        val top = (h - cardH) / 2f
        tmpRect.set(left, top, left + cardW, top + cardH)

        paint.color = 0xF22B1F15.toInt()
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), paint)
        linePaint.color = 0x66E2BE79
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(tmpRect, dp(24f), dp(24f), linePaint)

        val title = when (game.winner) {
            2 -> "和棋"
            Side.RED -> "红方胜"
            else -> "黑方胜"
        }
        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = dp(34f)
        titlePaint.color = when (game.winner) {
            2 -> C_GOLD
            Side.RED -> C_RED
            else -> 0xFFC9D6E2.toInt()
        }
        canvas.drawText(title, w / 2f, top + dp(84f), titlePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(12f)
        textPaint.color = C_TEXT_DIM
        val sub = when (game.endReason) {
            1 -> "将死"
            6 -> "无子可动，困毙"
            2 -> "三次重复局面，判和"
            3 -> "长时间无吃子，判和"
            4 -> if (mode == Mode.AI) "你认输了" else "${Side.name(Side.other(game.winner))}认输"
            5 -> "双方同意和棋"
            else -> "棋局结束"
        }
        canvas.drawText(sub, w / 2f, top + dp(112f), textPaint)

        val btnH = dp(46f)
        val btnW = (cardW - dp(56f)) / 2f
        val by = top + cardH - dp(28f) - btnH
        againRect.set(left + dp(20f), by, left + dp(20f) + btnW, by + btnH)
        toMenuRect.set(againRect.right + dp(16f), by, againRect.right + dp(16f) + btnW, by + btnH)

        paint.color = C_GOLD
        canvas.drawRoundRect(againRect, btnH / 2f, btnH / 2f, paint)
        textPaint.color = 0xFF2A1E14.toInt()
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.textSize = dp(14f)
        val fm1 = textPaint.fontMetrics
        canvas.drawText(
            "再来一局", againRect.centerX(),
            againRect.centerY() - (fm1.ascent + fm1.descent) / 2f, textPaint
        )

        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(toMenuRect, btnH / 2f, btnH / 2f, paint)
        linePaint.color = 0x55E2BE79
        linePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(toMenuRect, btnH / 2f, btnH / 2f, linePaint)
        textPaint.color = C_GOLD
        canvas.drawText(
            "返回菜单", toMenuRect.centerX(),
            toMenuRect.centerY() - (fm1.ascent + fm1.descent) / 2f, textPaint
        )
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // ============================================================
    // 棋子位图（一次生成，反复使用）
    // ============================================================

    private fun ensurePieceBitmaps() {
        if (pieceBmpReady) return
        for (piece in 1..15) {
            val side = sideOf(piece)
            val kind = kindOf(piece)
            if (glyphOf(piece).isEmpty()) continue
            pieceBitmaps[piece] = renderPiece(piece, side, kind)
        }
        pieceBmpReady = true
    }

    private fun renderPiece(piece: Int, side: Int, kind: Int): Bitmap {
        val size = 224
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = size / 2f
        val r = size * 0.46f

        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        sp.color = 0x45000000
        cv.drawCircle(c, c + size * 0.030f, r, sp)
        sp.color = 0x22000000
        cv.drawCircle(c, c + size * 0.050f, r * 0.98f, sp)

        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = RadialGradient(
            c - r * 0.35f, c - r * 0.42f, r * 1.5f,
            intArrayOf(0xFFFFFDF5.toInt(), 0xFFF7E6C4.toInt(), 0xFFDFC08A.toInt()),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawCircle(c, c, r, body)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.028f
            color = 0xFFA87944.toInt()
        }
        cv.drawCircle(c, c, r - size * 0.014f, rim)

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.013f
            color = if (side == Side.RED) 0xCCB01E24.toInt() else 0xCC23201D.toInt()
        }
        cv.drawCircle(c, c, r * 0.80f, ring)

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (side == Side.RED) 0xFFB01E24.toInt() else 0xFF23201D.toInt()
            typeface = kai
            textAlign = Paint.Align.CENTER
            textSize = size * PIECE_TEXT_SCALE
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = size * 0.012f
            strokeJoin = Paint.Join.ROUND
        }
        // 每个字的墨迹宽高差很多（「馬」窄、「將」宽），统一字号必然有人顶圈、有人偏小。
        // 所以按实测墨迹把每个字缩放到固定的目标尺寸，再按墨迹中心对齐圆心。
        val glyph = glyphOf(piece)
        val inkRatio = (if (kind == Kind.KING) KING_INK_RATIO else PIECE_INK_RATIO) *
            (GLYPH_INK_TWEAK[glyph] ?: 1f)
        val targetInk = r * 0.80f * 2f * inkRatio

        tp.getTextBounds(glyph, 0, glyph.length, inkRect)
        val inkMax = maxOf(inkRect.width(), inkRect.height()).toFloat()
        if (inkMax > 1f) tp.textSize *= targetInk / inkMax

        // 用最终字号重新量一次，按墨迹中心（而不是字体基线）垂直居中
        tp.getTextBounds(glyph, 0, glyph.length, inkRect)
        cv.drawText(glyph, c, c - (inkRect.top + inkRect.bottom) / 2f, tp)

        if (kind != Kind.KING && kind != Kind.ADVISOR && kind != Kind.ELEPHANT) {
            // 车马炮兵：轻微内阴影提升立体感
            val sh = Paint(Paint.ANTI_ALIAS_FLAG)
            sh.color = 0x10FFFFFF
            cv.drawCircle(c, c - r * 0.28f, r * 0.55f, sh)
        }
        return bmp
    }

    private companion object {
        /** 字号的起算基准（会再按墨迹归一化，最终大小由下面两个比例决定） */
        const val PIECE_TEXT_SCALE = 0.56f

        /** 普通棋子墨迹占内圈直径的比例 */
        const val PIECE_INK_RATIO = 0.68f

        /** 将 / 帅比其它子略大一点 */
        const val KING_INK_RATIO = 0.72f

        /**
         * 个别字的光学补偿（在墨迹归一化之后再乘一次）。
         * 归一化让墨迹最长边一样长，但笔画少的字看着还是偏小，按需单独放大几个百分点。
         * 调整时改这里，配平后重新看截图。
         */
        val GLYPH_INK_TWEAK = mapOf(
            "仕" to 1.06f,
            "相" to 1.05f,
            "車" to 1.04f,
        )

        val C_BG_TOP = 0xFF2E2117.toInt()
        val C_BG_MID = 0xFF20170F.toInt()
        val C_BG_BOTTOM = 0xFF120D08.toInt()
        val C_WOOD_A = 0xFFF8E8C8.toInt()
        val C_WOOD_B = 0xFFE9CE9C.toInt()
        val C_GOLD = 0xFFE2BE79.toInt()
        val C_RED = 0xFFC0392B.toInt()
        val C_TEXT_DIM = 0xFFB7A489.toInt()
    }

}
