# 随记账本

桌面悬浮宠物记账 Android 应用，随时随地快速记账。

## 功能

- **悬浮窗宠物**：桌面悬浮宠物，点击即可记账，拖拽吸附屏幕边缘
- **快速记账**：悬浮窗一键打开记账界面，选择分类输入金额即完成
- **多主题切换**：自然绿、赛博朋克、古代风、温馨风四种界面风格
- **自定义分类**：支持 emoji 预设图标或自定义图片作为分类图标
- **账单管理**：按月查看账单列表，支持编辑、删除
- **月度统计**：收入/支出分类汇总，可视化占比

## 演示

![随记账本演示](docs/demo.gif)

## 技术栈

- Kotlin / Android (minSdk 26)
- Room 数据库
- MVVM 架构 (ViewModel + LiveData)
- ViewBinding
- Material Design 3
- 九宫格精灵图动画

## 下载安装

[![下载 APK](https://img.shields.io/badge/下载-APK-brightgreen)](https://github.com/t29912019-spec/ledger-pet/releases/download/v1.0/ledger-pet-v1.0.apk)

1. 点击上方按钮下载 APK
2. 手机设置 → 允许「安装未知应用」
3. 打开 APK 安装

> 要求 Android 8.0+

## 构建

```bash
./gradlew assembleDebug
```
