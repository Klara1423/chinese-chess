package com.klara.xiangqi.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
import com.klara.xiangqi.game.Board
import com.klara.xiangqi.game.Difficulty
import com.klara.xiangqi.game.Game
import com.klara.xiangqi.game.Kind
import com.klara.xiangqi.game.MoveGen
import com.klara.xiangqi.game.MoveNamer
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
import kotlin.math.sqrt

/**
 * 整个界面用一块 Canvas 绘制：主菜单 + 棋盘 + 对局信息，避免 Fragment/Compose 依赖。
 */
class GameView(context: Context) : View(context) {

    private enum class Screen { MENU, GAME }
    private enum class Mode { AI, PVP }

    // ---------- 状态 ----------
    private val game = Game()
    private val ai = Ai()
    /** 提示用的独立引擎实例，避免和电脑落子线程共用置换表 */
    private val hintAi = Ai()
    private var screen = Screen.MENU
    private var mode = Mode.AI
    private var difficulty = Difficulty.NORMAL

    // ---------- 人机执子 / 让子 ----------
    /** 人机模式下玩家执哪一方（红先走，执黑时电脑先走） */
    private var humanSide = Side.RED
    /** 人机、双人各自的让子：开局被拿掉的格子 */
    private var aiRemoved = IntArray(0)
    private var pvpRemoved = IntArray(0)
    /** 设置卡片是否打开，以及它是给哪个入口用的 */
    private var setupVisible = false
    private var setupMode = Mode.AI
    /** 让子小盘始终摆开局，被拿掉的子画淡 + 划线（不真的从棋盘上挖空） */
    private val setupBoard = Board()

    private var selected = -1
    private var targets = IntArray(0)
    private var dragging = false
    private var pressing = false
    private var pressSq = -1
    private var dragX = 0f
    private var dragY = 0f
    /** 拖动时吸附到的格子（-1 = 没吸附，跟着手指走） */
    private var dragSnapSq = -1
    private var downX = 0f
    private var downY = 0f

    private var thinking = false
    /** 正在后台计算提示着法 */
    private var hintThinking = false
    /** 提示的着法（-1 表示没有提示） */
    private var hintMove = -1
    /** 提示着法的中文记谱，显示在底部状态栏 */
    private var hintName = ""
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
        typeface = SANS
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
    private val hintPath = Path()

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
    private val hintRect = RectF()
    private val confirmCancelRect = RectF()
    private val confirmOkRect = RectF()
    private val confirmDiscardRect = RectF()
    private val confirmSaveRect = RectF()
    private val againRect = RectF()
    private val reviewRect = RectF()
    private val toMenuRect = RectF()
    private val logToggleRect = RectF()
    private val logCardRect = RectF()
    private val reviewPrevRect = RectF()
    private val reviewNextRect = RectF()
    private val settleRect = RectF()
    private val saveRect = RectF()

    // 开局设置卡片（人机 / 双人共用）
    private val setupCardRect = RectF()
    private val setupRedRect = RectF()
    private val setupBlackRect = RectF()
    private val setupClearRect = RectF()
    private val setupStartRect = RectF()
    private val setupCancelRect = RectF()
    /** 让子小盘的几何：画的时候算一次，点的时候按同一套坐标反推 */
    private var setupCell = 0f
    private var setupLeft = 0f
    private var setupTop = 0f

    /** 「退出对局？」二次确认是否打开 */
    private var confirmExit = false

    // ---------- 双人暂行（存档） ----------
    private var hasPvpSave = false
    private var pvpSavePly = 0

    /** 状态栏临时提示（如「已恢复上次对局」），到点自动消失 */
    private var notice = ""
    private var noticeUntil = 0L

    private val prefs = context.getSharedPreferences("xiangqi", Context.MODE_PRIVATE)

    init {
        difficulty = Difficulty.fromName(prefs.getString("difficulty", null))
        humanSide = if (prefs.getString(KEY_AI_SIDE, null) == "BLACK") Side.BLACK else Side.RED
        aiRemoved = parseSquares(prefs.getString(KEY_AI_REMOVED, null))
        pvpRemoved = parseSquares(prefs.getString(KEY_PVP_REMOVED, null))
        hasPvpSave = !prefs.getString(SAVE_KEY, null).isNullOrEmpty()
        pvpSavePly = prefs.getInt(SAVE_PLY_KEY, 0)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ============================================================
    // 生命周期 / 几何
    // ============================================================

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                // 顶部留白 = 系统给的挖孔安全区，但最多 29.1dp（80 px @440dpi）。
                // 系统报的是一条「安全区」，把状态栏高度也算进去了（这台 111、10S 90），
                // 比摄像头实体孔的下沿高出一截；实体孔的圆 Android 不暴露。
                // 所以按上限截断：孔比 29.1dp 小的机器自动跟着孔走，比它大的统一停在 29.1dp，
                // 换手机不用再逐个量。顶栏自带 12dp 内边距，扣掉后按钮顶边正好落在这条线上。
                val safeTop = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).top
                val band = minOf(safeTop.toFloat(), dp(29.1f))
                topInset = (band - dp(12f)).coerceAtLeast(0f).toInt()
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

    /** 人机执黑时棋盘翻转：黑方在下，自己的子就在手边（棋子上的字不跟着转） */
    private val flipped: Boolean get() = mode == Mode.AI && humanSide == Side.BLACK

    private fun cx(col: Int): Float = boardLeft + (if (flipped) 8 - col else col) * cell

    private fun cy(row: Int): Float = boardTop + (if (flipped) 9 - row else row) * cell

    /** 棋盘装饰（楚河汉界）固定在屏幕上的位置，不跟着翻转跑 */
    private fun bx(col: Int): Float = boardLeft + col * cell

    private val aiSide: Int get() = Side.other(humanSide)

    /** 这枚子现在能不能由玩家操作：人机模式下只认自己那一方 */
    private fun canPick(sq: Int): Boolean =
        game.isOwnPiece(sq) && (mode != Mode.AI || game.sideToMove == humanSide)

    /** 悔棋只退到玩家自己该走的局面：执黑时至少要先走完电脑的开局那一步 */
    private fun canUndoNow(): Boolean =
        game.canUndo() && !thinking && !confirmExit &&
            (mode != Mode.AI || game.plyCount >= if (humanSide == Side.BLACK) 2 else 1)

    private fun sideLabel(side: Int): String = if (side == Side.RED) "红" else "黑"

    private fun parseSquares(raw: String?): IntArray {
        if (raw.isNullOrEmpty()) return IntArray(0)
        return try {
            raw.split(",").map { it.toInt() }.filter { it in 0 until BOARD_SIZE }.distinct().sorted().toIntArray()
        } catch (e: Exception) {
            IntArray(0)
        }
    }

    private fun formatSquares(squares: IntArray): String = squares.joinToString(",")

    private fun removedFor(m: Mode): IntArray = if (m == Mode.AI) aiRemoved else pvpRemoved

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
                if (game.reviewing) return true
                if (logVisible) return true
                if (game.finished) return true
                if (thinking) return true
                val sq = squareAt(x, y, 0.62f)
                if (sq >= 0 && canPick(sq)) {
                    selected = sq
                    targets = game.legalTargets(sq)
                    pressSq = sq
                    pressing = true
                    dragging = false
                    dragSnapSq = -1
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
                        // 靠近可以落子的格子时自动吸附过去，吸附目标一变就轻振一下
                        val snap = snapTargetAt(x, y)
                        if (snap != dragSnapSq) {
                            dragSnapSq = snap
                            if (snap >= 0) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (screen == Screen.MENU) {
                    if (setupVisible) handleSetupTap(x, y) else handleMenuTap(x, y)
                    return true
                }
                if (confirmExit) {
                    when {
                        confirmCancelRect.contains(x, y) -> {
                            confirmExit = false
                            invalidate()
                        }
                        // 双人：退出时自己选「不保存」还是「保存」，不自动存档
                        mode == Mode.PVP && confirmDiscardRect.contains(x, y) -> {
                            confirmExit = false
                            clearPvpSave()
                            goMenu()
                        }
                        mode == Mode.PVP && confirmSaveRect.contains(x, y) -> {
                            confirmExit = false
                            savePvpGame()
                            goMenu()
                        }
                        mode != Mode.PVP && confirmOkRect.contains(x, y) -> {
                            confirmExit = false
                            goMenu()
                        }
                    }
                    return true
                }
                if (game.reviewing) {
                    when {
                        backRect.contains(x, y) -> requestExitGame()
                        reviewPrevRect.contains(x, y) -> reviewStep(-1)
                        reviewNextRect.contains(x, y) -> reviewStep(1)
                        settleRect.contains(x, y) -> exitReview()
                        mode == Mode.PVP && saveRect.contains(x, y) -> {
                            logVisible = !logVisible
                            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            invalidate()
                        }
                        logVisible && !logCardRect.contains(x, y) -> {
                            logVisible = false
                            invalidate()
                        }
                    }
                    return true
                }
                if (mode == Mode.PVP && logToggleRect.contains(x, y)) {
                    logVisible = !logVisible
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                if (mode == Mode.AI && hintRect.contains(x, y)) {
                    startHint()
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
                        reviewRect.contains(x, y) -> enterReview()
                        toMenuRect.contains(x, y) -> goMenu()
                        backRect.contains(x, y) -> goMenu()
                    }
                    return true
                }
                if (thinking) return true

                if (pressing) {
                    val snap = dragSnapSq
                    val wasDragging = dragging
                    pressing = false
                    dragging = false
                    dragSnapSq = -1
                    // 松手时优先用吸附的格子；没吸附再按原来的 0.9 格容差判断
                    val upSq = if (snap >= 0) snap else squareAt(x, y, 0.90f)
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
                dragSnapSq = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun handleMenuTap(x: Float, y: Float) {
        if (menuAiRect.contains(x, y)) {
            openSetup(Mode.AI)
        } else if (menuPvpRect.contains(x, y)) {
            // 有存档就直接接着上次的走（设置只在新开一局时出现）；没有才开设置卡片
            if (!resumePvpGame()) openSetup(Mode.PVP)
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

    // ============================================================
    // 开局设置（人机：执红 / 执黑 + 让子；双人：让子）
    // ============================================================

    /** 只有在新开一局之前才有这个卡片：对局中、复盘时都进不来 */
    private fun openSetup(m: Mode) {
        setupMode = m
        setupVisible = true
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun handleSetupTap(x: Float, y: Float) {
        when {
            setupStartRect.contains(x, y) -> {
                setupVisible = false
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                startNewGame(setupMode)
            }

            setupCancelRect.contains(x, y) || !setupCardRect.contains(x, y) -> {
                setupVisible = false
                invalidate()
            }

            setupMode == Mode.AI && setupRedRect.contains(x, y) -> setHumanSide(Side.RED)
            setupMode == Mode.AI && setupBlackRect.contains(x, y) -> setHumanSide(Side.BLACK)

            setupClearRect.width() > 0f && setupClearRect.contains(x, y) -> clearHandicap()

            setupCell > 0f -> {
                val sc = round((x - setupLeft) / setupCell).toInt()
                val sr = round((y - setupTop) / setupCell).toInt()
                if (sc in 0..8 && sr in 0..9) {
                    val dx = x - (setupLeft + sc * setupCell)
                    val dy = y - (setupTop + sr * setupCell)
                    if (dx * dx + dy * dy <= (setupCell * 0.55f) * (setupCell * 0.55f)) {
                        toggleHandicap(sr * 9 + sc)
                    }
                }
            }
        }
    }

    private fun setHumanSide(side: Int) {
        if (humanSide == side) return
        humanSide = side
        prefs.edit().putString(KEY_AI_SIDE, if (side == Side.BLACK) "BLACK" else "RED").apply()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** 点小盘上的棋子：拿掉 / 放回。将帅不能动 */
    private fun toggleHandicap(sq: Int) {
        if (sq !in 0 until BOARD_SIZE) return
        val piece = setupBoard.cells[sq]
        if (piece == 0 || kindOf(piece) == Kind.KING) return
        val list = removedFor(setupMode).toMutableList()
        if (!list.remove(sq)) list.add(sq)
        list.sort()
        setRemoved(setupMode, list.toIntArray())
    }

    private fun clearHandicap() {
        if (removedFor(setupMode).isEmpty()) return
        setRemoved(setupMode, IntArray(0))
    }

    private fun setRemoved(m: Mode, squares: IntArray) {
        if (m == Mode.AI) {
            aiRemoved = squares
            prefs.edit().putString(KEY_AI_REMOVED, formatSquares(squares)).apply()
        } else {
            pvpRemoved = squares
            prefs.edit().putString(KEY_PVP_REMOVED, formatSquares(squares)).apply()
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun squareAt(x: Float, y: Float, tol: Float): Int {
        if (cell <= 0f) return -1
        val sc = round((x - boardLeft) / cell).toInt()
        val sr = round((y - boardTop) / cell).toInt()
        if (sc < 0 || sc > 8 || sr < 0 || sr > 9) return -1
        val dx = x - bx(sc)
        val dy = y - (boardTop + sr * cell)
        val limit = cell * tol
        if (dx * dx + dy * dy > limit * limit) return -1
        val col = if (flipped) 8 - sc else sc
        val row = if (flipped) 9 - sr else sr
        return row * 9 + col
    }

    /** 拖动时的手指位置如果落在某个合法落点（或原位）附近，就返回那个格子，否则 -1 */
    private fun snapTargetAt(x: Float, y: Float): Int {
        if (cell <= 0f || selected < 0) return -1
        val sc = round((x - boardLeft) / cell).toInt()
        val sr = round((y - boardTop) / cell).toInt()
        if (sc < 0 || sc > 8 || sr < 0 || sr > 9) return -1
        val col = if (flipped) 8 - sc else sc
        val row = if (flipped) 9 - sr else sr
        val sq = row * 9 + col
        if (sq != pressSq && !targets.contains(sq)) return -1
        val dx = x - bx(sc)
        val dy = y - (boardTop + sr * cell)
        val limit = cell * 0.62f
        return if (dx * dx + dy * dy <= limit * limit) sq else -1
    }

    // ============================================================
    // 对局控制
    // ============================================================

    fun handleBack(): Boolean {
        return when {
            setupVisible -> {
                // 设置卡片开着时，返回键先关卡片
                setupVisible = false
                invalidate()
                true
            }
            screen == Screen.GAME -> {
                if (game.reviewing) {
                    // 复盘时返回键先回到结算卡片，不直接丢下这局
                    exitReview()
                } else if (confirmExit) {
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
        if (game.reviewing) game.exitReview()
        // 双人的存档只由退出弹窗里的「保存」写入；下完 / 一步没走就回菜单时清掉旧存档
        if (mode == Mode.PVP && (game.finished || game.plyCount == 0)) clearPvpSave()
        generation++
        thinking = false
        hintThinking = false
        hintMove = -1
        hintName = ""
        logVisible = false
        confirmExit = false
        setupVisible = false
        notice = ""
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
        if (m == Mode.PVP) clearPvpSave()
        // 开局按设置摆子：让子只在这里生效，之后悔棋 / 复盘都从同一个开局重放
        game.reset(removedFor(m))
        selected = -1
        targets = IntArray(0)
        dragging = false
        pressing = false
        thinking = false
        hintThinking = false
        hintMove = -1
        hintName = ""
        logVisible = false
        confirmExit = false
        animating = false
        animStartAt = 0L
        finishedAt = 0L
        notice = ""
        screen = Screen.GAME
        // 双人 / 人机的棋盘布局不同（居中 vs 给棋谱留位置），换模式要重算几何
        updateGeometry()
        if (m == Mode.AI) {
            if (game.removedPieces.isNotEmpty()) {
                showNotice("我执${sideLabel(humanSide)} · 让 ${game.removedPieces.size} 子", 2400L)
            }
            // 执黑时电脑（红）先走
            if (game.sideToMove == aiSide) startThinking()
        }
        invalidate()
    }

    private fun applyMove(from: Int, to: Int) {
        if (!game.play(from, to)) return
        selected = -1
        targets = IntArray(0)
        // 走子后提示就过期了
        hintThinking = false
        hintMove = -1
        hintName = ""
        // 双人模式落子即自动存盘：中途被系统杀掉也能接着下；退出时选「不保存」才会清掉
        if (mode == Mode.PVP) {
            if (game.finished) clearPvpSave() else savePvpGame()
        }
        startAnimation(from, to)
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        if (game.finished) {
            finishedAt = SystemClock.uptimeMillis()
            // 等动画走完再弹出结算
            postDelayed({ invalidate() }, 420L)
        } else if (mode == Mode.AI && game.sideToMove == aiSide) {
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
        if (mode == Mode.AI && !canUndoNow()) return
        generation++
        thinking = false
        hintThinking = false
        hintMove = -1
        hintName = ""
        if (mode == Mode.AI) {
            // 人机模式下退回到自己该走棋的局面：自己的上一步 + 电脑的应手
            repeat(2) { if (game.canUndo()) game.undo() }
        } else {
            if (game.canUndo()) game.undo()
        }
        if (mode == Mode.PVP) savePvpGame()
        selected = -1
        targets = IntArray(0)
        animating = false
        finishedAt = 0L
        // 执黑时退到开局，轮到电脑重新走第一步
        if (mode == Mode.AI && game.sideToMove == aiSide) startThinking()
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    // ============================================================
    // 复盘 / 双人存档
    // ============================================================

    /** 结算卡片上点「复盘」：收起结果，棋盘进入只读的前后翻页模式 */
    private fun enterReview() {
        if (!game.finished || !game.enterReview()) return
        generation++
        thinking = false
        hintThinking = false
        hintMove = -1
        hintName = ""
        selected = -1
        targets = IntArray(0)
        animating = false
        logVisible = false
        confirmExit = false
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** 复盘里点「上一步 / 下一步」 */
    private fun reviewStep(delta: Int) {
        val moved = if (delta < 0) game.reviewPrev() else game.reviewNext()
        if (!moved) return
        selected = -1
        targets = IntArray(0)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** 收起复盘，回到结算卡片 */
    private fun exitReview() {
        game.exitReview()
        selected = -1
        targets = IntArray(0)
        logVisible = false
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun savePvpGame() {
        if (mode != Mode.PVP || game.reviewing) return
        if (game.finished || game.plyCount == 0) {
            clearPvpSave()
            return
        }
        val body = game.movesSnapshot().joinToString(",")
        prefs.edit()
            .putString(SAVE_KEY, SAVE_PREFIX + body)
            .putInt(SAVE_PLY_KEY, game.plyCount)
            .putString(SAVE_REMOVED_KEY, formatSquares(game.removedPieces))
            .commit()
        hasPvpSave = true
        pvpSavePly = game.plyCount
    }

    private fun clearPvpSave() {
        if (!hasPvpSave && !prefs.contains(SAVE_KEY)) return
        prefs.edit()
            .remove(SAVE_KEY)
            .remove(SAVE_PLY_KEY)
            .remove(SAVE_REMOVED_KEY)
            .commit()
        hasPvpSave = false
        pvpSavePly = 0
    }

    /** 从存档接着下；没有存档或存档损坏返回 false */
    private fun resumePvpGame(): Boolean {
        val raw = prefs.getString(SAVE_KEY, null) ?: return false
        if (!raw.startsWith(SAVE_PREFIX)) {
            clearPvpSave()
            return false
        }
        val body = raw.substring(SAVE_PREFIX.length)
        val moves = try {
            if (body.isEmpty()) IntArray(0) else body.split(",").map { it.toInt() }.toIntArray()
        } catch (e: Exception) {
            clearPvpSave()
            return false
        }
        // 让子是开局的一部分：存档里单独记着，重放时和开局摆子一致
        val removed = parseSquares(prefs.getString(SAVE_REMOVED_KEY, null))
        if (moves.isEmpty() || !game.restoreMoves(moves, removed) || game.finished) {
            clearPvpSave()
            return false
        }

        generation++
        mode = Mode.PVP
        // 存档里带着让子，重开这一局时保持同一套设置
        pvpRemoved = removed
        prefs.edit().putString(KEY_PVP_REMOVED, formatSquares(removed)).apply()
        selected = -1
        targets = IntArray(0)
        dragging = false
        pressing = false
        thinking = false
        hintThinking = false
        hintMove = -1
        hintName = ""
        logVisible = false
        confirmExit = false
        animating = false
        animStartAt = 0L
        finishedAt = 0L
        screen = Screen.GAME
        updateGeometry()
        showNotice("已恢复上次对局", 2200L)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
        return true
    }

    /** 状态栏临时提示，过一会儿自动恢复成行棋方 / 结果 */
    private fun showNotice(text: String, duration: Long = 1600L) {
        notice = text
        noticeUntil = SystemClock.uptimeMillis() + duration
        postDelayed({ invalidate() }, duration + 30L)
        invalidate()
    }

    /** Activity 进入后台时再兜底存一次（正常走子时已经存过） */
    fun onHostPause() {
        if (mode == Mode.PVP && !game.finished) savePvpGame()
    }

    /** 人机模式：让引擎给出当前局面的推荐着法，只标在棋盘上，不替玩家落子。再点一次收起。 */
    private fun startHint() {
        if (mode != Mode.AI || thinking || hintThinking || game.finished) return
        if (game.sideToMove != humanSide) return
        if (hintMove >= 0) {
            hintMove = -1
            hintName = ""
            invalidate()
            return
        }
        hintThinking = true
        val gen = generation
        val ply = game.plyCount
        val snapshot = game.board.copy()
        // 提示至少用「困难」档：低档位带随手棋，会把提示带偏；菜单选大师时就让提示也跑大师。
        val level = if (difficulty.ordinal >= Difficulty.HARD.ordinal) difficulty else Difficulty.HARD
        invalidate()
        Thread {
            val move = hintAi.chooseMove(snapshot, level)
            val name = if (move != 0) MoveNamer.name(snapshot, move) else ""
            post {
                if (gen != generation) return@post
                hintThinking = false
                if (move != 0 && game.plyCount == ply && !game.finished &&
                    game.sideToMove == humanSide
                ) {
                    hintMove = move
                    hintName = name
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                invalidate()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startThinking() {
        if (thinking) return
        if (mode != Mode.AI || game.finished || game.sideToMove != aiSide) return
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
                if (move != 0 && !game.finished && game.sideToMove == aiSide) {
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
        if (animating || thinking || (game.inCheck && (!game.finished || game.reviewing))) {
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
        textPaint.typeface = SANS

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
        drawMenuButton(canvas, menuAiRect, "人机对战", aiMenuSubtitle(), filled = true)
        y += btnH + dp(14f)

        menuPvpRect.set(left, y, left + btnW, y + btnH)
        drawMenuButton(canvas, menuPvpRect, "双人对弈", pvpMenuSubtitle(), filled = false)
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

        // 开局设置卡片盖在菜单上（只在新开一局前出现）
        if (setupVisible) drawSetup(canvas)
    }

    private fun aiMenuSubtitle(): String =
        if (humanSide == Side.RED && aiRemoved.isEmpty()) {
            "与电脑对弈"
        } else {
            buildString {
                append("我执${sideLabel(humanSide)}")
                if (aiRemoved.isNotEmpty()) append(" · 让 ${aiRemoved.size} 子")
            }
        }

    private fun pvpMenuSubtitle(): String = when {
        hasPvpSave -> "继续上次对局 · $pvpSavePly 步"
        pvpRemoved.isEmpty() -> "同屏轮流走子"
        else -> "同屏轮流走子 · 让 ${pvpRemoved.size} 子"
    }

    // ============================================================
    // 开局设置卡片（人机 / 双人共用）
    // ============================================================

    private fun drawSetup(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ai = setupMode == Mode.AI

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0xA6000000.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val cardW = min(w - dp(32f), dp(340f))
        val padX = dp(18f)
        val padTop = dp(18f)
        val titleH = dp(30f)
        val labelH = dp(16f)
        val segH = dp(36f)
        val gapAfterSeg = dp(10f)
        val boardGap = dp(6f)
        val btnGap = dp(16f)
        val btnH = dp(44f)
        val padBottom = dp(18f)

        // 卡片固定部分的高度；小盘按剩下的空间定边长，小屏也不会顶出去
        val fixed = padTop + titleH +
            (if (ai) labelH + segH + gapAfterSeg else 0f) +
            labelH + boardGap + btnGap + btnH + padBottom
        val availH = h - topInset - bottomInset - dp(24f)
        val miniCell = min(
            (cardW - padX * 2) / 9f,
            (availH - fixed) / 10f
        ).coerceIn(dp(13f), dp(30f))

        val cardH = fixed + miniCell * 10f
        val left = (w - cardW) / 2f
        val top = (h - cardH) / 2f
        setupCardRect.set(left, top, left + cardW, top + cardH)

        paint.color = 0xF22B1F15.toInt()
        canvas.drawRoundRect(setupCardRect, dp(24f), dp(24f), paint)
        linePaint.color = 0x66E2BE79
        linePaint.strokeWidth = dp(1.2f)
        canvas.drawRoundRect(setupCardRect, dp(24f), dp(24f), linePaint)

        titlePaint.textAlign = Paint.Align.CENTER
        titlePaint.textSize = dp(24f)
        titlePaint.color = C_GOLD
        titlePaint.letterSpacing = 0.08f
        canvas.drawText(
            if (ai) "人机对战" else "双人对弈",
            w / 2f, top + padTop + dp(21f), titlePaint
        )
        titlePaint.letterSpacing = 0f

        var y = top + padTop + titleH
        if (ai) {
            drawSetupLabel(canvas, "我执", left + padX, y + labelH * 0.74f)
            y += labelH

            val segLeft = left + padX
            val segW = cardW - padX * 2
            paint.color = 0x12FFFFFF
            tmpRect.set(segLeft, y, segLeft + segW, y + segH)
            canvas.drawRoundRect(tmpRect, segH / 2f, segH / 2f, paint)
            linePaint.color = 0x22E2BE79
            linePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(tmpRect, segH / 2f, segH / 2f, linePaint)

            val pad = dp(3.5f)
            val itemW = (segW - pad * 2) / 2f
            val itemH = segH - pad * 2
            setupRedRect.set(segLeft + pad, y + pad, segLeft + pad + itemW, y + pad + itemH)
            setupBlackRect.set(
                segLeft + pad + itemW, y + pad,
                segLeft + pad + itemW * 2f, y + pad + itemH
            )
            drawSetupSegment(canvas, setupRedRect, "红方 · 先行", humanSide == Side.RED, itemH)
            drawSetupSegment(canvas, setupBlackRect, "黑方 · 后行", humanSide == Side.BLACK, itemH)
            y += segH + gapAfterSeg
        } else {
            setupRedRect.setEmpty()
            setupBlackRect.setEmpty()
        }

        val removed = removedFor(setupMode)
        drawSetupLabel(
            canvas,
            if (removed.isEmpty()) "让子（点棋子拿掉，再点放回）" else "让子 · 已让 ${removed.size} 子",
            left + padX, y + labelH * 0.74f
        )
        setupClearRect.setEmpty()
        if (removed.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.typeface = SANS_MEDIUM
            textPaint.textSize = dp(11f)
            textPaint.color = C_GOLD
            canvas.drawText("清空", left + cardW - padX, y + labelH * 0.74f, textPaint)
            val tw = textPaint.measureText("清空")
            setupClearRect.set(
                left + cardW - padX - tw - dp(8f), y,
                left + cardW - padX, y + labelH
            )
            textPaint.typeface = SANS
        }
        y += labelH + boardGap

        setupCell = miniCell
        setupLeft = w / 2f - miniCell * 4f
        setupTop = y + miniCell * 0.5f
        drawSetupBoard(canvas)
        y += miniCell * 10f + btnGap

        val gap = dp(10f)
        val btnW = (cardW - padX * 2 - gap) / 2f
        setupCancelRect.set(left + padX, y, left + padX + btnW, y + btnH)
        setupStartRect.set(
            setupCancelRect.right + gap, y,
            setupCancelRect.right + gap + btnW, y + btnH
        )
        drawDialogButton(canvas, setupCancelRect, "取消", false, btnH)
        drawDialogButton(canvas, setupStartRect, "开始对局", true, btnH)
    }

    private fun drawSetupLabel(canvas: Canvas, label: String, x: Float, baseline: Float) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = SANS
        textPaint.textSize = dp(11f)
        textPaint.letterSpacing = 0.1f
        textPaint.color = C_TEXT_DIM
        canvas.drawText(label, x, baseline, textPaint)
        textPaint.letterSpacing = 0f
    }

    private fun drawSetupSegment(
        canvas: Canvas,
        r: RectF,
        label: String,
        selected: Boolean,
        itemH: Float
    ) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        if (selected) {
            paint.color = C_GOLD
            canvas.drawRoundRect(r, itemH / 2f, itemH / 2f, paint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(12f)
        textPaint.color = if (selected) 0xFF2A1E14.toInt() else C_TEXT_DIM
        val fm = textPaint.fontMetrics
        canvas.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.typeface = SANS
    }

    /** 让子小盘：开局摆好的棋子，被拿掉的画淡 + 红线划掉，将帅不可点 */
    private fun drawSetupBoard(canvas: Canvas) {
        val cell = setupCell
        val left = setupLeft
        val top = setupTop
        if (cell <= 0f) return

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = 0x14FFFFFF
        tmpRect.set(
            left - cell * 0.5f, top - cell * 0.5f,
            left + cell * 8.5f, top + cell * 9.5f
        )
        canvas.drawRoundRect(tmpRect, dp(8f), dp(8f), paint)

        linePaint.color = 0x2EE2BE79
        linePaint.strokeWidth = dp(0.7f)
        for (r in 0..9) {
            val yy = top + r * cell
            canvas.drawLine(left, yy, left + cell * 8f, yy, linePaint)
        }
        for (c in 0..8) {
            val xx = left + c * cell
            canvas.drawLine(xx, top, xx, top + cell * 4f, linePaint)
            canvas.drawLine(xx, top + cell * 5f, xx, top + cell * 9f, linePaint)
        }
        canvas.drawLine(left + cell * 3f, top, left + cell * 5f, top + cell * 2f, linePaint)
        canvas.drawLine(left + cell * 5f, top, left + cell * 3f, top + cell * 2f, linePaint)
        canvas.drawLine(left + cell * 3f, top + cell * 7f, left + cell * 5f, top + cell * 9f, linePaint)
        canvas.drawLine(left + cell * 5f, top + cell * 7f, left + cell * 3f, top + cell * 9f, linePaint)

        val removed = removedFor(setupMode)
        val size = cell * 0.86f
        for (sq in 0 until BOARD_SIZE) {
            val p = setupBoard.cells[sq]
            if (p == 0) continue
            val x = left + colOf(sq) * cell
            val yy = top + rowOf(sq) * cell
            val gone = removed.contains(sq)
            // 淡到能看出是哪枚子，又不至于像还在棋盘上
            paint.alpha = if (gone) 78 else 255
            drawPieceAt(canvas, p, x, yy, size, rotateBlack = false)
            paint.alpha = 255
            if (gone) {
                linePaint.color = 0xCCD9503F.toInt()
                linePaint.strokeWidth = dp(1.5f)
                val rr = size * 0.30f
                canvas.drawLine(x - rr, yy - rr, x + rr, yy + rr, linePaint)
                canvas.drawLine(x + rr, yy - rr, x - rr, yy + rr, linePaint)
            }
        }
    }

    /**
     * 菜单的两个入口：主按钮（金色实心）和次按钮（描边）。
     * 两者除底色、描边、文字颜色之外完全一样，合成一处，改一次两边都生效。
     */
    private fun drawMenuButton(
        canvas: Canvas,
        r: RectF,
        title: String,
        sub: String,
        filled: Boolean
    ) {
        paint.style = Paint.Style.FILL
        tmpRect.set(r)
        if (filled) {
            paint.color = 0x33E2BE79
            canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), paint)
            paint.color = C_GOLD
            tmpRect.inset(dp(1.5f), dp(1.5f))
            canvas.drawRoundRect(tmpRect, dp(17f), dp(17f), paint)
        } else {
            paint.color = 0x10FFFFFF
            canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), paint)
            linePaint.color = 0x55E2BE79
            linePaint.strokeWidth = dp(1.2f)
            canvas.drawRoundRect(tmpRect, dp(18f), dp(18f), linePaint)
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(18f)
        textPaint.color = if (filled) 0xFF2A1E14.toInt() else C_GOLD
        val fm = textPaint.fontMetrics
        val titleCy = r.centerY() - dp(7f)
        canvas.drawText(title, r.centerX(), titleCy - (fm.ascent + fm.descent) / 2f, textPaint)

        textPaint.typeface = SANS
        textPaint.textSize = dp(10.5f)
        textPaint.color = if (filled) 0xAA3A2A16.toInt() else 0x88B7A489.toInt()
        canvas.drawText(sub, r.centerX(), r.centerY() + dp(15f), textPaint)
    }

    // ============================================================
    // 对局界面
    // ============================================================

    private fun drawGame(canvas: Canvas) {
        drawTopBar(canvas)
        drawBoard(canvas)
        drawPieces(canvas)
        drawHint(canvas)
        drawCapturedRows(canvas)
        drawLogPanel(canvas)
        drawBottomBar(canvas)
        if (game.finished && !game.reviewing && SystemClock.uptimeMillis() - finishedAt > 380) {
            drawResult(canvas)
        } else if (confirmExit) {
            drawConfirm(canvas)
        }
    }

    private fun drawTopBar(canvas: Canvas) {
        val w = width.toFloat()
        val barTop = topInset + dp(12f)
        val barH = dp(36f)

        if (game.reviewing) {
            backRect.set(dp(12f), barTop, dp(12f) + dp(56f), barTop + barH)
            drawPill(canvas, backRect, "‹ 菜单", true)

            // 复盘时右半边换成「上一步 / 下一步 / 结算」，从右往左排
            settleRect.set(w - dp(12f) - dp(56f), barTop, w - dp(12f), barTop + barH)
            reviewNextRect.set(
                settleRect.left - dp(8f) - dp(56f), barTop,
                settleRect.left - dp(8f), barTop + barH
            )
            reviewPrevRect.set(
                reviewNextRect.left - dp(8f) - dp(56f), barTop,
                reviewNextRect.left - dp(8f), barTop + barH
            )
            drawPill(canvas, settleRect, "结算", true)
            drawPill(canvas, reviewNextRect, "下一步", game.reviewPly < game.plyCount)
            drawPill(canvas, reviewPrevRect, "上一步", game.reviewPly > 0)

            val title = "复盘 ${game.reviewPly} / ${game.plyCount}"
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = C_GOLD
            textPaint.typeface = SANS_MEDIUM
            textPaint.textSize = dp(14f)
            val fmr = textPaint.fontMetrics
            val reviewCx = (backRect.right + reviewPrevRect.left) / 2f
            canvas.drawText(
                title, reviewCx,
                barTop + barH / 2f - (fmr.ascent + fmr.descent) / 2f, textPaint
            )
            textPaint.typeface = SANS
            return
        }

        backRect.set(dp(12f), barTop, dp(12f) + dp(56f), barTop + barH)
        drawPill(canvas, backRect, "‹ 菜单", true)

        restartRect.set(w - dp(12f) - dp(56f), barTop, w - dp(12f), barTop + barH)
        drawPill(canvas, restartRect, "重开", true)

        undoRect.set(
            restartRect.left - dp(8f) - dp(56f), barTop,
            restartRect.left - dp(8f), barTop + barH
        )
        drawPill(canvas, undoRect, "悔棋", canUndoNow())

        val title = when (mode) {
            Mode.AI -> "人机对战 · ${difficulty.label}"
            Mode.PVP -> "双人对弈"
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = C_GOLD
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(14f)
        val fm = textPaint.fontMetrics
        // 「菜单」右边：人机是「提示」，双人是「棋谱」开关；两边的标题都从按钮右边开始居中
        var titleLeft = backRect.right
        if (mode == Mode.PVP) {
            logToggleRect.set(
                backRect.right + dp(8f), barTop,
                backRect.right + dp(8f) + dp(56f), barTop + barH
            )
            drawPill(canvas, logToggleRect, if (logVisible) "收起" else "棋谱", true)
            titleLeft = logToggleRect.right
        } else {
            hintRect.set(
                backRect.right + dp(8f), barTop,
                backRect.right + dp(8f) + dp(56f), barTop + barH
            )
            // 电脑思考、计算提示、棋局结束、轮到电脑走时都不能点
            val hintEnabled = !thinking && !hintThinking && !game.finished &&
                game.sideToMove == humanSide && !confirmExit
            drawPill(canvas, hintRect, "提示", hintEnabled)
            titleLeft = hintRect.right
        }
        val titleCx = (titleLeft + undoRect.left) / 2f
        canvas.drawText(title, titleCx, barTop + barH / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
        textPaint.typeface = SANS
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
        val riverY = boardTop + cell * 4.5f
        val fm = textPaint.fontMetrics
        val charDx = cell * 0.28f
        fun riverChar(ch: String, x: Float, y: Float, degrees: Float) {
            canvas.save()
            canvas.rotate(degrees, x, y)
            canvas.drawText(ch, x, y - (fm.ascent + fm.descent) / 2f, textPaint)
            canvas.restore()
        }
        // 楚河汉界是棋盘装饰，固定在屏幕左右，不跟着翻转（执黑）跑
        riverChar("楚", bx(2) - charDx, riverY, -90f)
        riverChar("河", bx(2) + charDx, riverY, -90f)
        // 右边这组是「漢界」：漢在右端、界在左端（之前写反了，按实机截图对比改回来）
        riverChar("界", bx(6) - charDx, riverY, 90f)
        riverChar("漢", bx(6) + charDx, riverY, 90f)
        textPaint.typeface = SANS
    }

    private fun mark(canvas: Canvas, r: Int, c: Int) {
        // 装饰跟随棋盘翻转：屏幕上的列才是判断「是否靠边」的依据
        val sc = if (flipped) 8 - c else c
        val x = bx(sc)
        val y = cy(r)
        val d = cell * 0.10f
        val len = cell * 0.16f
        linePaint.strokeWidth = dp(0.9f)
        linePaint.color = 0xAA8A5F35.toInt()
        for (sx in intArrayOf(-1, 1)) {
            if (sx < 0 && sc == 0) continue
            if (sx > 0 && sc == 8) continue
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
        if (dragging && dragSnapSq >= 0) {
            // 吸附到的落点再套一圈，配合振动让「已经吸住」看得见
            linePaint.color = 0x99FFD98A.toInt()
            linePaint.strokeWidth = dp(1.8f)
            canvas.drawCircle(cx(colOf(dragSnapSq)), cy(rowOf(dragSnapSq)), cell * 0.52f, linePaint)
        } else if (dragging) {
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

        // 上一步的残影：在起点留一个淡棋子，下一步落子时自然被替换
        if (game.lastMove >= 0 && game.lastMovedPiece != 0) {
            val from = MoveGen.fromOf(game.lastMove)
            paint.alpha = 64
            drawPieceAt(canvas, game.lastMovedPiece, cx(colOf(from)), cy(rowOf(from)), size)
            paint.alpha = 255
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

        // 拖动中的棋子：吸附时直接落在目标格中心，没吸附时跟着手指走
        if (dragging && selected >= 0) {
            val p = game.board.cells[selected]
            if (p != 0) {
                if (dragSnapSq >= 0) {
                    drawPieceAt(
                        canvas, p,
                        cx(colOf(dragSnapSq)), cy(rowOf(dragSnapSq)),
                        size * 1.08f
                    )
                } else {
                    drawPieceAt(canvas, p, dragX, dragY - dp(14f), size * 1.08f)
                }
            }
        }

        // 将军提示（复盘翻到历史上的将军局面时也保留脉冲）
        if (game.inCheck && (!game.finished || game.reviewing)) {
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

    /**
     * 提示着法：起点画绿圈，再用箭头指向目标格（沿用 1.1 的样式，颜色收进 `Theme`）。
     * 画在棋子之上，箭头才不会被目标格上的子挡住。
     */
    private fun drawHint(canvas: Canvas) {
        if (hintMove < 0) return
        val from = MoveGen.fromOf(hintMove)
        val to = MoveGen.toOf(hintMove)
        val fx = cx(colOf(from))
        val fy = cy(rowOf(from))
        val tx = cx(colOf(to))
        val ty = cy(rowOf(to))

        paint.style = Paint.Style.FILL
        paint.alpha = 255

        linePaint.color = C_HINT
        linePaint.strokeWidth = dp(2.6f)
        canvas.drawCircle(fx, fy, cell * 0.48f, linePaint)

        // 落点：空位画实心绿点，可吃子画绿圈——绿点压在棋子上会挡住字
        if (game.board.cells[to] != 0) {
            linePaint.color = C_HINT
            linePaint.strokeWidth = dp(3f)
            canvas.drawCircle(tx, ty, cell * 0.44f, linePaint)
        } else {
            paint.color = C_HINT
            paint.alpha = 55
            canvas.drawCircle(tx, ty, cell * 0.20f, paint)
            paint.alpha = 255
            canvas.drawCircle(tx, ty, cell * 0.12f, paint)
        }

        val dx = tx - fx
        val dy = ty - fy
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len

        val head = cell * 0.30f
        val sx = fx + ux * cell * 0.54f
        val sy = fy + uy * cell * 0.54f
        val ex = tx - ux * cell * 0.54f
        val ey = ty - uy * cell * 0.54f

        linePaint.strokeWidth = cell * 0.115f
        canvas.drawLine(sx, sy, ex - ux * head * 0.7f, ey - uy * head * 0.7f, linePaint)

        val px = -uy
        val py = ux
        hintPath.reset()
        hintPath.moveTo(ex, ey)
        hintPath.lineTo(ex - ux * head + px * head * 0.72f, ey - uy * head + py * head * 0.72f)
        hintPath.lineTo(ex - ux * head - px * head * 0.72f, ey - uy * head - py * head * 0.72f)
        hintPath.close()
        paint.color = C_HINT
        canvas.drawPath(hintPath, paint)
    }

    private fun drawPieceAt(
        canvas: Canvas,
        piece: Int,
        x: Float,
        y: Float,
        size: Float,
        rotateBlack: Boolean = mode == Mode.PVP
    ) {
        val bmp = pieceBitmaps[piece] ?: return
        val h = size / 2f
        tmpRect2.set(x - h, y - h, x + h, y + h)
        // 双人对弈时两人对坐，黑方棋子转 180°，让对面的人正着看
        if (rotateBlack && sideOf(piece) == Side.BLACK) {
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
        // 上排永远是坐在上方那一方的战绩：执黑翻转后上方变成红方
        val topSide = if (flipped) Side.RED else Side.BLACK
        drawCapturedRow(
            canvas, game.capturedPieces(topSide), above,
            mode == Mode.PVP, "${Side.name(topSide)}吃子"
        )
        drawCapturedRow(
            canvas, game.capturedPieces(Side.other(topSide)), below,
            false, "${Side.name(Side.other(topSide))}吃子"
        )
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
        textPaint.typeface = SANS
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
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(11f)
        textPaint.color = 0x99B7A489.toInt()
        textPaint.letterSpacing = 0.16f
        canvas.drawText("棋谱", left + dp(14f), headerBaseline, textPaint)
        textPaint.letterSpacing = 0f
        textPaint.typeface = SANS

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = dp(11f)
        textPaint.color = 0x66B7A489
        val removedCount = game.removedPieces.size
        val countText = if (removedCount > 0) {
            "让${removedCount}子 · 共 ${game.moveCount} 步"
        } else {
            "共 ${game.moveCount} 步"
        }
        canvas.drawText(countText, right - dp(14f), headerBaseline, textPaint)

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

        val visible = game.visiblePly
        if (visible == 0) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = dp(12.5f)
            textPaint.color = 0x44B7A489
            val cyText = (rowsTop + rowsBottom) / 2f
            val fm0 = textPaint.fontMetrics
            canvas.drawText(
                if (game.reviewing) "开局局面" else "走子后在这里记录中文棋谱",
                (left + right) / 2f,
                cyText - (fm0.ascent + fm0.descent) / 2f,
                textPaint
            )
            return
        }
        val totalRows = (visible + 1) / 2
        val firstRow = (totalRows - rows).coerceAtLeast(0)

        textPaint.textSize = dp(13f)
        val fm = textPaint.fontMetrics
        val numX = left + dp(16f)
        val redX = left + dp(48f)
        val blackX = left + (right - left) * 0.52f
        val highlight = visible - 1

        for (row in firstRow until totalRows) {
            val y = rowsTop + rowH * (row - firstRow) + rowH / 2f
            val baseline = y - (fm.ascent + fm.descent) / 2f

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = 0x55B7A489
            canvas.drawText("${row + 1}.", numX, baseline, textPaint)

            val i0 = row * 2
            if (i0 < visible) {
                textPaint.color = if (i0 == highlight) C_RED else 0xCCC0392B.toInt()
                canvas.drawText(game.moveAt(i0), redX, baseline, textPaint)
            }
            if (i0 + 1 < visible) {
                textPaint.color = if (i0 + 1 == highlight) 0xFFFFFFFF.toInt() else 0xCCB9C6D2.toInt()
                canvas.drawText(game.moveAt(i0 + 1), blackX, baseline, textPaint)
            }
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val w = width.toFloat()
        val y = height - bottomInset - bottomBarH / 2f

        val turn = game.sideToMove
        val now = SystemClock.uptimeMillis()
        val showNotice = notice.isNotEmpty() && now < noticeUntil
        val (label, color) = when {
            game.reviewing -> if (game.reviewPly == 0) {
                "复盘 · 开局" to C_GOLD
            } else {
                "第 ${game.reviewPly} 步 · ${game.moveAt(game.reviewPly - 1)}" to C_GOLD
            }
            showNotice -> notice to C_HINT_TEXT
            game.finished -> when (game.winner) {
                2 -> "和棋" to C_GOLD
                Side.RED -> "红方胜" to C_RED
                else -> "黑方胜" to 0xFF9FB3C8.toInt()
            }
            thinking -> "电脑思考中…" to C_GOLD
            hintThinking -> "正在计算提示…" to C_GOLD
            hintName.isNotEmpty() && game.inCheck -> "将军！提示：$hintName" to C_HINT_TEXT
            hintName.isNotEmpty() -> "提示：$hintName" to C_HINT_TEXT
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

        // 双人复盘时右下角放「棋谱」；对局中不放按钮，存档只在退出时问
        if (mode == Mode.PVP && game.reviewing) {
            saveRect.set(w - dp(12f) - dp(52f), y - dp(13f), w - dp(12f), y + dp(13f))
            drawPill(canvas, saveRect, if (logVisible) "收起" else "棋谱", true)
        } else {
            saveRect.setEmpty()
        }
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

        val pvp = mode == Mode.PVP
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = SANS
        textPaint.textSize = dp(12f)
        textPaint.color = C_TEXT_DIM
        val hint = if (pvp) "保存后下次继续，不保存则重新开始" else "当前棋局不会保存"
        canvas.drawText(hint, w / 2f, top + dp(94f), textPaint)

        val btnH = dp(42f)
        val by = top + cardH - dp(22f) - btnH
        if (pvp) {
            // 双人：取消 / 不保存 / 保存（保存后才写盘，不自动存）
            confirmOkRect.setEmpty()
            val btnW = (cardW - dp(52f)) / 3f
            confirmCancelRect.set(left + dp(16f), by, left + dp(16f) + btnW, by + btnH)
            confirmDiscardRect.set(
                confirmCancelRect.right + dp(10f), by,
                confirmCancelRect.right + dp(10f) + btnW, by + btnH
            )
            confirmSaveRect.set(
                confirmDiscardRect.right + dp(10f), by,
                confirmDiscardRect.right + dp(10f) + btnW, by + btnH
            )
            drawDialogButton(canvas, confirmCancelRect, "取消", false, btnH)
            drawDialogButton(canvas, confirmDiscardRect, "不保存", false, btnH)
            drawDialogButton(canvas, confirmSaveRect, "保存", true, btnH)
        } else {
            confirmDiscardRect.setEmpty()
            confirmSaveRect.setEmpty()
            val btnW = (cardW - dp(48f)) / 2f
            confirmCancelRect.set(left + dp(16f), by, left + dp(16f) + btnW, by + btnH)
            confirmOkRect.set(
                confirmCancelRect.right + dp(16f), by,
                confirmCancelRect.right + dp(16f) + btnW, by + btnH
            )
            drawDialogButton(canvas, confirmCancelRect, "取消", false, btnH)
            drawDialogButton(canvas, confirmOkRect, "退出", true, btnH)
        }
        textPaint.typeface = SANS
    }

    private fun drawDialogButton(canvas: Canvas, r: RectF, label: String, filled: Boolean, btnH: Float) {
        if (filled) {
            paint.color = C_GOLD
            canvas.drawRoundRect(r, btnH / 2f, btnH / 2f, paint)
            textPaint.color = 0xFF2A1E14.toInt()
        } else {
            paint.color = 0x14FFFFFF
            canvas.drawRoundRect(r, btnH / 2f, btnH / 2f, paint)
            linePaint.color = 0x55E2BE79
            linePaint.strokeWidth = dp(1f)
            canvas.drawRoundRect(r, btnH / 2f, btnH / 2f, linePaint)
            textPaint.color = C_GOLD
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(14f)
        val fm = textPaint.fontMetrics
        canvas.drawText(label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
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
        val gap = dp(10f)
        val sidePad = dp(20f)
        val btnW = (cardW - sidePad * 2 - gap * 2) / 3f
        val by = top + cardH - dp(28f) - btnH
        againRect.set(left + sidePad, by, left + sidePad + btnW, by + btnH)
        reviewRect.set(againRect.right + gap, by, againRect.right + gap + btnW, by + btnH)
        toMenuRect.set(reviewRect.right + gap, by, reviewRect.right + gap + btnW, by + btnH)

        paint.color = C_GOLD
        canvas.drawRoundRect(againRect, btnH / 2f, btnH / 2f, paint)
        textPaint.color = 0xFF2A1E14.toInt()
        textPaint.typeface = SANS_MEDIUM
        textPaint.textSize = dp(13.5f)
        val fm1 = textPaint.fontMetrics
        canvas.drawText(
            "再来一局", againRect.centerX(),
            againRect.centerY() - (fm1.ascent + fm1.descent) / 2f, textPaint
        )

        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(reviewRect, btnH / 2f, btnH / 2f, paint)
        linePaint.color = 0x88E2BE79.toInt()
        linePaint.strokeWidth = dp(1.1f)
        canvas.drawRoundRect(reviewRect, btnH / 2f, btnH / 2f, linePaint)
        textPaint.color = C_GOLD
        canvas.drawText(
            "复盘", reviewRect.centerX(),
            reviewRect.centerY() - (fm1.ascent + fm1.descent) / 2f, textPaint
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
        textPaint.typeface = SANS
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
            pieceBitmaps[piece] = PieceArt.render(kai, piece, side, kind)
        }
        pieceBmpReady = true
    }

    private companion object {
        /** 双人模式暂行存档：只留一个槽位，新的一局会覆盖 */
        const val SAVE_KEY = "pvp_save"
        const val SAVE_PLY_KEY = "pvp_save_ply"
        const val SAVE_PREFIX = "v1:"
        /** 存档里的让子（开局被拿掉的格子） */
        const val SAVE_REMOVED_KEY = "pvp_save_removed"
        /** 开局设置：我执哪一方、人机 / 双人各自的让子 */
        const val KEY_AI_SIDE = "ai_side"
        const val KEY_AI_REMOVED = "ai_removed"
        const val KEY_PVP_REMOVED = "pvp_removed"
    }
}
