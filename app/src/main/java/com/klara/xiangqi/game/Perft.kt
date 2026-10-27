package com.klara.xiangqi.game

/** 调试用：统计固定深度下的合法局面数，用于校验规则实现 */
object Perft {

    fun count(board: Board, depth: Int): Long {
        if (depth == 0) return 1L
        val buf = IntArray(160)
        val n = MoveGen.generate(board, buf)
        val us = board.sideToMove
        var total = 0L
        for (i in 0 until n) {
            val m = buf[i]
            val captured = board.make(m)
            if (!MoveGen.isAttacked(board, board.kingSq(us), Side.other(us))) {
                total += count(board, depth - 1)
            }
            board.unmake(m, captured)
        }
        return total
    }
}
