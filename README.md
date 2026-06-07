# PicShelf / 图架

PicShelf is a lightweight Android image shelf app for personal offline image organization.

图架是一个轻量 Android 本地图片阅读器，用于个人离线图片整理和浏览。

## Features / 功能

- Organize local images by top-level category and album.
- Set an optional password for each category.
- Import multiple images or an entire folder.
- Move imported images into the app's private storage.
- Try to remove imported originals from the system gallery after import.
- Choose an album cover from images inside the album.
- Set a custom blurred home background.
- Adjust background blur from no blur to stronger blur.
- Switch between three UI themes.
- Browse images with drag-based left/right switching.

- 按大分类和图集整理本地图片。
- 每个大分类可选设置密码。
- 支持多图导入和文件夹导入。
- 导入后图片会移动到 App 私有空间。
- 导入后会尝试从系统相册删除原图。
- 可从图集内选择一张图片作为封面。
- 可设置自定义首页背景。
- 首页背景支持从不虚化到高虚化调节。
- 支持三套 UI 主题。
- 浏览图片时支持左右拖拽切换。

## Project Path / 工程目录

```text
pic_shelf_android/
```

## Build / 构建

Build a release APK locally:

本地构建 release APK：

```powershell
cd pic_shelf_android
.\gradlew.bat assembleRelease
```

The generated APK is located at:

构建产物位于：

```text
pic_shelf_android/app/build/outputs/apk/release/app-release.apk
```

## Current Release / 当前版本

- Version: `0.5.1`
- Package: `com.reilia.picshelf`
- App name: `图架`

## Privacy Notes / 隐私说明

PicShelf works with local files selected by the user. Imported images are copied into the app's private storage. When possible, the app asks Android to remove the original images from the system gallery, but deletion depends on Android version and system permission behavior.

图架只处理用户主动选择的本地文件。导入图片会复制到 App 私有空间。条件允许时，App 会请求 Android 从系统相册删除原图，但是否能删除取决于 Android 版本和系统权限行为。

The category password is an in-app access gate for casual privacy. It is not a full device-level encryption system.

分类密码是 App 内的日常隐私入口，不等同于完整的设备级加密系统。

## Ignored Local Files / 不上传的本地文件

The repository ignores local APK outputs, Gradle build outputs, Android local machine settings, logs, caches, and unrelated local tools.

仓库会忽略本地 APK、Gradle 构建产物、Android 本机配置、日志、缓存和无关的本地工具。
