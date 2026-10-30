# Minecraft安卓下载站

本软件是一个 Minecraft 安卓版本的下载工具，帮助玩家快速获取各版本游戏安装包，欢迎各位提供反馈。

> 声明与安装包来源说明：见 [legal/statement.txt](legal/statement.txt)。 

## 功能

- **版本全**：从 1.0.0 到最新 26.x，600+ 个历史版本全收录
- **包干净**：提供去验证包，纯净无病毒、无需正版账号
- **下载快**：多线程分段下载（1~16 线程可调）+ 断点续传
- **一条龙**：应用内下载 → 安装，装完可自动清理安装包

## 系统要求

- Android 5.0（API 21）及以上
- Android 5.0~7.1 可安装的最高版本为 1.20.62（1.20.63 起 APK 最低 SDK 要求提升）

## 构建

1. 克隆本仓库
2. 用 Android Studio（或命令行 `./gradlew assembleDebug`）构建
3. 产物位于 `app/build/outputs/apk/debug/`

技术栈：Kotlin + Jetpack Compose（Material 3），最低 API 21

## 免责声明

[legal/statement.txt](legal/statement.txt)
