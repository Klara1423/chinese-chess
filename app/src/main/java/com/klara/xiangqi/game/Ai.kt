package com.klara.xiangqi.game

import kotlin.math.abs

/** 难度档位：搜索深度 / 思考时间 / 估值扰动 / 随手棋概率 */
enum class Difficulty(
    val label: String,
    val depth: Int,
    val timeMs: Long,
    val jitter: Int,
    val blunder: Int
) {
    BEGINNER("入门", 1, 150L, 140, 30),
    EASY("简单", 2, 400L, 80, 12),
    NORMAL("普通", 4, 900L, 0, 0),
    HARD("困难", 6, 1800L, 0, 0),
    MASTER("大师", 9, 3600L, 0, 0);

    companion object {
        fun fromName(name: String?): Difficulty =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Alpha-Beta 搜索引擎：
 * 迭代加深 + 置换表 + MVV-LVA + 杀手着法 + 历史启发 + 静态搜索。
 */
class Ai {

    private companion object {
        const val INF = 1_000_000
        const val MATE = 30_000
        const val TT_BITS = 18
        const val TT_SIZE = 1 shl TT_BITS
        const val TT_MASK = TT_SIZE - 1
        const val MAX_PLY = 60
    }

    private val ttKey = LongArray(TT_SIZE)
    private val ttMove = IntArray(TT_SIZE)
    private val ttScore = ShortArray(TT_SIZE)
    private val ttDepth = ByteArray(TT_SIZE)
    private val ttFlag = ByteArray(TT_SIZE)

    private val killers = Array(MAX_PLY) { IntArray(2) }
    private val historyTable = IntArray(BOARD_SIZE * BOARD_SIZE)

    private val moveStack = Array(MAX_PLY) { IntArray(160) }
    private val orderStack = Array(MAX_PLY) { IntArray(160) }
    private val usedStack = Array(MAX_PLY) { BooleanArray(160) }

    private val rootMoves = IntArray(160)
    private val rootScores = IntArray(160)
    private var rootCount = 0

    private var nodes = 0L
    private var deadlineNs = 0L
    private var aborted = false
    private var fullWindowRoot = false
    private val rng = java.util.Random()

    /** 上一次搜索实际到达的深度，供调试输出 */
    var lastDepth: Int = 0
        private set
    var lastNodes: Long = 0
        private set

    fun chooseMove(board: Board, difficulty: Difficulty): Int {
        nodes = 0
        aborted = false
        fullWindowRoot = difficulty.jitter > 0
        deadlineNs = System.nanoTime() + difficulty.timeMs * 1_000_000L
        clearTables()

        var best = 0
        var hint = 0
        var lastScore = 0
        var reached = 0

        for (depth in 1..difficulty.depth) {
            if (!searchRoot(board, depth, hint)) break
            if (rootCount == 0) break

            var bi = 0
            var bs = Int.MIN_VALUE
            for (i in 0 until rootCount) {
                val s = if (difficulty.jitter > 0) {
                    rootScores[i] + rng.nextInt(difficulty.jitter * 2 + 1) - difficulty.jitter
                } else {
                    rootScores[i]
                }
                if (s > bs) {
                    bs = s
                    bi = i
                }
            }
            best = rootMoves[bi]
            lastScore = bs
            hint = best
            reached = depth
            if (abs(lastScore) > MATE - 200) break
            if (System.nanoTime() > deadlineNs) break
        }

        if (best == 0) best = fallbackMove(board)

        // 低难度偶尔走一步"随手棋"，这是档位差距的主要来源
        if (difficulty.blunder > 0 && rng.nextInt(100) < difficulty.blunder) {
            val rnd = randomLegalMove(board)
            if (rnd != 0) best = rnd
        }

        lastDepth = reached
        lastNodes = nodes
        return best
    }

    private fun randomLegalMove(board: Board): Int {
        val buf = IntArray(160)
        val n = MoveGen.generate(board, buf)
        val us = board.sideToMove
        var count = 0
        for (i in 0 until n) {
            if (MoveGen.isLegal(board, buf[i], us)) buf[count++] = buf[i]
        }
        if (count == 0) return 0
        return buf[rng.nextInt(count)]
    }

    private fun clearTables() {
        java.util.Arrays.fill(ttFlag, 0)
        java.util.Arrays.fill(historyTable, 0)
        for (k in killers) {
            k[0] = 0
            k[1] = 0
        }
        rootCount = 0
    }

    private fun fallbackMove(board: Board): Int {
        val buf = IntArray(160)
        val n = MoveGen.generate(board, buf)
        val us = board.sideToMove
        for (i in 0 until n) {
            if (MoveGen.isLegal(board, buf[i], us)) return buf[i]
        }
        return 0
    }

    private fun searchRoot(board: Board, depth: Int, hint: Int): Boolean {
        val buf = moveStack[0]
        val order = orderStack[0]
        val used = usedStack[0]
        val n = MoveGen.generate(board, buf)
        val us = board.sideToMove
        for (i in 0 until n) {
            val m = buf[i]
            order[i] = if (m == hint) 1_000_000 else orderScore(board, m, 0, hint)
            used[i] = false
        }

        var alpha = -INF
        var count = 0
        var bestScore = -INF
        var bestMove = 0

        for (step in 0 until n) {
            var bi = -1
            var bs = Int.MIN_VALUE
            for (i in 0 until n) {
                if (!used[i] && order[i] > bs) {
                    bs = order[i]
                    bi = i
                }
            }
            if (bi < 0) break
            used[bi] = true
            val m = buf[bi]
            val captured = board.make(m)
            if (MoveGen.isAttacked(board, board.kingSq(us), Side.other(us))) {
                board.unmake(m, captured)
                continue
            }
            val score = if (fullWindowRoot) {
                -search(board, depth - 1, -INF, INF, 1)
            } else {
                -search(board, depth - 1, -INF, -alpha, 1)
            }
            board.unmake(m, captured)
            if (aborted) return false

            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
            if (score > alpha) alpha = score
            rootMoves[count] = m
            rootScores[count] = score
            count++
        }

        if (count == 0) {
            rootCount = 0
            return true
        }
        rootCount = count
        // 保证第一次迭代一定能给出着法
        if (bestMove == 0) bestMove = rootMoves[0]
        return true
    }

    private fun search(board: Board, depth0: Int, alpha0: Int, beta0: Int, ply: Int): Int {
        if (aborted) return 0
        nodes++
        if ((nodes and 1023L) == 0L && System.nanoTime() > deadlineNs) {
            aborted = true
            return 0
        }
        if (ply >= MAX_PLY - 2) return evaluate(board)

        val us = board.sideToMove
        val checked = MoveGen.isAttacked(board, board.kingSq(us), Side.other(us))
        var depth = depth0
        if (depth <= 0) {
            if (checked && ply < 24) {
                depth = 1
            } else {
                return quiesce(board, alpha0, beta0, ply)
            }
        }

        val key = board.hash
        val idx = hashIndex(key)
        var ttHint = 0
        if (ttFlag[idx].toInt() != 0 && ttKey[idx] == key) {
            ttHint = ttMove[idx]
            if (ttDepth[idx].toInt() >= depth) {
                val tScore = ttScore[idx].toInt()
                when (ttFlag[idx].toInt()) {
                    1 -> return tScore
                    2 -> if (tScore <= alpha0) return tScore
                    3 -> if (tScore >= beta0) return tScore
                }
            }
        }

        val buf = moveStack[ply]
        val order = orderStack[ply]
        val used = usedStack[ply]
        val n = MoveGen.generate(board, buf)
        for (i in 0 until n) {
            order[i] = orderScore(board, buf[i], ply, ttHint)
            used[i] = false
        }

        var alpha = alpha0
        var bestScore = -INF
        var bestMove = 0
        var legal = 0

        for (step in 0 until n) {
            var bi = -1
            var bs = Int.MIN_VALUE
            for (i in 0 until n) {
                if (!used[i] && order[i] > bs) {
                    bs = order[i]
                    bi = i
                }
            }
            if (bi < 0) break
            used[bi] = true
            val m = buf[bi]
            val captured = board.make(m)
            if (MoveGen.isAttacked(board, board.kingSq(us), Side.other(us))) {
                board.unmake(m, captured)
                continue
            }
            legal++
            val score = -search(board, depth - 1, -beta0, -alpha, ply + 1)
            board.unmake(m, captured)
            if (aborted) return 0

            if (score > bestScore) {
                bestScore = score
                bestMove = m
            }
            if (score > alpha) alpha = score
            if (alpha >= beta0) {
                if (captured == 0) {
                    if (killers[ply][0] != m) {
                        killers[ply][1] = killers[ply][0]
                        killers[ply][0] = m
                    }
                    val hi = MoveGen.fromOf(m) * BOARD_SIZE + MoveGen.toOf(m)
                    if (historyTable[hi] < 50_000) historyTable[hi] += depth * depth
                }
                break
            }
        }

        // 无合法着法 = 将死或困毙，均判负
        if (legal == 0) return -MATE + ply

        if (!aborted) {
            val flag = when {
                bestScore <= alpha0 -> 2
                bestScore >= beta0 -> 3
                else -> 1
            }
            ttKey[idx] = key
            ttMove[idx] = bestMove
            ttScore[idx] = bestScore.coerceIn(-32000, 32000).toShort()
            ttDepth[idx] = depth.toByte()
            ttFlag[idx] = flag.toByte()
        }
        return bestScore
    }

    /** 静态搜索：只展开吃子，避免水平线效应 */
    private fun quiesce(board: Board, alpha0: Int, beta0: Int, ply: Int): Int {
        if (aborted) return 0
        nodes++
        if ((nodes and 1023L) == 0L && System.nanoTime() > deadlineNs) {
            aborted = true
            return 0
        }

        val us = board.sideToMove
        val stand = evaluate(board)
        var alpha = alpha0
        if (stand >= beta0) return stand
        if (stand > alpha) alpha = stand
        if (ply >= MAX_PLY - 6) return alpha

        val buf = moveStack[ply]
        val used = usedStack[ply]
        val n = MoveGen.generate(board, buf, capturesOnly = true)
        for (i in 0 until n) used[i] = false

        var best = alpha
        for (step in 0 until n) {
            var bi = -1
            var bv = -1
            for (i in 0 until n) {
                if (used[i]) continue
                val victim = board.cells[MoveGen.toOf(buf[i])]
                val v = if (victim == 0) 0 else PIECE_VALUES[kindOf(victim)]
                if (v > bv) {
                    bv = v
                    bi = i
                }
            }
            if (bi < 0) break
            used[bi] = true
            val m = buf[bi]
            val captured = board.make(m)
            if (MoveGen.isAttacked(board, board.kingSq(us), Side.other(us))) {
                board.unmake(m, captured)
                continue
            }
            val score = -quiesce(board, -beta0, -best, ply + 1)
            board.unmake(m, captured)
            if (aborted) return 0
            if (score > best) best = score
            if (best >= beta0) break
        }
        return best
    }

    private fun orderScore(board: Board, move: Int, ply: Int, ttHint: Int): Int {
        if (move == ttHint) return 1_000_000
        val to = MoveGen.toOf(move)
        val victim = board.cells[to]
        if (victim != 0) {
            val attacker = board.cells[MoveGen.fromOf(move)]
            return 100_000 + PIECE_VALUES[kindOf(victim)] * 8 - PIECE_VALUES[kindOf(attacker)]
        }
        if (move == killers[ply][0]) return 90_000
        if (move == killers[ply][1]) return 80_000
        return historyTable[MoveGen.fromOf(move) * BOARD_SIZE + to]
    }

    private fun hashIndex(key: Long): Int = ((key ushr 24).toInt()) and TT_MASK

    /** 局面评估，返回行棋方视角的分值 */
    private fun evaluate(b: Board): Int {
        var score = 0
        val cells = b.cells
        for (sq in 0 until BOARD_SIZE) {
            val p = cells[sq]
            if (p == 0) continue
            val side = sideOf(p)
            val k = kindOf(p)
            var v = PIECE_VALUES[k]
            val r = rowOf(sq)
            val c = colOf(sq)
            val adv = if (side == Side.RED) 9 - r else r
            val center = 4 - abs(c - 4)
            when (k) {
                Kind.PAWN -> {
                    v += adv * 7
                    if (adv >= 5) v += 30
                    v += center * 2
                }
                Kind.HORSE -> v += adv * 4 + center * 4
                Kind.CANNON -> v += adv * 2 + center * 3
                Kind.ROOK -> v += adv * 3 + center * 2
                Kind.ADVISOR, Kind.ELEPHANT -> if (adv >= 5) v += 6
                else -> {}
            }
            score += if (side == Side.RED) v else -v
        }
        return if (b.sideToMove == Side.RED) score else -score
    }
}
