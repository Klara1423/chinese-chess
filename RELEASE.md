# 发版流程

每次发新版按这个清单走，别跳步。以 `1.1 → 1.2` 为例。

## 一、改版本号

`app/build.gradle.kts`：

```kotlin
versionCode = 3        // 必须 +1，Android 靠它判断升级
versionName = "1.2"
```

> `versionCode` 只增不减。改小了系统会拒绝安装，也没法覆盖升级。

## 二、改内容 + 自测

```bat
build.bat --quiet assembleDebug
```

装到模拟器上过一遍：菜单 / 人机一局 / 双人一局 / 提示 / 求和 / 认输 / 结算卡片 / **返回键三种场景**。

> 注意：debug 包和 release 包签名不同，来回切换要先 `adb uninstall com.klara.xiangqi`。

## 三、归档旧版（**打包前做，别忘**）

```powershell
Move-Item "dist\中国象棋-1.1.apk" "dist\历史版本\中国象棋-1.1.apk"
```

顺手把当前截图也留一份（`screenshots/` 会被新版本覆盖）：

```powershell
Copy-Item "screenshots\*.png" "screenshots\历史\" -Force
Rename-Item "screenshots\历史\menu.png"  "v1.1-menu.png"
Rename-Item "screenshots\历史\board.png" "v1.1-board.png"
Rename-Item "screenshots\历史\pvp.png"   "v1.1-pvp.png"
Rename-Item "screenshots\历史\result.png" "v1.1-result.png"
```

## 四、打 release 包

```bat
build.bat --quiet assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 偶尔会遇到 `error: source file or directory not found: ...\u4E2D\u6587路径...`
> 这是 Kotlin 守护进程拿到了错误编码的路径，**直接重跑一次就好**。

## 五、复制并校验

```powershell
Copy-Item "app\build\outputs\apk\release\app-release.apk" "dist\中国象棋-1.2.apk"
(Get-FileHash "dist\中国象棋-1.2.apk" -Algorithm SHA256).Hash.ToLower()
$aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Recurse -Filter aapt2.exe | Select-Object -Last 1
& $aapt.FullName dump badging "dist\中国象棋-1.2.apk" | Select-String "package:"
```

确认 `versionCode` / `versionName` 是新的。

## 六、更新文档

1. `CHANGELOG.md` 顶部加一节：新增 / 改动 / 修复 / 体积
2. `dist/INDEX.md` 表格加一行，填 versionCode、大小、SHA256、来源、备注
3. `dist/历史版本/README.md` 的「目前状态」表更新

## 七、提交 + 打 tag

```powershell
git add -A
git commit -m "release: v1.2"
git tag -a v1.2 -m "v1.2 — 一句话概括本次改动"
```

tag 一定要打：以后 `git checkout v1.2` 就能回到这个版本，`git diff v1.1 v1.2` 能看全量差异。

## 版本号约定

| 变更类型 | 例子 |
|---|---|
| bug 修复 / 小打磨 | 1.1 → 1.2 |
| 新功能 | 1.2 → 1.3 或 2.0 |
| 架构级重写 / 联网对战 | 2.0 |

## 不要做的事

- ❌ 覆盖 `dist/` 里已有的同名包
- ❌ 改小 `versionCode`
- ❌ 把 `keystore/` 提交进 git（里面有明文口令）
- ❌ 只改代码不打 tag —— tag 才是"这一刻是什么样"的锚点
