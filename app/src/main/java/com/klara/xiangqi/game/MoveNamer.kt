package com.klara.xiangqi.game

import kotlin.math.abs

/**
 * 中文记谱：如「炮二平五」「马8进7」「前车进三」。
 * 红方用汉字数字（自右向左 1-9），黑方用阿拉伯数字（自左向右 1-9）。
 */
object MoveNamer {

    private val CN = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    private fun num(side: Int, n: Int): String =
        if (side == Side.RED) CN[(n - 1).coerceIn(0, 8)] else n.toString()

    fun name(board: Board, move: Int): String {
        val from = MoveGen.fromOf(move)
        val to = MoveGen.toOf(move)
        val piece = board.cells[from]
        if (piece == 0) return ""

        val side = sideOf(piece)
        val kind = kindOf(piece)
        val fr = rowOf(from)
        val fc = colOf(from)
        val tr = rowOf(to)
        val tc = colOf(to)

        fun fileNo(c: Int) = if (side == Side.RED) 9 - c else c + 1

        var sameFile = 0
        var ahead = 0
        for (sq in 0 until BOARD_SIZE) {
            if (sq == from) continue
            val p = board.cells[sq]
            if (p == 0 || sideOf(p) != side || kindOf(p) != kind) continue
            if (colOf(sq) != fc) continue
            sameFile++
            val sr = rowOf(sq)
            if ((side == Side.RED && sr < fr) || (side == Side.BLACK && sr > fr)) ahead++
        }

        val base = glyphOf(piece)
        val label = if (sameFile > 0) {
            if (ahead == 0) "前$base" else "后$base"
        } else {
            base + num(side, fileNo(fc))
        }

        return if (tr == fr) {
            label + "平" + num(side, fileNo(tc))
        } else {
            val forward = if (side == Side.RED) tr < fr else tr > fr
            val action = if (forward) "进" else "退"
            val value = if (kind == Kind.HORSE || kind == Kind.ADVISOR || kind == Kind.ELEPHANT) {
                num(side, fileNo(tc))
            } else {
                num(side, abs(tr - fr))
            }
            label + action + value
        }
    }
}
