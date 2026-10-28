# 协作约定

给以后改这个仓库的人（也包括 AI）的规矩。

## 每次改动都要更新更新日志

`CHANGELOG.md` 是**改动账本**，不是发版时才补的总结。

1. 每落一笔改动（代码、布局、文案、图标、配置、修 bug）就在**当前版本那一节**补一行，
   写清「改了什么」和「为什么」，别攒到最后一起写
2. 版本还没定稿时**不新开一节**，直接改当前这节（版本号没变就是同一个版本）
3. 定稿打 tag 之后，新改动 = 新版本，才在顶部新开一节
4. 提交前扫一遍 `git diff`：凡是用户能感知到的变化，`CHANGELOG.md` 里必须有对应文字
5. 文档、图标、资源这类「也会进 APK / 影响用户看到的东西」同样算改动
   （只调整文档排版本身不必登记）

`CHANGELOG.md` 的写法：**分点写，不用表格**（对齐 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)）。

- 每个版本一节 `## [版本] - 日期`，下面按「新增 / 变更 / 修复 / 移除」分组，每组用 `-` 分点
- 一行一件事，写结果和原因（为什么这么改），不写「重构了某某函数」
- 顶部留一份版本清单，用分点列出 版本 / versionCode / 日期 / 包大小 / 概要，新版本加在最上面
- 定稿前删掉的功能不用写进日志，代码也一并删干净（不留没人调用的入口）

## 文档分工

| 文件 | 写什么 |
|---|---|
| `CHANGELOG.md` | 每个版本改了什么（**每次改动都要动它**） |
| `TODO.md` | 当前版本范围、待解决项、实测数据与调参入口 |
| `README.md` | 给使用者看的：功能、安装、结构、字体/图标来源 |
| `RELEASE.md` | 发版流程清单、版本号约定、归档规则 |
| `dist/INDEX.md` | 每个发布包的大小 / SHA256 / 来源 / 位置 |
| `dist/历史版本/README.md` | 旧包清单 |

## 版本与构建

- 版本号只写在 `app/build.gradle.kts`；`versionCode` 只增不减
- 1.1 已定稿（tag `v1.1`），1.2 是当前版本（`versionCode 3`）
- 构建统一走 `build.bat`（内部设置 `JAVA_HOME`）：
  `cmd /c "build.bat --quiet assembleRelease"`
- 偶尔报 Kotlin 路径编码错误，重跑一次即可

## 提交与归档

- 提交信息前缀：`display:`（界面/手感）、`docs:`（文档）、`release:`（发版）、`fix:`（修 bug）
- 中文提交信息写进 UTF-8 文件再 `git commit -F <文件>`，避免命令行编码问题
- 发版：`dist/` 只留当前包，旧包移进 `dist/历史版本/`，同步更新 `INDEX.md` 与 `历史版本/README.md`
- 定稿打 tag（`git tag -a vX.Y`）；定稿前的覆盖式修改可以 amend + 移动 tag
- 每次改完界面都重拍 `screenshots/`，旧图放 `screenshots/历史/`

## 验证

- 模拟器 `xiangqi_test`（1080×2340 @440dpi，无头）冷启动约 2~3 分钟
- **签名坑**：debug 包和 release 包签名不同，覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 且静默失败；
  验证正式效果一律装 `dist/` 里的包（`install -r`），或先 `uninstall`
- 用户真机（`73410da1`）连着 USB 时**只读不装**
- 测完立刻 `adb emu kill`，并清掉残留 `qemu` / `emulator` 进程
