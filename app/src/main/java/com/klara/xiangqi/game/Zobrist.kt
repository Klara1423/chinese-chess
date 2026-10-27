package com.klara.xiangqi.game

/** Zobrist 置换表哈希，固定种子保证同机可复现 */
object Zobrist {
    private val rnd = java.util.Random(20260607L)

    /** [格子][棋子编码] */
    val piece: Array<LongArray> = Array(BOARD_SIZE) { LongArray(16) { rnd.nextLong() } }
    val side: Long = rnd.nextLong()

    fun compute(board: Board): Long {
        var h = 0L
        val cells = board.cells
        for (i in 0 until BOARD_SIZE) {
            val p = cells[i]
            if (p != 0) h = h xor piece[i][p]
        }
        if (board.sideToMove == Side.BLACK) h = h xor side
        return h
    }
}
