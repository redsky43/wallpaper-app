# 壁纸

一个只做一件事的二次元壁纸 app：点一下，抽一张，整屏配色跟着图变。

## 现在能做什么

- 随机抽一张竖屏二次元插画（nekos.best 主源 / Pixiv 备源）
- 整屏展示，卡片悬浮在模糊的背景之上
- 从图片里取主色，生成整套 Material 3 配色（OKLCH 色彩空间）
- 设为壁纸：仅主屏幕 / 仅锁屏 / 两处一起
- 保存到相册

## 技术栈

| 层 | 做法 |
|---|---|
| 界面 | Jetpack Compose + Material 3 |
| 取色 | 自写 OKLCH 色彩引擎（MD3 tonal palette 思路） |
| 图片 | Coil 2 + OkHttp |
| 构建 | AGP 9.5.1 / Gradle 9.6 / Kotlin 2.3.21 / compileSdk 37 |

## 构建

推送到 `main` 分支会自动触发 GitHub Actions 编译，产物发布在 Releases 页面。

## 版本

- `minSdk 31`（Android 12，需要系统级模糊 API）
- `targetSdk 37`（Android 17）
