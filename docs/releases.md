# 发布与公开下载（Android APK）

与 mob、mobd 相同的流程：推送版本标签 → CI 构建并签名 APK、创建**草稿** GitHub
Release → 手动发布 Release → 独立工作流上传到 Cloudflare R2。官网
（`../mob-site`）读取 `android/latest.json` 显示下载按钮，无需修改官网代码。

```text
{DOWNLOAD_BASE_URL}/android/latest.json             当前版本
{DOWNLOAD_BASE_URL}/android/releases.json           版本历史数组
{DOWNLOAD_BASE_URL}/android/vX.Y.Z/mob-android_X.Y.Z.apk
{DOWNLOAD_BASE_URL}/android/vX.Y.Z/SHA256SUMS
```

清单包含 `version`、`version_code`、`notes`、`pub_date`、`min_sdk`、
`download.url`、`download.sha256` 和 `checksums_url`。`versionCode` 由版本号计算：
`MAJOR × 1000000 + MINOR × 1000 + PATCH`，因此 MINOR、PATCH 不超过 999。

## 1. 生成签名密钥（只做一次）

```bash
keytool -genkeypair -v -keystore mob-release.jks -alias mob \
  -keyalg RSA -keysize 4096 -validity 10000
openssl base64 -A -in mob-release.jks -out /tmp/keystore.txt
```

**离线备份 keystore 和密码。** 丢失后无法为已安装用户发布可覆盖安装的更新，
用户需要卸载重装。不要把 `.jks` 提交到 Git。

## 2. GitHub Actions 配置

在 **Settings → Environments** 创建 `release` 环境（与 mob 仓库同名，但配置不会继承）。

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_BASE64` | `/tmp/keystore.txt` 的内容 |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| Secret | `ANDROID_KEY_ALIAS` | 密钥别名，如 `mob` |
| Secret | `ANDROID_KEY_PASSWORD` | 密钥密码 |
| Secret | `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | 限定到发布桶的 R2 凭据 |
| Variable | `DOWNLOAD_BASE_URL` | `https://mob-releases.0xd.app` |
| Variable | `R2_ACCOUNT_ID` / `R2_BUCKET` | 与 mob 仓库相同 |
| Secret（可选） | `SITE_DISPATCH_TOKEN` | 对 `xrdavies/mob-site` 有 Contents 读写权限的 fine-grained token；发布后立即重建官网 |

未配置 `SITE_DISPATCH_TOKEN` 时，官网每小时的定时构建会读取新版本。

## 3. 发布

```bash
git switch main && git pull --ff-only
make release VERSION=v0.1.0
```

`.github/release/tag.mjs` 的检查与 mob 相同（干净的 `main`、与远端一致、版本不回退）。
在 Actions 中查看 **Release mob Android**：运行单元测试、构建并签名 `assembleRelease`，
用 `apksigner` 校验签名，创建草稿 Release。下载 APK 在真机上试装，编辑面向用户的说明，
然后 **Publish release**（不要勾选 pre-release）。

**Publish mob Android to R2** 随后校验 `SHA256SUMS`，上传版本文件，通过公开地址重新下载
校验，最后更新 `android/releases.json` 和 `android/latest.json`。失败可重试；已发布文件不同
时拒绝覆盖，也不会把 stable 指向更低版本。只读写 `android/` 前缀。

本地验证：`make release-test`。
