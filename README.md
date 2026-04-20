# AutoSkip

自动跳过 Android 应用开屏广告的无障碍服务工具。

通过 Android AccessibilityService 监听白名单应用的界面变化，自动识别并点击"跳过"按钮，实现零操作跳过开屏广告。

## 构建

Debug 构建（无需签名配置）：

```bash
./gradlew assembleDebug
```

本地 Release 构建（需要签名配置）：

```bash
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties 填入真实的签名信息
./gradlew assembleRelease
```

## 发布新版本

项目通过 GitHub Actions 自动构建并发布签名 Release APK。

### 前置条件

在仓库的 **Settings → Secrets and variables → Actions** 中配置以下 Secrets：

| Secret 名称 | 说明 |
|---|---|
| `KEYSTORE_BASE64` | Keystore 文件的 Base64 编码内容 |
| `KEYSTORE_PASSWORD` | Keystore 密码 |
| `KEY_ALIAS` | 签名密钥别名 |
| `KEY_PASSWORD` | 签名密钥密码 |

生成 `KEYSTORE_BASE64` 的方法：

```bash
base64 -i autoskip.jks | pbcopy   # macOS，结果复制到剪贴板
base64 -w 0 autoskip.jks          # Linux，输出到终端
```

### 发布流程

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`
2. 提交版本变更并打 tag：
   ```bash
   git add app/build.gradle.kts
   git commit -m "release: v1.0.0"
   git tag v1.0.0
   git push origin main --tags
   ```
3. GitHub Actions 会自动执行构建并创建 Release，附带签名后的 APK 文件
4. 在 [Releases](../../releases) 页面查看发布结果

### 手动触发

也可以在 [Actions](../../actions/workflows/release.yml) 页面点击 **Run workflow** 手动触发构建（不会自动创建 Release，但会上传 APK 作为 Artifact）。
