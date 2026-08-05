# 自动发布说明（Fork）

## 一键发版

1. 改 `app/build.gradle` 里的 `versionName` / `versionCode`
2. 提交并推送到 `main`
3. 打 tag 并推送（会触发 Actions 构建并创建 Release）：

```bash
git tag 2.0.5
git push origin 2.0.5
```

或在 GitHub：**Actions → Build Signed Release APK → Run workflow**，输入版本号。

## 安装包在哪里

发布成功后：

- **Release 页面**（推荐）：  
  https://github.com/mcxen/MoeMemosAndroid/releases  
  资源文件名：`moememos-vX.Y.Z.apk`

- **某次构建产物**：  
  Actions 对应 run → Artifacts → `moememos-vX.Y.Z`（保留期有限）

## 需要的 Secrets

仓库 Settings → Secrets and variables → Actions：

| Secret | 说明 |
|--------|------|
| `ANDROID_KEYSTORE_BASE64` | release keystore 的 base64 |
| `ANDROID_SIGNING_STORE_PASSWORD` | 密钥库密码 |
| `ANDROID_SIGNING_KEY_ALIAS` | 别名 |
| `ANDROID_SIGNING_KEY_PASSWORD` | 密钥密码 |

本机 keystore 备份路径（勿提交到 Git）：`~/.moememos-signing/`

## 支持的 tag 格式

- `2.0.5`
- `2.0.5-alpha.0`
- `2.0.5-beta.0`
