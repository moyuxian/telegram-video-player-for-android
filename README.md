# Telegram Video Player for Android

这是一个面向 Android 的本地视频播放器，主要用于扫描并播放 Telegram 缓存目录中的视频文件。项目基于 Kotlin、Jetpack Compose 和 Media3（ExoPlayer）实现，当前分支已包含收藏、竖屏播放、手势控制和沉浸式全屏等功能。

## 主要功能

- 自动扫描 Telegram 缓存目录中的视频文件
- 视频列表搜索、排序、分组显示
- 收藏视频，并在“全部 / 收藏”之间快速切换
- 记忆每个视频的播放进度
- 默认竖屏播放，可一键切换横屏/竖屏
- 双击暂停/继续播放
- 左右滑动快速拖动进度，并实时预览目标画面
- 长按屏幕左右边缘临时倍速播放，倍速值可自定义
- 播放时隐藏状态栏和导航栏，保持沉浸式体验

## 运行要求

- Android Studio
- Android SDK
- 建议 Android 11 及以上设备
- 需要授予“所有文件访问权限（All Files Access）”

## 如何构建

推荐直接用 Android Studio：

1. 打开本项目目录
2. 等待 Gradle Sync 完成
3. 连接真机或启动模拟器
4. 运行 `app` 模块

如果你使用命令行，也可以执行：

```bash
gradle assembleDebug
gradle installDebug
```

说明：当前仓库使用 Gradle Kotlin DSL，应用模块位于 `app/`。

## 权限说明

本项目依赖 Telegram 缓存目录扫描，因此需要 `MANAGE_EXTERNAL_STORAGE` 权限。首次启动后，请根据应用提示授予权限，否则无法读取视频列表。

## 项目结构

- `app/src/main/java/com/example/telegramcacheplayer/`：核心业务代码
- `MainActivity.kt`：视频列表页
- `PlayerActivity.kt`：播放器页
- `VideoListViewModel.kt`：列表状态、筛选、收藏等逻辑
- `FavoritesStore.kt` / `PlaybackSettingsStore.kt`：本地偏好与设置持久化

## 当前分支说明

当前 `main` 分支已经是最新版本。旧的实验分支已清理，当前仓库以这一版为主线继续开发。

## 开源协议

本项目基于 `MIT License` 开源，详见仓库根目录下的 [LICENSE](./LICENSE) 文件。
