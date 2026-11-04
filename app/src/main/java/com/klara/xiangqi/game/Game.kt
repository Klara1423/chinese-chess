package com.klara.xiangqi.game

/**
 * 一局棋：负责落子、悔棋、胜负判定（将死、困毙、重复局面、60 回合无吃子）。
 */
class Game {

    val board = Board()

    private class Record(val move: Int, val piece: Int, val captured: Int)

    private val history = ArrayList<Record>(128)
    private val hashes = ArrayList<Long>(128)
    private val notations = ArrayList<String>(128)

    var lastMove: Int = -1
        private set

    /** 上一步被移动的棋子（画「残影」用；0 表示没有） */
    var lastMovedPiece: Int = 0
        private set

    /** true 表示已结束 */
    var finished: Boolean = false
        private set

    /** -1 进行中 / 0 红胜 / 1 黑胜 / 2 和棋 */
    var winner: Int = -1
        private set

    /** 结束原因：0 未结束 / 1 将死 / 2 三次重复 / 3 久无吃子 / 4 认输 / 5 协议和棋 / 6 困毙 */
    var endReason: Int = 0
        private set

    /**
     * 复盘光标：-1 表示正在看实时局面（或棋局还在进行），
     * 否则是当前显示到第几步（0 = 开局，history.size = 终局）。
     */
    var reviewPly: Int = -1
        private set

    /** 是否处于复盘状态（棋盘暂时回退到历史某一步，历史本身不动） */
    val reviewing: Boolean get() = reviewPly >= 0

    /** 当前应该显示到第几步：复盘看光标，平时看全部历史 */
    val visiblePly: Int get() = if (reviewPly >= 0) reviewPly else history.size

    private var quietPlies: Int = 0

    init {
        hashes.add(board.hash)
    }

    val sideToMove: Int get() = board.sideToMove

    fun reset() {
        board.reset()
        history.clear()
        hashes.clear()
        notations.clear()
        hashes.add(board.hash)
        lastMove = -1
        lastMovedPiece = 0
        finished = false
        winner = -1
        endReason = 0
        quietPlies = 0
        reviewPly = -1
    }

    /** 该格子上的棋子是否可以由当前行棋方操作 */
    fun isOwnPiece(sq: Int): Boolean {
        val p = board.cells[sq]
        return p != 0 && sideOf(p) == board.sideToMove
    }

    /** 当前行棋方在某格棋子的全部合法落点 */
    fun legalTargets(from: Int): IntArray {
        if (finished) return IntArray(0)
        if (from < 0 || from >= BOARD_SIZE) return IntArray(0)
        if (!isOwnPiece(from)) return IntArray(0)
        val buf = IntArray(160)
        val n = MoveGen.generate(board, buf)
        var count = 0
        val out = IntArray(n)
        val us = board.sideToMove
        for (i in 0 until n) {
            val m = buf[i]
            if (MoveGen.fromOf(m) != from) continue
            if (MoveGen.isLegal(board, m, us)) out[count++] = MoveGen.toOf(m)
        }
        return out.copyOf(count)
    }

    /** 落子，返回是否成功 */
    fun play(from: Int, to: Int): Boolean {
        if (finished || reviewing) return false
        if (from < 0 || from >= BOARD_SIZE || to < 0 || to >= BOARD_SIZE) return false
        if (!isOwnPiece(from)) return false
        val move = MoveGen.makeMove(from, to)
        if (!MoveGen.isLegal(board, move, board.sideToMove)) return false

        // 记谱必须在走子之前根据当前局面生成
        val notation = MoveNamer.name(board, move)
        val moving = board.cells[from]
        val captured = board.make(move)
        history.add(Record(move, moving, captured))
        notations.add(notation)
        hashes.add(board.hash)
        lastMove = move
        lastMovedPiece = moving
        quietPlies = if (captured != 0) 0 else quietPlies + 1
        updateStatus()
        return true
    }

    fun canUndo(): Boolean = history.isNotEmpty() && !reviewing

    /** 已走步数 */
    val plyCount: Int get() = history.size

    /**
     * 某一方吃掉的全部棋子（按吃掉的先后顺序）。
     * 被吃子的阵营必然是对手，所以不用额外记「谁吃的」。
     * 复盘时只统计到当前光标那一步，和棋盘上的局面保持一致。
     */
    fun capturedPieces(side: Int): IntArray {
        val out = IntArray(16)
        var n = 0
        for (i in 0 until visiblePly) {
            val captured = history[i].captured
            if (captured == 0) continue
            if (Side.other(sideOf(captured)) == side) out[n++] = captured
        }
        return out.copyOf(n)
    }

    val moveCount: Int get() = notations.size

    fun moveAt(index: Int): String =
        if (index in notations.indices) notations[index] else ""

    // ---------- 存档 / 复盘 ----------

    /** 存档用：按顺序导出历史着法（棋盘、棋谱、吃子记录都能由它重放出来） */
    fun movesSnapshot(): IntArray {
        val out = IntArray(history.size)
        for (i in history.indices) out[i] = history[i].move
        return out
    }

    /** 从存档恢复：重放整局，重建棋盘、棋谱、吃子记录和胜负状态 */
    fun restoreMoves(moves: IntArray): Boolean {
        reset()
        for (m in moves) {
            if (!play(MoveGen.fromOf(m), MoveGen.toOf(m))) {
                reset()
                return false
            }
        }
        return true
    }

    /** 进入复盘：棋盘回到终局，之后用 reviewPrev / reviewNext 前后翻 */
    fun enterReview(): Boolean {
        if (history.isEmpty()) return false
        reviewPly = history.size
        rebuildTo(reviewPly)
        return true
    }

    /** 复盘上一步，成功返回 true */
    fun reviewPrev(): Boolean {
        if (reviewPly <= 0) return false
        reviewPly--
        rebuildTo(reviewPly)
        return true
    }

    /** 复盘下一步，成功返回 true */
    fun reviewNext(): Boolean {
        if (reviewPly < 0 || reviewPly >= history.size) return false
        reviewPly++
        rebuildTo(reviewPly)
        return true
    }

    /** 退出复盘，棋盘恢复到终局 */
    fun exitReview() {
        if (reviewPly < 0) return
        reviewPly = -1
        rebuildTo(history.size)
        quietPlies = computeQuietPlies()
    }

    /**
     * 把棋盘重放到第 ply 步（0 = 开局）。只动棋盘和「上一步」标记，
     * 不动历史 / 棋谱 / 胜负结果，所以前后翻页不会丢着法。
     */
    private fun rebuildTo(ply: Int) {
        board.reset()
        for (i in 0 until ply) board.make(history[i].move)
        lastMove = if (ply == 0) -1 else history[ply - 1].move
        lastMovedPiece = if (ply == 0) 0 else history[ply - 1].piece
    }

    /** 悔棋一步 */
    fun undo(): Boolean {
        if (reviewing) return false
        if (history.isEmpty()) return false
        val rec = history.removeAt(history.size - 1)
        board.unmake(rec.move, rec.captured)
        if (hashes.size > 1) hashes.removeAt(hashes.size - 1)
        if (notations.isNotEmpty()) notations.removeAt(notations.size - 1)
        lastMove = if (history.isEmpty()) -1 else history[history.size - 1].move
        lastMovedPiece = if (history.isEmpty()) 0 else history[history.size - 1].piece
        finished = false
        winner = -1
        endReason = 0
        quietPlies = computeQuietPlies()
        return true
    }

    /**
     * 当前行棋方认输。
     *
     * 1.2 起界面上的「认输」入口已删除（双人同屏对坐，只有下方那侧够得着按钮，做不到对等），
     * 这里保留判定逻辑，等 `TODO.md` 里的「双人对等」方案定了再接回界面。
     */
    fun resign() {
        if (finished) return
        winner = Side.other(board.sideToMove)
        finished = true
        endReason = 4
    }

    /** 双方同意和棋（同 [resign]：界面入口已删，逻辑留着等「双人对等」方案） */
    fun agreeDraw() {
        if (finished) return
        winner = 2
        finished = true
        endReason = 5
    }

    /** 某方子力总值（用于电脑判断要不要接受和棋） */
    fun material(side: Int): Int {
        var sum = 0
        for (sq in 0 until BOARD_SIZE) {
            val p = board.cells[sq]
            if (p == 0 || sideOf(p) != side) continue
            sum += when (kindOf(p)) {
                Kind.KING -> 0
                Kind.ROOK -> 900
                Kind.CANNON -> 450
                Kind.HORSE -> 400
                Kind.ADVISOR, Kind.ELEPHANT -> 200
                else -> 100
            }
        }
        return sum
    }

    private fun computeQuietPlies(): Int {
        var n = 0
        for (i in history.indices.reversed()) {
            if (history[i].captured != 0) break
            n++
        }
        return n
    }

    val inCheck: Boolean
        get() = MoveGen.inCheck(board, board.sideToMove)

    fun kingSquare(side: Int): Int = board.kingSq(side)

    private fun updateStatus() {
        if (!MoveGen.hasLegalMove(board, board.sideToMove)) {
            // 象棋规则：被将死或困毙均判负
            finished = true
            winner = Side.other(board.sideToMove)
            endReason = if (MoveGen.inCheck(board, board.sideToMove)) 1 else 6
            return
        }
        if (repetitionCount(board.hash) >= 3) {
            finished = true
            winner = 2
            endReason = 2
            return
        }
        if (quietPlies >= 120) {
            finished = true
            winner = 2
            endReason = 3
            return
        }
        finished = false
        winner = -1
        endReason = 0
    }

    private fun repetitionCount(h: Long): Int {
        var c = 0
        for (i in hashes.indices) {
            if (hashes[i] == h) c++
        }
        return c
    }
}
