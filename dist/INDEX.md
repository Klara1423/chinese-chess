# 版本清单

发布包按 `中国象棋-<versionName>.apk` 命名。最新版放 `dist/` 根目录，旧版移入 `dist/历史版本/`。

| 版本 | versionCode | 大小 | SHA256 | 来源 | 位置 | 备注 |
|---|---|---|---|---|---|---|
| v1.1 | 2 | 833 936 B | `43f6b1697dc4ae20fc79a17798c87fb64885f86e0fc27ac3014d9c8fcd293e6e` | 自构建 | `dist/中国象棋-1.1.apk` | 楷体 / 新图标 / 提示 / 求和 / 认输 / 返回键修复 |
| v1.0 | 1 | 676 030 B | `f5accdb444085de3c3b2642e67743fffef82c49484a91e724def16420df869f1` | 设备提取（MIUI 备份） | `dist/历史版本/中国象棋-1.0.apk` | 首版；源码未保留 |

## 校验方法

```powershell
Get-FileHash "dist\中国象棋-1.1.apk" -Algorithm SHA256
```

## v1.0 归档来源

- 原始文件：`20261028_002904.zip`（小米 10S 的 MIUI 本地备份，备份时间 2026-10-28 00:29）
- 备份内是 MIUI 私有格式 `MIUI BACKUP`（头部 `4D 49 55 49 20 42 41 43`），不是裸 APK
- 提取方式：跳过 3147 字节的 MIUI 头部，取 `PK\x03\x04` 至 EOCD 结束（偏移 3147–679177），得到 676 030 字节的完整 APK
- 完整性：zip 中央目录可解析（15 个条目）；`apksigner verify` 通过 v2 签名校验
- 签名指纹：`e5bae672df093cc4fd0997dcc954740beb5baa2104693215c96aa75b103ab8ad`
  与本机 `keystore/xiangqi.jks` 生成的 v1.1 证书**完全一致**，确认是原始构建，未经第三方重签

## 签名

所有 `自构建` 的包使用同一个 `keystore/xiangqi.jks`（不入库），因此可以互相覆盖升级。
`设备提取` 的包经签名比对确认同源，同样可以互相覆盖升级。

## 降级说明

历史包不能直接覆盖安装：Android 会拒绝 `versionCode` 更小的包。需要先

```
adb uninstall com.klara.xiangqi
```

再装旧版（会丢失本地难度设置等数据）。
