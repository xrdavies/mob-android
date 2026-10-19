# 发布 Runbook（Android）

本文是 mob-android 的发布操作手册：一次性准备 → 每次发布 → 发布后验证 → 回滚与热修复。
标注说明：

- **【现有】**：仓库中已有并核对过的流程（工作流、脚本、Gradle 配置）。
- **【待建】**：尚未配置，按步骤搭建；仓库里没有对应的工作流、密钥或商店记录。

| 渠道 | 状态 | 产物 |
| --- | --- | --- |
| 官网直接下载（Cloudflare R2） | 【现有】 | 签名 APK |
| Google Play | 【待建】 | AAB（Play 不接受 APK 新应用） |

应用基本信息（来自 `app/build.gradle.kts`、`AndroidManifest.xml`）：

| 项 | 值 |
| --- | --- |
| `applicationId` | `com.xrdavies.mob` |
| `minSdk` / `targetSdk` / `compileSdk` | 26 / 37 / 37 |
| 发布 ABI | `arm64-v8a`、`armeabi-v7a` |
| 版本 | CI 从标签注入 `MOB_VERSION_NAME` / `MOB_VERSION_CODE`；本地构建为 `0.1.0` / `1` |
| 应用内更新 | 无。直接下载的用户需从官网手动下载新 APK 覆盖安装 |

## 一、官网直接下载（R2）【现有】

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

### 1. 生成签名密钥（只做一次）

```bash
keytool -genkeypair -v -keystore mob-release.jks -alias mob \
  -keyalg RSA -keysize 4096 -validity 10000
openssl base64 -A -in mob-release.jks -out /tmp/keystore.txt
```

**离线备份 keystore 和密码。** 丢失后无法为已安装用户发布可覆盖安装的更新，
用户需要卸载重装。不要把 `.jks` 提交到 Git。

备份要求：

- 至少两处离线副本（如加密 U 盘 + 密码管理器附件），与密码分开存放。
- 记录别名、证书 SHA-256 指纹（`keytool -list -v -keystore mob-release.jks`），
  发布后用它核对 APK 签名。
- 计划上 Google Play 时，这个密钥的处理见下文「三、Google Play」。

### 2. GitHub Actions 配置

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
R2 桶、自定义域名、CORS 和缓存规则与桌面端共用，见 `../mob/docs/releases.md` 第 1 节；
缓存规则需要让 `/android/latest.json`、`/android/releases.json` 绕过缓存。

> 以上是工作流读取的名称。仓库文件无法证明它们已在 GitHub 上配置，首次发布前到
> Settings → Environments → `release` 逐项核对。

### 3. 发布

发布前：

1. 确认要发布的提交已在 `main` 并推送，`make test` 通过。
2. 决定版本号：不兼容改动升 MAJOR，新功能升 MINOR，修复升 PATCH（MINOR、PATCH ≤ 999）。
3. 准备面向用户的更新说明（中文、无私有 PR 链接），稍后粘贴到 Release 正文；
   正文会写进 `notes`，显示在官网下载页和更新日志。

```bash
git switch main && git pull --ff-only
make release VERSION=v0.1.0
```

`.github/release/tag.mjs` 的检查与 mob 相同（干净的 `main`、与远端一致、版本不回退）。
在 Actions 中查看 **Release mob Android**：运行单元测试、构建并签名 `assembleRelease`，
用 `apksigner` 校验签名，创建草稿 Release。下载 APK 在真机上试装，编辑面向用户的说明，
然后 **Publish release**（不要勾选 pre-release）。

真机试装至少覆盖：

- 覆盖安装上一版本（不卸载），确认配对和机器列表保留、无签名冲突。
- 首次安装：扫码配对（相机权限弹窗）、通知权限弹窗（Android 13+）。
- 发送一条任务、收到完成通知；打开实时桌面。

**Publish mob Android to R2** 随后校验 `SHA256SUMS`，上传版本文件，通过公开地址重新下载
校验，最后更新 `android/releases.json` 和 `android/latest.json`。失败可重试；已发布文件不同
时拒绝覆盖，也不会把 stable 指向更低版本。只读写 `android/` 前缀。

本地验证：`make release-test`。

### 4. 发布后验证

- [ ] Actions 中 **Publish mob Android to R2** 成功（不是只有 Release 工作流成功）。
- [ ] `curl -s https://mob-releases.0xd.app/android/latest.json` 显示新版本和 `version_code`。
- [ ] 用 `download.url` 下载 APK，`shasum -a 256` 与 `download.sha256` 一致。
- [ ] 签名指纹与备份记录一致：`apksigner verify --print-certs mob-android_X.Y.Z.apk`。
- [ ] 官网下载页、更新日志显示新版本（未配置 `SITE_DISPATCH_TOKEN` 时等待最多一小时）。
- [ ] 在未登录 GitHub 的手机浏览器下载并安装成功。

### 5. 失败、回滚与热修复

- 本地检查失败：按提示处理工作区、分支或版本；此时尚未创建标签。
- 标签推送失败：保留本地标签，先 `git ls-remote origin refs/tags/vX.Y.Z`；若远端没有，执行脚本
  提示的 `git -c push.followTags=false push origin refs/tags/vX.Y.Z:refs/tags/vX.Y.Z`。
  不要重建或强制移动标签。
- 构建或签名失败：修正 `release` 环境配置后重跑失败任务，或手动运行 **Release mob Android**，
  填写原标签。
- R2 发布失败：手动运行 **Publish mob Android to R2**，填写已发布的原标签；不要改动产物和说明。
- **发现严重问题（回滚）**：不能回退。发布器拒绝把 stable 指向更低版本，Android 也不允许用较低的
  `versionCode` 覆盖安装。修复后发布更高的 PATCH（如 `v0.1.1`）。紧急时可在 Release 正文和官网
  说明中提示用户暂不升级；已公开版本的文件不可替换。
- 热修复流程与正常发布相同：在 `main` 提交修复 → `make release VERSION=vX.Y.(Z+1)`。

## 二、发布清单（每次）

- [ ] `main` 已推送，`make test`、`make release-test` 通过。
- [ ] 版本号高于上一个标签；更新说明已写好。
- [ ] `make release VERSION=vX.Y.Z` 成功推送标签。
- [ ] **Release mob Android** 成功；草稿含 `mob-android_X.Y.Z.apk` 和 `SHA256SUMS`。
- [ ] 真机覆盖安装和首次安装通过。
- [ ] 编辑 Release 正文，Publish（非 pre-release）。
- [ ] **Publish mob Android to R2** 成功，`latest.json` 已更新。
- [ ] 第 4 节的发布后验证完成后再对外通知。
- [ ] （上架 Play 后）同一版本的 AAB 已上传并完成第三部分的轨道推进。

## 三、Google Play【待建】

当前**没有** Play Console 应用、Play 上传工作流或上传密钥；CI 只构建 APK（`assembleRelease`）。
以下是搭建步骤，完成一项后把本节对应条目改为【现有】。

### 1. 先决定签名方案（不可逆）

Play 新应用必须使用 Play App Signing：Google 保管**应用签名密钥**（签最终安装包），开发者用
**上传密钥**签 AAB 上传。

| 方案 | 结果 |
| --- | --- |
| A. 把现有 `mob-release.jks` 作为应用签名密钥交给 Play（Play Console 提供 PEPK 工具导出加密私钥），另生成新的上传密钥 | Play 安装包与官网 APK 签名相同，用户可在两种渠道间覆盖安装 |
| B. 让 Play 生成新的应用签名密钥 | 两个渠道签名不同；从官网 APK 换到 Play（或反过来）需要卸载重装，配对数据丢失 |

建议 A。应用签名密钥一旦在 Play 上确定就不能随意更换。

新上传密钥（方案 A）：

```bash
keytool -genkeypair -v -keystore mob-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

上传密钥同样离线备份；丢失后可在 Play Console 申请重置（需要等待），比应用签名密钥丢失可恢复。

### 2. Play Console 一次性设置

1. 注册 Google Play 开发者账号（<DEVELOPER_ACCOUNT>，个人或组织；组织需 D-U-N-S）。
   新个人账号上架正式版前通常要求完成一段封闭测试（测试人数和天数以 Play Console 当前要求为准）。
2. 创建应用：名称 `mob`，默认语言，应用类型「应用」，免费。包名在首次上传 AAB 时固定为
   `com.xrdavies.mob`，之后不可更改。
3. **设置 → 应用签名**：按第 1 步的方案配置。
4. **应用内容**（Policy）逐项填写：
   - **隐私权政策**：需要公开 URL（<PRIVACY_POLICY_URL>），说明数据只发往用户自己的 mobd。
   - **数据安全**：见下文第 3 步。
   - **前台服务权限声明**：清单声明了 `FOREGROUND_SERVICE_SPECIAL_USE`（`WatchService`，
     后台保持与用户自己办公电脑的连接以显示提问和任务完成通知）。需填写用途说明并提供演示视频。
   - **相机**：`CAMERA` 只用于扫描 `mobd link` 的配对二维码，`android.hardware.camera` 为非必需。
   - **通知**：`POST_NOTIFICATIONS`，用于提问、任务完成或失败的本地通知（没有推送服务）。
   - 内容分级问卷、目标受众（非面向儿童）、广告（无）、政府应用（否）、金融功能（无）等。
   - **应用访问权限**：应用需要已配对的 mobd 才能使用。为审核提供可访问的演示 mobd 和配对链接，
     或说明审核方式（<REVIEW_ACCESS>）。没有可用环境时审核可能被拒。
5. **商店详情**：简短说明、完整说明、图标 512×512、置顶大图 1024×500、手机截图（≥2 张），
   有平板布局可加 7″/10″ 截图；类别「效率」；联系邮箱和网站。

### 3. 数据安全表单要点

依据代码现状（无分析/崩溃 SDK、无广告，网络请求只到用户配置的 mobd 地址）：

- 消息内容、上传的图片、任务数据会离开设备发往**用户自己的** mobd。是否算 Google 定义的「收集」
  需要所有者按 Play 当前说明判断；保守做法是如实申报为「应用功能」用途、不共享、不用于广告。
- 「传输中加密」：清单 `usesCleartextTraffic="true"`，局域网地址可走 HTTP，因此不能笼统勾选
  「所有数据在传输中加密」。
- 数据删除：配对凭据和机器列表在本地，卸载即删除；`allowBackup="false"`，配对密钥被排除在备份之外。

### 4. 构建 AAB 并上传（手动，首次）

现有 Gradle 签名配置读取同一组环境变量，`bundleRelease` 可直接使用：

```bash
export ANDROID_KEYSTORE_PATH=/path/to/mob-upload.jks   # 方案 A 用上传密钥
export ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=upload ANDROID_KEY_PASSWORD=…
export MOB_VERSION_NAME=0.1.0 MOB_VERSION_CODE=1000      # 按公式：0.1.0 → 1000
./gradlew --no-daemon testReleaseUnitTest bundleRelease
# 产物：app/build/outputs/bundle/release/app-release.aab
```

`versionCode` 与官网 APK 共用同一公式，保证同一版本两渠道一致、单调递增。

### 5. 测试轨道 → 正式发布

1. **内部测试**：上传 AAB，添加测试人员邮箱列表，发送 opt-in 链接；几分钟内可安装，无需审核。
2. **封闭测试**（新个人账号上架正式版前通常必须）：按 Play Console 要求的人数和天数进行。
3. **正式版**：从测试轨道「升级发布」到正式版，填写版本说明（与 GitHub Release 正文一致），
   提交审核。首次审核可能需要数天。
4. **分阶段发布**：正式版选择发布比例（建议 10% → 50% → 100%），观察 Android vitals
   （崩溃、ANR）后逐步扩大。

回滚：Play 不允许降低 `versionCode`。发现问题时在正式版**暂停分阶段发布**，修复后以更高版本号发布；
已更新的用户只能通过新版本修复。

### 6. CI 自动上传（提议，未实现）

在 `release.yml` 中增加 `bundleRelease`，并用 Play Developer API 上传到内部测试轨道：

1. Google Cloud 创建服务账号，在 Play Console **用户和权限** 授予该应用的发布权限，下载 JSON 密钥。
2. `release` 环境新增 Secret `PLAY_SERVICE_ACCOUNT_JSON`，若用独立上传密钥，另存
   `ANDROID_UPLOAD_KEYSTORE_BASE64` 等（命名待定）。
3. 工作流上传 AAB 到 `internal` 轨道（如 `r0adkll/upload-google-play` 或 Gradle Play Publisher）；
   正式版推进和分阶段比例仍在 Play Console 手动操作。

### 7. 官网

上架后决定官网是否同时提供 Play 链接和 APK。`mob-site` 当前只有 APK 下载，加 Play 按钮需要改官网代码。
