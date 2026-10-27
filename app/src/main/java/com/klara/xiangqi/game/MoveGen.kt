package com.klara.xiangqi.game

/**
 * 走法生成与受攻击判定。
 *
 * 走法用 Int 编码：(起点 shl 8) or 终点，避免搜索时产生大量对象。
 */
object MoveGen {

    fun makeMove(from: Int, to: Int): Int = (from shl 8) or to

    fun fromOf(move: Int): Int = move ushr 8

    fun toOf(move: Int): Int = move and 0xFF

    private val ORTHO_R = intArrayOf(-1, 1, 0, 0)
    private val ORTHO_C = intArrayOf(0, 0, -1, 1)
    private val DIAG_R = intArrayOf(-1, -1, 1, 1)
    private val DIAG_C = intArrayOf(-1, 1, -1, 1)
    private val HORSE_R = intArrayOf(-2, -2, -1, -1, 1, 1, 2, 2)
    private val HORSE_C = intArrayOf(-1, 1, -2, 2, -2, 2, -1, 1)

    /** 生成当前行棋方的伪合法走法，返回个数 */
    fun generate(board: Board, out: IntArray, capturesOnly: Boolean = false): Int {
        var n = 0
        val cells = board.cells
        val us = board.sideToMove
        for (sq in 0 until BOARD_SIZE) {
            val p = cells[sq]
            if (p == 0 || sideOf(p) != us) continue
            n = when (kindOf(p)) {
                Kind.KING -> genKing(board, sq, out, n, capturesOnly)
                Kind.ADVISOR -> genAdvisor(board, sq, out, n, capturesOnly)
                Kind.ELEPHANT -> genElephant(board, sq, out, n, capturesOnly)
                Kind.HORSE -> genHorse(board, sq, out, n, capturesOnly)
                Kind.ROOK -> genRook(board, sq, out, n, capturesOnly)
                Kind.CANNON -> genCannon(board, sq, out, n, capturesOnly)
                Kind.PAWN -> genPawn(board, sq, out, n, capturesOnly)
                else -> n
            }
        }
        return n
    }

    private fun add(board: Board, out: IntArray, n: Int, from: Int, to: Int, capturesOnly: Boolean): Int {
        val target = board.cells[to]
        if (target != 0 && sideOf(target) == board.sideToMove) return n
        if (capturesOnly && target == 0) return n
        out[n] = makeMove(from, to)
        return n + 1
    }

    private fun genKing(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        val us = board.sideToMove
        for (d in 0 until 4) {
            val nr = r + ORTHO_R[d]
            val nc = c + ORTHO_C[d]
            if (!inBoard(nr, nc)) continue
            val to = squareOf(nr, nc)
            if (!inPalace(to, us)) continue
            n = add(board, out, n, sq, to, capOnly)
        }
        return n
    }

    private fun genAdvisor(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        val us = board.sideToMove
        for (d in 0 until 4) {
            val nr = r + DIAG_R[d]
            val nc = c + DIAG_C[d]
            if (!inBoard(nr, nc)) continue
            val to = squareOf(nr, nc)
            if (!inPalace(to, us)) continue
            n = add(board, out, n, sq, to, capOnly)
        }
        return n
    }

    private fun genElephant(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        val us = board.sideToMove
        for (d in 0 until 4) {
            val nr = r + DIAG_R[d] * 2
            val nc = c + DIAG_C[d] * 2
            if (!inBoard(nr, nc)) continue
            // 象不过河
            if (us == Side.RED) {
                if (nr < 5) continue
            } else {
                if (nr > 4) continue
            }
            // 塞象眼
            if (board.cells[squareOf(r + DIAG_R[d], c + DIAG_C[d])] != 0) continue
            n = add(board, out, n, sq, squareOf(nr, nc), capOnly)
        }
        return n
    }

    private fun genHorse(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        for (d in 0 until 8) {
            val nr = r + HORSE_R[d]
            val nc = c + HORSE_C[d]
            if (!inBoard(nr, nc)) continue
            // 蹩马腿：马在长轴方向旁的格被占则不可走
            val legR = if (HORSE_R[d] == 2) r + 1 else if (HORSE_R[d] == -2) r - 1 else r
            val legC = if (HORSE_C[d] == 2) c + 1 else if (HORSE_C[d] == -2) c - 1 else c
            if (board.cells[squareOf(legR, legC)] != 0) continue
            n = add(board, out, n, sq, squareOf(nr, nc), capOnly)
        }
        return n
    }

    private fun genRook(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        for (d in 0 until 4) {
            var nr = r + ORTHO_R[d]
            var nc = c + ORTHO_C[d]
            while (inBoard(nr, nc)) {
                val to = squareOf(nr, nc)
                val target = board.cells[to]
                if (target == 0) {
                    if (!capOnly) {
                        out[n] = makeMove(sq, to)
                        n++
                    }
                } else {
                    if (sideOf(target) != board.sideToMove) {
                        out[n] = makeMove(sq, to)
                        n++
                    }
                    break
                }
                nr += ORTHO_R[d]
                nc += ORTHO_C[d]
            }
        }
        return n
    }

    private fun genCannon(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        for (d in 0 until 4) {
            var nr = r + ORTHO_R[d]
            var nc = c + ORTHO_C[d]
            var screen = false
            while (inBoard(nr, nc)) {
                val to = squareOf(nr, nc)
                val target = board.cells[to]
                if (!screen) {
                    if (target == 0) {
                        if (!capOnly) {
                            out[n] = makeMove(sq, to)
                            n++
                        }
                    } else {
                        screen = true
                    }
                } else {
                    if (target != 0) {
                        if (sideOf(target) != board.sideToMove) {
                            out[n] = makeMove(sq, to)
                            n++
                        }
                        break
                    }
                }
                nr += ORTHO_R[d]
                nc += ORTHO_C[d]
            }
        }
        return n
    }

    private fun genPawn(board: Board, sq: Int, out: IntArray, n0: Int, capOnly: Boolean): Int {
        var n = n0
        val r = rowOf(sq)
        val c = colOf(sq)
        val us = board.sideToMove
        val forward = if (us == Side.RED) -1 else 1
        val fr = r + forward
        if (fr in 0 until BOARD_H) {
            n = add(board, out, n, sq, squareOf(fr, c), capOnly)
        }
        val crossed = if (us == Side.RED) r <= 4 else r >= 5
        if (crossed) {
            if (c - 1 >= 0) n = add(board, out, n, sq, squareOf(r, c - 1), capOnly)
            if (c + 1 < BOARD_W) n = add(board, out, n, sq, squareOf(r, c + 1), capOnly)
        }
        return n
    }

    /**
     * sq 是否被 by 方攻击。包含将帅照面规则：同列且中间无子时视作互相攻击。
     */
    fun isAttacked(board: Board, sq: Int, by: Int): Boolean {
        val cells = board.cells
        val r = rowOf(sq)
        val c = colOf(sq)
        val targetIsKing = kindOf(cells[sq]) == Kind.KING

        // 车、炮、将（贴身或照面）
        for (d in 0 until 4) {
            var nr = r + ORTHO_R[d]
            var nc = c + ORTHO_C[d]
            var screen = false
            while (inBoard(nr, nc)) {
                val p = cells[squareOf(nr, nc)]
                if (p != 0) {
                    val k = kindOf(p)
                    if (!screen) {
                        if (sideOf(p) == by) {
                            if (k == Kind.ROOK) return true
                            if (k == Kind.KING) {
                                // 贴身
                                if (kotlin.math.abs(nr - r) + kotlin.math.abs(nc - c) == 1) return true
                                // 照面：同列且中间无子
                                if (targetIsKing && nc == c) return true
                            }
                        }
                        screen = true
                    } else {
                        if (sideOf(p) == by && k == Kind.CANNON) return true
                        break
                    }
                }
                nr += ORTHO_R[d]
                nc += ORTHO_C[d]
            }
        }

        // 马（反向推算马的位置与马腿）
        for (d in 0 until 8) {
            val hr = r + HORSE_R[d]
            val hc = c + HORSE_C[d]
            if (!inBoard(hr, hc)) continue
            val hp = cells[squareOf(hr, hc)]
            if (hp == 0 || sideOf(hp) != by || kindOf(hp) != Kind.HORSE) continue
            // 从马指向 sq 的腿
            val legR: Int
            val legC: Int
            if (HORSE_R[d] == 2 || HORSE_R[d] == -2) {
                legR = hr - HORSE_R[d] / 2
                legC = hc
            } else {
                legR = hr
                legC = hc - HORSE_C[d] / 2
            }
            if (cells[squareOf(legR, legC)] == 0) return true
        }

        // 兵/卒
        if (by == Side.RED) {
            // 红兵向上走，攻击其上方一格；侧翼攻击要求已过河（row <= 4）
            if (r + 1 < BOARD_H) {
                val p = cells[squareOf(r + 1, c)]
                if (p != 0 && sideOf(p) == Side.RED && kindOf(p) == Kind.PAWN) return true
            }
            if (r <= 4) {
                if (c - 1 >= 0) {
                    val p = cells[squareOf(r, c - 1)]
                    if (p != 0 && sideOf(p) == Side.RED && kindOf(p) == Kind.PAWN) return true
                }
                if (c + 1 < BOARD_W) {
                    val p = cells[squareOf(r, c + 1)]
                    if (p != 0 && sideOf(p) == Side.RED && kindOf(p) == Kind.PAWN) return true
                }
            }
        } else {
            if (r - 1 >= 0) {
                val p = cells[squareOf(r - 1, c)]
                if (p != 0 && sideOf(p) == Side.BLACK && kindOf(p) == Kind.PAWN) return true
            }
            if (r >= 5) {
                if (c - 1 >= 0) {
                    val p = cells[squareOf(r, c - 1)]
                    if (p != 0 && sideOf(p) == Side.BLACK && kindOf(p) == Kind.PAWN) return true
                }
                if (c + 1 < BOARD_W) {
                    val p = cells[squareOf(r, c + 1)]
                    if (p != 0 && sideOf(p) == Side.BLACK && kindOf(p) == Kind.PAWN) return true
                }
            }
        }
        return false
    }

    fun inCheck(board: Board, side: Int): Boolean =
        isAttacked(board, board.kingSq(side), Side.other(side))

    /** 走这一步后自己是否安全（合法走法判定） */
    fun isLegal(board: Board, move: Int, us: Int): Boolean {
        val captured = board.make(move)
        val ok = !isAttacked(board, board.kingSq(us), Side.other(us))
        board.unmake(move, captured)
        return ok
    }

    fun hasLegalMove(board: Board, side: Int): Boolean {
        val original = board.sideToMove
        if (original != side) return false
        val buf = IntArray(160)
        val n = generate(board, buf)
        for (i in 0 until n) {
            if (isLegal(board, buf[i], side)) return true
        }
        return false
    }
}
