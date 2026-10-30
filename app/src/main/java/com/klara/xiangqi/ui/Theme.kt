package com.klara.xiangqi.ui

import android.graphics.Typeface

/**
 * 界面配色与字体。
 *
 * - 所有颜色集中在这里，改配色不用翻 `GameView`（原来散在一个 1500 行的文件末尾）
 * - 字体对象只创建一次：原来 `Typeface.create(...)` 写在各处绘制函数里，每帧都会重新分配
 */
internal val C_BG_TOP = 0xFF2E2117.toInt()
internal val C_BG_MID = 0xFF20170F.toInt()
internal val C_BG_BOTTOM = 0xFF120D08.toInt()
internal val C_WOOD_A = 0xFFF8E8C8.toInt()
internal val C_WOOD_B = 0xFFE9CE9C.toInt()
internal val C_GOLD = 0xFFE2BE79.toInt()
internal val C_RED = 0xFFC0392B.toInt()
internal val C_TEXT_DIM = 0xFFB7A489.toInt()

/** 正文用的常规无衬线 */
internal val SANS = Typeface.create("sans-serif", Typeface.NORMAL)

/** 标题 / 按钮用的中粗无衬线 */
internal val SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
