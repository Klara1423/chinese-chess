package com.klara.xiangqi.game

/**
 * 棋盘状态。棋格用 IntArray(90) 表示，原地 make/unmake 以便搜索时零分配。
 */
class Board {

    val cells = IntArray(BOARD_SIZE)
    var sideToMove: Int = Side.RED
    var redKingSq: Int = 85
    var blackKingSq: Int = 4
    var hash: Long = 0L

    init {
        reset()
    }

    fun reset() {
        cells.fill(0)
        val back = intArrayOf(
            Kind.ROOK, Kind.HORSE, Kind.ELEPHANT, Kind.ADVISOR, Kind.KING,
            Kind.ADVISOR, Kind.ELEPHANT, Kind.HORSE, Kind.ROOK
        )
        for (c in 0 until BOARD_W) {
            cells[squareOf(0, c)] = pieceOf(Side.BLACK, back[c])
            cells[squareOf(9, c)] = pieceOf(Side.RED, back[c])
        }
        cells[squareOf(2, 1)] = pieceOf(Side.BLACK, Kind.CANNON)
        cells[squareOf(2, 7)] = pieceOf(Side.BLACK, Kind.CANNON)
        cells[squareOf(7, 1)] = pieceOf(Side.RED, Kind.CANNON)
        cells[squareOf(7, 7)] = pieceOf(Side.RED, Kind.CANNON)
        for (c in intArrayOf(0, 2, 4, 6, 8)) {
            cells[squareOf(3, c)] = pieceOf(Side.BLACK, Kind.PAWN)
            cells[squareOf(6, c)] = pieceOf(Side.RED, Kind.PAWN)
        }
        sideToMove = Side.RED
        redKingSq = squareOf(9, 4)
        blackKingSq = squareOf(0, 4)
        hash = Zobrist.compute(this)
    }

    fun copy(): Board {
        val b = Board()
        cells.copyInto(b.cells)
        b.sideToMove = sideToMove
        b.redKingSq = redKingSq
        b.blackKingSq = blackKingSq
        b.hash = hash
        return b
    }

    fun kingSq(side: Int): Int = if (side == Side.RED) redKingSq else blackKingSq

    fun pieceAt(sq: Int): Int = cells[sq]

    /** 走子，返回被吃掉的棋子（0 表示空位） */
    fun make(move: Int): Int {
        val from = MoveGen.fromOf(move)
        val to = MoveGen.toOf(move)
        val moving = cells[from]
        val captured = cells[to]

        hash = hash xor Zobrist.piece[from][moving]
        if (captured != 0) hash = hash xor Zobrist.piece[to][captured]
        hash = hash xor Zobrist.piece[to][moving]
        hash = hash xor Zobrist.side

        cells[from] = 0
        cells[to] = moving
        if (kindOf(moving) == Kind.KING) {
            if (sideOf(moving) == Side.RED) redKingSq = to else blackKingSq = to
        }
        sideToMove = Side.other(sideToMove)
        return captured
    }

    /** 撤销 make */
    fun unmake(move: Int, captured: Int) {
        val from = MoveGen.fromOf(move)
        val to = MoveGen.toOf(move)
        val moving = cells[to]

        sideToMove = Side.other(sideToMove)
        cells[from] = moving
        cells[to] = captured
        if (kindOf(moving) == Kind.KING) {
            if (sideOf(moving) == Side.RED) redKingSq = from else blackKingSq = from
        }

        hash = hash xor Zobrist.side
        hash = hash xor Zobrist.piece[from][moving]
        if (captured != 0) hash = hash xor Zobrist.piece[to][captured]
        hash = hash xor Zobrist.piece[to][moving]
    }
}
