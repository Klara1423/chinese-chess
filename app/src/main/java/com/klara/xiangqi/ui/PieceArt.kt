package com.klara.xiangqi.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import com.klara.xiangqi.game.Kind
import com.klara.xiangqi.game.Side
import com.klara.xiangqi.game.glyphOf

/**
 * 棋子位图：一次生成、反复使用。
 *
 * 和界面布局无关，只负责把一个子的样子画到 [SIZE]×[SIZE] 的位图上：
 * 木质底 → 外沿 → 红黑内圈 → 字。字按**实际墨迹**归一化后居中（见 [render] 里的注释）。
 */
internal object PieceArt {

    /** 位图边长，各密度共用这一张，绘制时缩放 */
    private const val SIZE = 224

    /** 字号的起算基准（会再按墨迹归一化，最终大小由下面两个比例决定） */
    private const val TEXT_SCALE = 0.56f

    /** 普通棋子墨迹占内圈直径的比例 */
    private const val INK_RATIO = 0.68f

    /** 将 / 帅比其它子略大一点 */
    private const val KING_INK_RATIO = 0.72f

    /**
     * 个别字的光学补偿（在墨迹归一化之后再乘一次）。
     * 归一化让墨迹最长边一样长，但笔画少的字看着还是偏小，按需单独放大几个百分点。
     * 调整时改这里，配平后重新看截图。
     */
    private val GLYPH_INK_TWEAK = mapOf(
        "仕" to 1.06f,
        "相" to 1.05f,
        "車" to 1.04f,
    )

    private val inkRect = Rect()

    /**
     * 画一个子：木质圆底 + 内外两圈 + 汉字。
     * 汉字大小不统一——每个字的墨迹宽高差很多（「馬」窄、「將」宽），
     * 所以按实测墨迹把每个字缩放到固定的目标尺寸，再按墨迹中心对齐圆心。
     */
    fun render(kai: Typeface, piece: Int, side: Int, kind: Int): Bitmap {
        val size = SIZE
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
            textSize = size * TEXT_SCALE
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = size * 0.012f
            strokeJoin = Paint.Join.ROUND
        }
        val glyph = glyphOf(piece)
        val inkRatio = (if (kind == Kind.KING) KING_INK_RATIO else INK_RATIO) *
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
}
