# 随记账本

桌面悬浮宠物记账 Android 应用，随时随地快速记账。

## 功能

- **自动记账**：监听微信/支付宝/购物App支付通知，自动弹出记账窗口并预填金额；退款通知自动匹配等额支出并抵消删除
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

[![下载 APK](https://img.shields.io/badge/下载-APK-brightgreen)](https://github.com/t29912019-spec/ledger-pet/raw/main/releases/ledger-pet-v1.1.apk)

**手机直接下载**：用手机浏览器打开以下链接即可下载安装：

```
https://github.com/t29912019-spec/ledger-pet/raw/main/releases/ledger-pet-v1.1.apk
```

或者用手机扫码下载：

![QR](https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=https://github.com/t29912019-spec/ledger-pet/raw/main/releases/ledger-pet-v1.1.apk)

安装步骤：
1. 下载 APK 文件
2. 手机设置 → 允许「安装未知应用」
3. 打开 APK 安装

> 要求 Android 8.0+

## 构建

```bash
./gradlew assembleDebug
```
