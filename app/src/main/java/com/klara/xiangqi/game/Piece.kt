package com.klara.xiangqi.game

/** 阵营 */
object Side {
    const val RED = 0
    const val BLACK = 1
    fun other(s: Int): Int = s xor 1
    fun name(s: Int): String = if (s == RED) "红方" else "黑方"
}

/** 兵种 */
object Kind {
    const val NONE = 0
    const val KING = 1
    const val ADVISOR = 2
    const val ELEPHANT = 3
    const val HORSE = 4
    const val ROOK = 5
    const val CANNON = 6
    const val PAWN = 7
}

/** 棋子编码：高 bit 存阵营，低 3 bit 存兵种；0 表示空 */
fun pieceOf(side: Int, kind: Int): Int = (side shl 3) or kind

fun sideOf(piece: Int): Int = (piece shr 3) and 1

fun kindOf(piece: Int): Int = piece and 7

private val GLYPHS = arrayOf(
    arrayOf("", "帥", "仕", "相", "馬", "車", "炮", "兵"),
    arrayOf("", "將", "士", "象", "馬", "車", "炮", "卒")
)

/** 棋子上的汉字 */
fun glyphOf(piece: Int): String =
    if (piece == 0) "" else GLYPHS[sideOf(piece)][kindOf(piece)]

/** 兵种基础价值（车 > 炮 ≈ 马 > 士象 > 兵） */
val PIECE_VALUES = intArrayOf(0, 0, 220, 220, 450, 1000, 500, 100)

/** 棋盘格坐标：row 0 为黑方底线，row 9 为红方底线 */
const val BOARD_W = 9
const val BOARD_H = 10
const val BOARD_SIZE = BOARD_W * BOARD_H

fun squareOf(row: Int, col: Int): Int = row * BOARD_W + col
fun rowOf(sq: Int): Int = sq / BOARD_W
fun colOf(sq: Int): Int = sq % BOARD_W

fun inBoard(row: Int, col: Int): Boolean =
    row in 0 until BOARD_H && col in 0 until BOARD_W

/** 九宫范围 */
fun inPalace(sq: Int, side: Int): Boolean {
    val r = rowOf(sq)
    val c = colOf(sq)
    if (c < 3 || c > 5) return false
    return if (side == Side.RED) r in 7..9 else r in 0..2
}
