# AutoSkip

自动跳过 Android 应用开屏广告的无障碍服务工具。

## 项目目标

通过 Android AccessibilityService 监听白名单应用的界面变化，自动识别并点击"跳过"按钮，实现零操作跳过开屏广告。

## 核心架构

```
├── SkipAdService          # 无障碍服务（核心）
│   ├── 监听 WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED 事件
│   ├── 仅处理白名单中的包名
│   ├── 窗口切换后 8 秒检测窗口期，避免误触
│   └── 关键词匹配 → 查找可点击节点 → 执行点击
│
├── WhitelistManager       # 白名单与配置管理（SharedPreferences）
│   ├── 白名单包名增删查
│   ├── 全局默认关键词 + 每个 App 自定义关键词
│   └── Toast 开关等功能配置
│
└── MainActivity           # Compose UI
    ├── 无障碍服务状态卡片（引导开启）
    ├── Toast 提示开关（联动通知权限申请）
    ├── 白名单应用列表（移除 / 编辑关键词）
    └── 应用选择器（已安装非系统应用，支持搜索）
```

## 技术栈

- Kotlin + Jetpack Compose
- Android AccessibilityService
- SharedPreferences
- Target SDK 36

## 构建

```bash
./gradlew assembleDebug
```
