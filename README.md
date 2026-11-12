# 哇哇叫 · 运动数据同步工具

<p align="center">
  <img src="docs/logo-wawajiao.png" alt="哇哇叫" width="120">
</p>

**把散落在各处的骑行 / 跑步记录，统一同步到 Outbase 或 Strava。**

Android 原生（Kotlin）多平台运动数据迁移工具，基于开源项目 **迈进OB** 改造而来，保留其 Outbase 全平台上传能力，并新增 **Strava 网页 cookie 协议上传**。

- 包名 `com.wawajiao.sync`｜版本 `1.0.0`（versionCode 10000）
- minSdk 26 / targetSdk 36 / compileSdk 36
- 许可证：MIT

> 本项目完全开源免费。如果你觉得有用，欢迎 Star、提 Issue、提 PR。

---

## 功能

| 能力 | 说明 |
|---|---|
| 多源统一同步 | 一次勾选多个数据源，批量拉取活动并上传到同一目标 |
| 双同步目标 | **Outbase 或 Strava 二选一**，设置页单选，选择持久化；手动同步与后台自动同步都跟随该目标 |
| 一键登录检测 | 一次性检测所有平台登录态（网络请求），支持会话内自动检测、后台静默检测 |
| 上传去重 | 记忆键 `{源}_{活动id}_to_{目标}`，成功 / 跳过写记忆，pending 不写，避免重复入库 |
| 时区修正 | 按平台单独配置当天 / 历史记录 ±8 小时偏移，上传前自动修正 |
| 坐标纠偏 | 迈金 GCJ-02 → WGS-84（七牛云与 fit_content 两个通道可分别开关） |
| 数据合并 | 多段轨迹合并为一条活动 |
| 界面 | Material 组件，橙色主题（`#FC4C02`），浅色 / 深色 / 跟随系统三态 |

### 支持的数据源

iGPSPORT（国内 / 国际）、行者、迈金、黑鸟单车、百锐腾、捷安特、佳明（国际 / 中国）、高驰（中国 / 国际）、Wahoo、MyWhoosh、Zwift、Keep、咕咚、Zepp、Komoot、松拓、两步路、悦跑圈、CyCity、Polar、Fitbit、Withings、TrainingPeaks。

> 以上平台当前作为**数据来源（下载）**接入；**上传目标**只开放 Outbase 与 Strava。

### 同步目标

- **Outbase**：沿用迈进OB 的全平台上传链路，能力不退化。
- **Strava**：通过网页端 cookie 协议上传，三步：
  1. `GET /upload/select` 取 `csrf-token`（3xx / 401 / 403 判登录失效）
  2. `POST /upload/files` 提交 multipart：`_method=POST` + `authenticity_token` + `files[]`，并带 `X-CSRF-Token` / `X-Requested-With` / `Referer` / `Origin`
  3. `GET /upload/progress.json?ids[]=<id>` 轮询入库进度（约 2 秒一次，最多 12 次），未完成记为待确认，不当成功

设置页内置「测试会话」与「试传一条」两个自检入口：前者直接显示带 HTTP 状态码的登录态明细，后者可选本地 `.fit` / `.gpx` 走完整链路，便于区分是凭证问题还是协议问题。

> Strava cookie 由内置 WebView 登录后取得并保存到本地键值库，不会上传到任何第三方服务器。

---

## 构建

环境要求：

- JDK 17
- Android SDK Platform 36、Build-Tools 35.0.0
- Gradle 使用仓库自带 wrapper

```bash
# 在 local.properties 里配置 sdk.dir 与 release 签名（该文件已被 .gitignore 排除）
./gradlew --offline assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名配置从 `local.properties` 读取（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），**请勿把签名文件与密码提交到仓库**。

---

## 项目结构

```
app/src/main/java/com/jichi/ob/
├─ MainActivity.kt          # 主界面、同步编排、登录回调
├─ AutoSyncWorker.kt        # 后台自动同步（跟随设置页所选目标）
├─ api/                     # 各平台接口与上传引擎（OutbaseApi / StravaApi / UploadEngine …）
├─ model/                   # DataSource 平台定义、活动模型、上传支持矩阵
├─ ui/                      # 登录 / 设置 / 同步 / 关于 四大页面
└─ util/                    # 凭证与设置持久化、日志、文件
```

命名空间仍是 `com.jichi.ob`（沿用上游代码结构），对外包名为 `com.wawajiao.sync`，二者分离属正常设计。

---

## 已知限制

- Strava 上传依赖网页 cookie 协议，属于非官方接口，Strava 侧策略调整可能导致失效；失效时自检入口会显示 401 / 403 / 302。
- 各数据源平台的接口随时可能变化，部分平台为半自动或实验性接入。
- 后台自动同步在「目标未登录」时直接跳过并通知，**不会**回退到另一个目标。
- Strava 判定为重复活动时会返回错误，App 会显示「上传失败」——这是平台的去重保护，不是程序故障，换一条记录或到网页端核对即可。
- 本项目尚未完成大规模真机回归，重要数据请先在少量记录上验证。

---

## 免责声明

本项目仅供**个人运动数据备份与迁移**使用，与 Outbase、Strava 及各数据源平台均无隶属或合作关系。

- 请遵守各平台的用户协议与接口使用规则，不要高频请求、批量爬取或用于商业分发。
- 使用本项目造成的账号异常、数据丢失、记录重复或平台风控，作者不承担任何责任。
- 所有凭证（cookie、token）仅保存在你本机，项目不含任何遥测与数据上报逻辑。
- 软件按 MIT 协议「按现状」提供，不附带任何明示或暗示担保。

---

## 鸣谢

本项目站在两个开源项目的肩膀上，特此致谢：

| 项目 | 作者 | 作用 | 许可 |
|---|---|---|---|
| [鸡翅幸哲迈进OB](https://github.com/Anathleticbicyclist/marching-ob-manager) | Anathleticbicyclist | 代码基底：平台接入、FIT/GPX 处理、同步引擎、Outbase 上传链路 | MIT |
| [顽爪爪 WanSync](https://github.com/Tyan66666/WanSync) | Tyan66666 | Strava 网页上传协议与流程的参考实现 | 见其仓库 |

同时感谢所有被本项目参考过的开源项目与工具链：Kotlin、AndroidX、Material Components、OkHttp、Gson、Coroutines 等。

上游原始说明文档存档于 [`docs/迈进OB-原始README.md`](docs/迈进OB-原始README.md)。

---

## 如何调用 Skill（用 AI 辅助开发 / 排查同类项目）

本项目的平台接入、FIT/GPX 处理、时区判据与上传排查，配套了一份工程指南 Skill：**`sport-data-sync-kit`**（[仓库](https://github.com/Anathleticbicyclist/sport-data-sync-kit)）。它不是运行时代码依赖，而是给人、也给 AI 用的一套「接平台、改字节、查上传」口径库。

**调用方式（在支持 Skill 的 AI 工作环境里）**

1. 把 `sport-data-sync-kit` 放进 Skill 目录（例如 `~/.doubao/agent_mode/workspace/.user_skills/sport-data-sync-kit/`），确保 `SKILL.md` 与 `references/`、`scripts/` 同级存在。
2. 在对话里直接描述任务，命中下列场景会自动加载；也可以显式点名「用 sport-data-sync-kit 技能」：
   - 接入或调试某个运动平台（iGPSPORT / 行者 / 迈金 / 佳明 / 高驰 / Strava …）
   - 解析、生成、转换 FIT / GPX / TCX，或修正活动时间偏 8 / 16 小时
   - 排查「下载失败」「上传成功但平台没记录」「列表取不到最新记录」
3. 加载后按 `SKILL.md` 的核心工作流走：接入前核查 → 凭证鉴权 → 列表下载 → 格式校验清洗 → 时区判定 → 上传入库 → 回归验证。

**本地脚本（无需 AI 也能用）**

```bash
python scripts/check_fit.py activity.fit    # FIT 魔数 / 长度 / 文件尾 CRC16 校验
```

退出码 0 表示通过；改过字节的 FIT 报 CRC 不一致时，先重算 CRC 再校验。

**在纯命令行环境里使用**

不想装 Skill 框架，就把仓库 clone 下来当手册读：`README` 是索引，`references/platforms.md` 是平台卡片，`references/formats.md` 是字段与单位，`references/pitfalls.md` 是坑位清单。

---

## 许可证

[MIT License](LICENSE) · Copyright (c) 2026 Anathleticbicyclist

本项目为 迈进OB 的衍生作品，遵循同一 MIT 许可；分发时请保留原始版权声明与许可文本。
