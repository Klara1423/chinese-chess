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
| 版本定稿**前**只调显示（字号、间距、配色），不改功能 | **不升版本号**，覆盖同版本的包（见下） |
| 定稿之后再改（哪怕只调显示） | 1.1 → 1.2 |
| bug 修复 / 小打磨 | 1.1 → 1.2 |
| 新功能 | 1.2 → 1.3 或 2.0 |
| 架构级重写 / 联网对战 | 2.0 |

## 推送到远程（可选，随时可做）

本地仓库不依赖 GitHub，没登录、没联网也能正常提交。等能登录了再推，一次都不会少。

**GitHub Desktop**：`File → Add local repository` → 选项目目录 → 右上角 `Publish repository` → 勾选 **Keep this code private**。

**命令行**：

```powershell
git remote add origin https://github.com/<用户名>/<仓库名>.git
git push -u origin main
git push origin --tags
```

要点：

- `keystore/` 已在 `.gitignore` 中排除，不会上传（里面有明文口令）。**密钥必须单独备份**，丢了就无法给老用户做覆盖升级
- APK 会随仓库上传（每个约 800 KB）。版本多了（>30 个）再考虑挪到 GitHub Releases，仓库里只留 `INDEX.md`
- 国内访问 GitHub 慢或被墙时，可以挂代理，或改用 Gitee 码云——命令完全一样，只换 remote 地址
- 部署前最稳的备份仍然是：**整个项目文件夹（含 `.git` 和 `dist`）复制到移动硬盘或网盘**

## 同版本覆盖（版本还没定稿时）

一个版本在**定稿前**反复调显示、不升版本号，直接覆盖 `dist/` 里同名的包。规则：

1. **「定稿」的界线**：用户说满意了 → 这个版本就定稿，打 tag 收尾；之后**再改**才升版本号（1.1 → 1.2）
2. 覆盖前确认旧包已提交进 git（`git log -- dist/中国象棋-X.Y.apk`），覆盖后旧包只存在于历史里
3. `dist/INDEX.md` 只保留**一行**当前包，写最新大小与 SHA256；被覆盖的中间态不必逐行登记，
   在表下用一句话说明「定稿前迭代过几版，可从 git 历史取回」
4. **不写「X.Y 修订」**——版本号没变就是同一个版本，`CHANGELOG.md` 里该版本只有一节，内容随时更新
5. 提交信息写 `display: ...`，定稿前不打新 tag
6. 取回某个历史包：`git show <commit>:dist/中国象棋-X.Y.apk > 中国象棋-X.Y-旧.apk`

## 不要做的事

- ❌ 在**改了功能**的情况下覆盖同名包（会分不清哪版是哪版）
- ❌ 改小 `versionCode`
- ❌ 把 `keystore/` 提交进 git（里面有明文口令）
- ❌ 只改代码不打 tag —— tag 才是"这一刻是什么样"的锚点
