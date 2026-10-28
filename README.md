# 中国象棋 · Android

纯 Kotlin 写的安卓中国象棋，**零第三方依赖**（不引 Compose、不引 AndroidX），棋盘、棋子、动画全部用 `Canvas` 自绘。APK 814 KB。

当前版本 **v1.1**（`versionCode 2`，2026-10-28），更新内容见 [CHANGELOG.md](CHANGELOG.md)。

## 直接安装

```
dist/中国象棋-1.1.apk
```

传到手机点开安装即可（需要允许「安装未知来源应用」）。已用本地密钥签名，可以正常升级覆盖安装。

## 功能

- **人机对战**：五档难度（入门 / 简单 / 普通 / 困难 / 大师），电脑执黑
- **双人对弈**：同屏轮流走子，两人对坐；黑方棋子自动转 180°，各自看自己的子都是正的
- **完整规则**：马腿、象眼、炮架、士象不出界、将帅照面、兵卒过河横走
- **胜负判定**：将死、困毙（无子可动判负）、三次重复局面、120 回合无吃子和棋
- **中文棋谱**：`炮二平五`、`马8进7`、`前车进三` 这类标准记法，实时记录
- **悔棋**：人机模式一次退回两步，回到自己该走的局面；按钮在顶栏
- **结算卡片**：标注结束原因（将死 / 困毙 / 三次重复 / 久无吃子）
- **返回键**：不会直接退出应用 —— 对局进行中先问「退出对局？」，已结束则回菜单，只有在菜单才退出
- **操作**：点选棋子再点目标；也可以直接拖动。选中后显示所有合法落点（空位圆点、可吃子红圈）
- 走子动画、将军红圈脉冲、触感反馈、难度记忆

> 「提示 / 求和 / 认输」的实现在 v1.1 里暂时摘掉了界面入口（代码仍在 `GameView.kt`），
> 原因是双人对坐时只有下方那侧够得着按钮，做不到两边对等。等布局方案定了再接回来，
> 见 [TODO.md](TODO.md)「待解决」。

## 字体与图标

- 棋子、标题、结算用 **霞鹜文楷 LXGW WenKai TC**，按实际用到的 36 个汉字子集化（约 17 KB，`assets/font/kai.ttf`）。棋子绘制额外加了 1.2% 描边，因为楷体笔画偏细，缩小后容易发虚
- 字体许可为 **SIL Open Font License 1.1**，副本见 `LICENSES/LXGW-WenKai-OFL.txt` 和 `assets/font/OFL.txt`
- 启动图标是「红帥棋子 + 木质底」，用同一份子集字体渲染，自适应图标放在 `res/mipmap-anydpi-v26/`

## 源码结构

```
app/src/main/java/com/klara/xiangqi/
├── MainActivity.kt          单 Activity，托管自绘 View
├── game/                    ← 纯逻辑，不含任何 Android API
│   ├── Piece.kt             棋子编码、兵种、汉字
│   ├── Board.kt             棋盘状态、make/unmake、Zobrist 增量哈希
│   ├── MoveGen.kt           走法生成 + 受攻击判定 + 合法性
│   ├── Game.kt              对局流程、悔棋、胜负判定
│   ├── Ai.kt                Alpha-Beta 引擎
│   ├── MoveNamer.kt         中文记谱
│   └── Perft.kt             调试自检
└── ui/GameView.kt           全部界面绘制与触控
```

`game` 包不依赖 Android，可以直接搬到桌面 JVM 上跑测试。

## 引擎

- 迭代加深 + Alpha-Beta + 置换表（2^18）+ MVV-LVA + 杀手着法 + 历史启发 + 静态搜索
- 每步有时间上限，低端机也不会卡住

五档强度不是改个名字，是三个旋钮一起调：

| 档位 | 搜索深度上限 | 思考时间 | 估值扰动 | 随手棋概率 |
|---|---|---|---|---|
| 入门 | 1 层 | 0.15 s | ±140 | 30% |
| 简单 | 2 层 | 0.4 s | ±80 | 12% |
| 普通 | 4 层 | 0.9 s | 0 | 0 |
| 困难 | 6 层 | 1.8 s | 0 | 0 |
| 大师 | 9 层 | 3.6 s | 0 | 0 |

「随手棋」是低档位真正变弱的关键：有概率直接在一个合法着法里随机挑一步，所以入门会送子、会漏杀。
「估值扰动」则让同分着法之间有随机性，避免每局走成一模一样。
高两档靠搜索深度拉开，大师跑满 3.6 秒的迭代加深，实际能到 6～7 层。

实测（模拟器，开局局面，调试包启动时自动 benchmark，见 logcat `XiangqiAI`）：

```
bench level=BEGINNER reached=1 nodes=340      ms=53
bench level=EASY     reached=2 nodes=4096     ms=~300
bench level=NORMAL   reached=3 nodes=15360    ms=934
bench level=HARD     reached=5 nodes=1027072  ms=1802
bench level=MASTER   reached=6 nodes=3690496  ms=3600
```

### 正确性自检

开局标准 perft 数字是 `44 / 1920 / 79666`。debug 包启动时会在后台跑一遍并打印到 logcat：

```
adb logcat -s XiangqiPerft
I XiangqiPerft: depth=1 nodes=44 expected=44 OK
I XiangqiPerft: depth=2 nodes=1920 expected=1920 OK
I XiangqiPerft: depth=3 nodes=79666 expected=79666 OK
```

三个数字全中，说明走法生成、将军判定、将帅照面、马腿象眼逻辑都是对的。

## 版本管理

- 版本号写在 `app/build.gradle.kts` 的 `defaultConfig` 里：`versionCode`（每次发版 +1，Android 靠它判断是不是升级）和 `versionName`（给人看的）
- 打好的包按版本号归档：`dist/中国象棋-1.1.apk`，**旧版本保留不覆盖**
- 每次发版在 `CHANGELOG.md` 里加一节，写清楚新增 / 改动 / 修复
- App 菜单右下角会显示当前 `versionName`

## 重新构建

**Android Studio**：直接 `Open` 这个目录，等同步完点 Run。

**命令行**（需要 Android Studio 自带的 JDK）：

```bat
build.bat assembleDebug      :: 调试包
build.bat assembleRelease    :: 正式包
```

产物在 `app/build/outputs/apk/`。

### 两个坑

1. **目录名是中文**，AGP 默认拒绝构建。已在 `gradle.properties` 里加 `android.overridePathCheck=true` 绕过（实测可用）。如果哪天换到别的机器出问题，把项目挪到纯英文路径即可。
2. 本机 Gradle 只认 Android Studio 自带的 JDK（`JAVA_HOME` 指向 `...\Android Studio\jbr`），`build.bat` 已处理好。AGP 9 自带 Kotlin 支持，**不要**再手动加 `org.jetbrains.kotlin.android` 插件，会报扩展名冲突。

## 签名

`keystore/xiangqi.jks`，口令 `xiangqi2026`，别名 `xiangqi`。
`keystore/keystore.properties` 里是构建时读取的配置。这个密钥丢了就没法覆盖升级，建议自己备份一份。

## 还能加什么

- 联网对战（需要后端 + 账号）
- 残局闯关、打谱/复盘
- 电脑执红、让子
- 音效（落子、吃子）
- 棋钟
