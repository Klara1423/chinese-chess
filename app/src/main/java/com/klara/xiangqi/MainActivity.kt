package com.klara.xiangqi

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.klara.xiangqi.ui.GameView

class MainActivity : Activity() {

    private lateinit var gameView: GameView

    /** Android 13+ 的预测式返回回调；targetSdk 36 上 onBackPressed 已不再被调用 */
    private val backCallback: OnBackInvokedCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OnBackInvokedCallback { onBackKey() }
        } else {
            null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        gameView = GameView(this)
        setContentView(gameView)

        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            runSelfTest()
        }
    }

    /** 开局 perft 参考值：1=44, 2=1920, 3=79666 —— 用于确认走法生成无误 */
    private fun runSelfTest() {
        Thread {
            val board = com.klara.xiangqi.game.Board()
            val expected = longArrayOf(44L, 1920L, 79666L)
            for (d in 1..3) {
                val t0 = System.nanoTime()
                val got = com.klara.xiangqi.game.Perft.count(board, d)
                val ms = (System.nanoTime() - t0) / 1_000_000
                android.util.Log.i(
                    "XiangqiPerft",
                    "depth=$d nodes=$got expected=${expected[d - 1]} ${if (got == expected[d - 1]) "OK" else "MISMATCH"} (${ms}ms)"
                )
            }
            benchmarkLevels()
        }.apply { isDaemon = true }.start()
    }

    /** 各档位在开局局面下的搜索量，用于确认档位差距真实存在 */
    private fun benchmarkLevels() {
        val ai = com.klara.xiangqi.game.Ai()
        for (level in com.klara.xiangqi.game.Difficulty.entries) {
            val b = com.klara.xiangqi.game.Board()
            val t0 = System.nanoTime()
            ai.chooseMove(b, level)
            val ms = (System.nanoTime() - t0) / 1_000_000
            android.util.Log.i(
                "XiangqiAI",
                "bench level=${level.name} depthCap=${level.depth} reached=${ai.lastDepth} " +
                    "nodes=${ai.lastNodes} ms=$ms"
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        onBackKey()
    }

    private fun onBackKey() {
        if (!gameView.handleBack()) {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, it
                )
            }
        }
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onPause()
    }
}
