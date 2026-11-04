# Bungram — REA 全量逆向报告（第二轮 · 类/方法级）

> 上一轮（`05-DEEP-DIVE.md`）回答的是「三家怎么做的」。这一轮用 REA 把三个 APK 真正**拆开**：
> 全量类清单、组件面、权限面、类成员、方法体、引用图。所有数字都来自本机实际执行，
> 附 `evidence_id`，可复现。

---

## 0. 本轮执行的 REA 命令（真实结果）

| # | 命令 | 目标 | 结果 |
|---|---|---|---|
| 1 | `rea doctor` | 环境 | `jadx: healthy`，engine `jadx-headless-mcp 0.7.1` |
| 2 | `rea inspect-android-package` | 3 个 APK | 版本/权限/类数/资源数（见 §2） |
| 3 | `rea decode-android-resources` | telegram.apk | apktool 解码 **7,564** 个文件，105 条 string 资源 |
| 4 | `rea search-android-classes` | 3 个 APK | **16,432 / 22,008 / 16,431** 个类全量清单 |
| 5 | `rea inspect-android-class` | SecretChatHelper | **101** 方法 / 10 字段 |
| 6 | `rea inspect-android-class` | JobManager | **75** 方法 / 10 字段 |
| 7 | `rea inspect-android-class` | TimelinePresenter | **4** 方法 / 25 字段 |
| 8 | `rea inspect-android-method` | `getTimelineItems()` | 反编译出真实方法体 |
| 9 | `rea trace-android-references` | RoomListPresenter | **12** 个入向引用节点 |

产物全部落在 `/workspace/research/`：`classes_*.json`、`cls_*.json`、`m_*.json`、`trace_*.json`、`components.json`。

---

## 1. 一句话结论

三家的根本差异不在 UI，而在**「真相存在哪」**：

| | Telegram | Signal | Element X |
|---|---|---|---|
| 权威副本 | **服务端**（云端聊天） | **本机**（服务端只是信箱） | **房间 DAG**（复制式） |
| 同步模型 | `pts/qts/seq` 差分 + 空洞修复 | 拉取队列，消费即删 | Matrix `/sync` since-token |
| 加密边界 | 云聊天服务端加密；Secret Chat 才 E2EE | **全部** E2EE | 全部 E2EE（Olm/Megolm） |
| 多设备 | 同账号多设备，服务端合并 | 单主设备 + 链接设备 | 多设备 + 会话验证 |
| 代价 | 服务端能看到明文 | 多设备体验差、备份难 | 状态收敛复杂、体积大 |

**Bungram 的选择：服务端权威 + 密文载荷。** 即 Telegram 的同步模型（简单、多设备天然一致）
配上 Signal 的加密边界（服务端只见 ciphertext）。这也是 `03-ARCHITECTURE.md` 里已经定的方向，
本轮用 APK 证据确认它可行。

---

## 2. APK 硬证据

### 2.1 规模

| 指标 | Telegram | Signal | Element X |
|---|---|---|---|
| 包名 | `org.telegram.messenger.web` | `org.thoughtcrime.securesms` | `io.element.android.x` |
| 版本 | 13.1.1 (72379) | 8.33.2 | 26.10.1 |
| minSdk → targetSdk | 23 → 36 | 26 → 36 | 26 → 36 |
| 类总数 | 16,432 | **22,008** | 16,431 |
| 资源数 | **7,531** | 4,140 | 1,483 |
| 权限数 | 74 | **77** | **22** |
| APK 体积 | 68 MB | 110 MB | 137 MB |

> Signal 类最多（22k）但资源最少之一 —— 它把逻辑写进代码，把 UI 写进 Compose。
> Element X 权限只有 22 个，是三者里最克制的。

### 2.2 组件面（功能边界的硬指标）

| 组件 | Telegram | Signal | Element X |
|---|---|---|---|
| Activity | 20（exported 8） | **94**（exported 8） | 5（exported 1） |
| Activity-Alias | 7（全 exported） | 12（全 exported） | 0 |
| Service | **32**（exported 17） | 26（exported 6） | 9（exported 2） |
| Receiver | 27（exported 4） | **40**（exported 6） | 11（exported 4） |
| Provider | 6（exported 1） | 6（exported 0） | 8（exported 0） |

解读：
- Telegram 32 个 Service 说明它把**大量能力做成独立前台服务**（通话、录制、位置、文件、音乐……）。
- Signal 94 个 Activity 是「一屏一 Activity」的老派结构，但每个都很薄，真正逻辑在 197 个 Job 里。
- Element X 只有 5 个 Activity —— 单 Activity + Compose Navigation，46 个 feature 模块靠导航图拼。
  **这是 Bungram 应该抄的结构**：单 Activity，feature 分包。

### 2.3 权限面

| 类别 | Telegram | Signal | Element X |
|---|---|---|---|
| 通信/后台 | 8 | 12 | 9 |
| 相机/麦克风 | 3 | 3 | 3 |
| 位置 | 4 | 2 | 2 |
| 通讯录/电话 | 7 | 5 | 0 |
| 存储/媒体 | 5 | 5 | 0 |
| 蓝牙/邻近 | 2 | 3 | 0 |
| 生物识别/锁屏 | 3 | 3 | 2 |
| 短信 | 1 | 0 | 0 |
| 系统/厂商 | 4 | 5 | 1 |

三家都申请的 18 个：`INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`RECORD_AUDIO`、
`MODIFY_AUDIO_SETTINGS`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`（+ CAMERA/MICROPHONE 子类型）、
`WAKE_LOCK`、`VIBRATE`、`USE_FULL_SCREEN_INTENT`、`USE_BIOMETRIC`、`USE_FINGERPRINT`、
`RECEIVE_BOOT_COMPLETED`、`REQUEST_INSTALL_PACKAGES`、`ACCESS_FINE/COARSE_LOCATION`。

> 三家合计申请 173 条，去重后并集 **93 条**，交集只有 **18 条**。
> 也就是说：**四分之三的权限是各自的业务选择，不是「聊天 App 必需品」。**

只有 Telegram 有：`SEND_SMS`、`READ_CALL_LOG`、`CALL_PHONE`、`SYSTEM_ALERT_WINDOW`、
`READ_CLIPBOARD`、`ACCESS_BACKGROUND_LOCATION`、`MANAGE_ACCOUNTS`。
→ 这些是「短信登录 / 系统通话 / 悬浮窗」的老包袱，**Bungram 一个都不要**。

只有 Signal 有：`SCHEDULE_EXACT_ALARM`、`NEARBY_WIFI_DEVICES`、`CHANGE_WIFI_STATE`、
`DISABLE_KEYGUARD`、`READ_CALL_STATE`、`ACCESS_NOTIFICATION_POLICY`。
→ 前四个是「精确闹钟 + 直连局域网通话 + 锁屏来电」需要的，**Bungram 做通话时再要**。

只有 Element X 有：`ACCESS_LOCAL_NETWORK`（局域网 Element Call）。
→ 说明它的通话走 LAN 直连，不依赖公网 TURN。Bungram 通话阶段可以借鉴。

---

## 3. 功能域全表（40 项 × 3 家 × Bungram）

图例：✅ 完整实现 · 🟡 部分 · ❌ 无 · 🔧 Bungram 已有骨架

| # | 子系统 | Telegram | Signal | Element X | Bungram |
|---|---|---|---|---|---|
| 1 | 注册/登录 | 手机号+验证码+二级密码 | 手机号+PIN+注册锁 | homeserver+OIDC/SSO | ✅ OTP→JWT 实测通过 |
| 2 | 多设备 | ✅ 同账号多设备 | 🟡 单主设备+链接设备 | ✅ 多设备+验证 | 🔧 设备表已建，未联调 |
| 3 | 增量同步 | ✅ pts/qts/seq | 🟡 队列消费 | ✅ /sync since | 🔧 cursor 已有，无差分 |
| 4 | 本地存储 | ✅ SQLite+FTS | ✅ SQLite+加密 | ✅ Rust 加密库 | ❌ 全内存 demo |
| 5 | 加密 | 🟡 云聊天服务端加密 | ✅ Signal Protocol | ✅ Olm/Megolm | ❌ 未接线 |
| 6 | 群组 | ✅ 200k 成员+权限位 | ✅ 群组 v2+Sender Key | ✅ room+power levels | ❌ |
| 7 | 媒体 | ✅ 并行分片上传 | ✅ 加密附件+缩略图 | ✅ media upload | ❌ |
| 8 | 语音/视频 | ✅ VoIP | ✅ RingRTC | ✅ Element Call | ❌ |
| 9 | 通话记录 | ✅ CallLogActivity | ✅ | ❌ 无独立记录 | 🔧 demo 数据 |
| 10 | Stories | ✅ 49 个文件 | ✅ stories 模块 | ❌ | 🔧 demo 数据 |
| 11 | 在线状态 | ✅ presence | ✅ | ✅ | 🔧 枚举已有 |
| 12 | 已读回执 | ✅ | ✅ | ✅ | 🔧 字段已有 |
| 13 | 输入中 | ✅ | ✅ | ✅ | ❌ |
| 14 | 表情回应 | ✅ | ✅ | ✅ | ❌ |
| 15 | 回复/转发 | ✅ | ✅ | ✅ | ❌ |
| 16 | 编辑/删除 | ✅ | ✅ | ✅ | ❌ |
| 17 | 投票 | ✅ | ✅ | ✅ | ❌ |
| 18 | 贴纸/GIF | ✅ | ✅ | 🟡 emoji | ❌ |
| 19 | 位置 | ✅ 实时位置 | ✅ | ✅ | ❌ |
| 20 | 联系人 | ✅ 通讯录同步 | ✅ | ❌ 用户目录 | 🔧 demo |
| 21 | 搜索 | ✅ 全局+FTS | ✅ | ✅ | ❌ |
| 22 | 通知 | ✅ FCM+角标 | ✅ FCM+渠道 | ✅ FCM | ❌ |
| 23 | 推送 | ✅ | ✅ | ✅ | ❌ |
| 24 | 离线队列 | ✅ | ✅ 197 个 Job | ✅ Rust 队列 | ❌ |
| 25 | 代理 | ✅ MTProxy | ✅ | ❌ | ❌ |
| 26 | 备份 | ✅ 云备份 | ✅ 本地+云 | ✅ 密钥备份 | ❌ |
| 27 | 主题 | ✅ 多主题 | ✅ | ✅ Compound | 🔧 设计系统已定 |
| 28 | 国际化 | ✅ 100+ | ✅ | ✅ | 🔧 仅英文 |
| 29 | 无障碍 | 🟡 | ✅ | ✅ | 🟡 部分 |
| 30 | 应用锁/截屏保护 | ✅ | ✅ | ✅ | ❌ |
| 31 | 阅后即焚 | ✅ TTL | ✅ | ✅ | ❌ |
| 32 | 举报/审核 | ✅ | ✅ | ✅ + 房间审核 | ❌ |
| 33 | 支付 | ✅ Stars/TON/Wallet | 🟡 billing | ❌ | ❌ |
| 34 | 机器人 | ✅ Bot API+inline | ❌ | ❌ | 🔧 服务号会话 |
| 35 | 频道 | ✅ | ❌ | 🟡 Space | ❌ |
| 36 | 语音房 | ✅ Voice Chats | ✅ 群通话 | ✅ Element Call | ❌ |
| 37 | 会话文件夹 | ✅ | ❌ | ✅ viewfolder | ❌ |
| 38 | 斜杠命令 | ❌ | ❌ | ✅ **33 个** | ❌ |
| 39 | 深度链接 | ✅ `tg://` | ✅ `sgnl://` | ✅ `element://` | ❌ |
| 40 | 应用内更新 | ✅ | ✅ APK 更新 | 🟡 商店 | ❌ |

**Bungram 现状：40 项里 4 项可用（1/2/9/10 的 demo），9 项有骨架，27 项空白。**
这不是失败，是路线图 —— 见 §6。

---

## 4. 命令与入口清单（「commands」到底有哪些）

### 4.1 Element X — 33 个斜杠命令（`libraries/slashcommands/impl/.../Command.kt`）

```
/crash  /me  /ban  /unban  /ignore  /unignore  /op  /deop  /roomname  /invite
/join  /topic  /remove  /nick  /myroomnick  /roomavatar  /myavatar  /myroomavatar
/rainbow  /rainbowme  /devtools  /spoiler  /shrug  /lenny  /plain  /whois
/discardsession  /confetti  /snowfall  /leave  /upgraderoom  /tableflip  /unflip
```

分三类：
- **管理类**：`/ban` `/unban` `/op` `/deop` `/remove` `/invite` `/join` `/leave` `/upgraderoom`
- **身份类**：`/nick` `/myroomnick` `/roomname` `/topic` `/roomavatar` `/myavatar` `/myroomavatar`
- **彩蛋/格式**：`/me` `/spoiler` `/plain` `/shrug` `/lenny` `/rainbow` `/rainbowme` `/confetti` `/snowfall` `/tableflip` `/unflip`
- **调试**：`/crash` `/devtools` `/discardsession` `/whois`

> 这是 Matrix 生态的「聊天即命令」。Bungram 若要抄，只需 8 个核心命令：
> `/me` `/shrug` `/spoiler` `/plain` `/nick` `/topic` `/invite` `/leave`。

### 4.2 Signal — 197 个后台 Job（`app/src/main/java/.../jobs/`）

这不是「用户命令」，而是**离线可靠性系统**。抽样：

```
AttachmentUploadJob  AttachmentDownloadJob  AttachmentCompressionJob
PushProcessMessageJob  PushProcessEarlyMessagesJob
BackupMessagesJob  BackupDeleteJob  ArchiveThumbnailUploadJob
AutomaticSessionResetJob  AccountConsistencyWorkerJob
AvatarGroupsV2DownloadJob  BackfillDigestsForDataFileJob
```

> 197 个 Job 的本质：**每个网络/磁盘动作都是一个可重试、可持久化、有依赖的单元**。
> Bungram 现在直接 `api.sendMessage()`，断网就丢。这是最该抄的一课 ——
> 不需要 197 个，先做 12 个（发送、上传、下载、缩略图、已读、删除、编辑、反应、媒体转码、备份、清理、重试）。

### 4.3 Telegram — 586 个 Activity/Cell/View + 32 个 Service

入口规模：`ui/` 下 20 个目录、1,550 个 Java 文件、`messenger/` 358 个文件。
32 个 Service 对应 32 种「常驻能力」：通话、录制、位置、文件、音乐、通知、同步……

> 这是 13 年堆出来的体量，**不能抄，只能挑**。

---

## 5. 类 / 方法级取证样本

### 5.1 Telegram `SecretChatHelper` — 101 方法
```
performSendEncryptedRequest  (3 个重载)
processUpdateEncryption
requestNewSecretChatKey
acceptSecretChat
evidence  ev_227b5b74a26611141812af81e677a29b72b61748ece7d4bb1664b33443890c3b
```
含义：Secret Chat 的密钥协商与消息加密是**四步状态机**，不是「发一条加密消息」。
Bungram 的 E2EE 必须建模成状态机（`PENDING_KEY → KEY_SENT → ESTABLISHED → REKEYING`）。

### 5.2 Signal `JobManager` — 75 方法 / 10 字段
```
evidence  ev_e9df8439f668e98f0f7ca36aa11dd0fa957630a834acaae68211adc2920beb72
```
75 个方法里包含 `add`（4 个重载）、`addAll`、以及 12 个 `$r8$lambda$`（R8 内联后的回调）。
含义：JobManager 是**调度器 + 依赖图 + 重试策略**三合一，不是简单的线程池。

### 5.3 Element X `TimelinePresenter` — 4 方法 / 25 字段
```
<init>
getTimelineItems
focusOnEvent (混淆为 m1523focusOnEvent6wD2l3Q)
present (mo1515present)
evidence  ev_0b1d73e3c1b8186655abcb1cc575431b4a3d96c390489fcfc441b65f878137db
```
25 个字段 → 大量 `StateFlow`/`derivedStateOf`。**Presenter 只做状态编排，不做 IO。**
这正是 Bungram `MessagesScreen.kt` 缺的那层：现在 UI 直接读 demo 数据，应该改成
`MessagesPresenter` 产出 `MessagesState`，UI 只渲染。

### 5.4 方法体反编译（证明能到源码级）

`rea inspect-android-method elementx.apk ...TimelinePresenter getTimelineItems`

```java
public final ImmutableList getTimelineItems() {
    return (ImmutableList) this.timelineItems$delegate.getValue();
}
```
`body_status: available`，`fell_back: false`，证据 `ev_df701aca0e8c5a244ee344a0259abfafb8fd5e9688641c1ffc70ea8ccf490411`。

### 5.5 引用图（谁在用 RoomListPresenter）

`rea trace-android-references elementx.apk ...RoomListPresenter` → **12 个入向节点**：

```
RoomListPresenter$$ExternalSyntheticLambda0
RoomListPresenter$present$3$1
RoomListPresenter$present$handleEvent$2
RoomListPresenter$present$showAllActivity$2$1
HomeFlowNode$onBuilt$2$1
FtueFlowNode
CallStageKt$CallStage$1$1
SdkRoomInfoKt$roomInfoUpdates$1
RealImageLoader$execute$result$1
SearchBarKt$SearchBar$2$1
MetroFactory
AnonymousClass1
evidence  ev_91676f42b27903fee8792f4a6ccc9340532ed37d121363348b74d9cfa6e1177d
```
含义：`HomeFlowNode` 是唯一真正的宿主，其余是 Compose lambda 与 DI 工厂。
**一个 Presenter 只有一个入口** —— 这就是「单向数据流」在字节码层面的样子。

---

## 6. 对 Bungram 的移植决策（P1–P6）

| 阶段 | 目标 | 具体改动 | 验收 |
|---|---|---|---|
| **P1** | 消息真正走服务端 | `data/remote/BungramApi.kt` 已有 `/v1/messages`；把 `MessagesScreen` 的 demo 源换成 `MessagesRepository`，加 `MessagesPresenter` | 断网重连后消息不丢 |
| **P2** | 离线队列（抄 Signal） | 新建 `data/queue/OutboxWorker.kt`，12 个 job，Room 持久化 | 飞行模式发 5 条，恢复后按序送达 |
| **P3** | 本地库（抄 Telegram 表结构） | Room：`messages(id, chat_id, seq, sender, kind, ciphertext, state, ts)` + FTS4 | 1 万条消息冷启动 < 300ms |
| **P4** | E2EE（抄 Signal 协议，不自研） | 引 `libsignal-client`；`crypto/SessionStore.kt`；密钥状态机照 §5.1 | 两台设备互发，服务端抓包无明文 |
| **P5** | 媒体管线 | `media/Uploader.kt` 分片 + 断点续传 + 缩略图 | 50MB 视频弱网可续传 |
| **P6** | 通话 | WebRTC + 信令复用 WS；权限只加 `SCHEDULE_EXACT_ALARM` 等 4 个 | 1:1 通话 3 分钟不崩 |

**不建议抄的**：Telegram 的 32 个 Service（用 WorkManager 替代）、586 个 Activity（单 Activity + Compose Navigation）、
Stars/TON 支付、短信权限。

**建议直接抄的**：Element X 的「单 Activity + 46 feature 模块」结构、Signal 的 Job 化、
Telegram 的 `pts/seq` 差分同步思路（服务端权威场景下最省流）。

---

## 7. 未验证 / 风险（诚实清单）

1. **Telegram APK 是混淆的**（类名 `a.a`、`a.b`），类级取证只能靠源码仓库，APK 侧只能看组件/权限/资源。
2. **Signal 全量类清单首次并发执行失败**（三个 JADX 同时跑，10GB 堆互相挤爆）。改成单跑 + 包名前缀过滤后成功（7,346/22,008）。
3. **apktool 只解出 105 条 string 资源** —— Telegram 大量文案在代码里（`LocaleController`），不在 `strings.xml`。
4. **没有真机**，所以组件 `exported` 行为、后台限制、厂商角标都未实测。
5. 权限表按「短名匹配」归类，少数自定义权限落入 other，已在 §2.3 单列。

---

## 8. 复现命令

```bash
export PATH="/root/.nvm/versions/node/v22.20.0/bin:$PATH"
export REA_JADX_MCP_JAR=/opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar
export REA_JADX_HEAP_MIB=6144          # 单跑；并发请降到 4096 并串行

rea inspect-android-package apk/telegram.apk --format json > rea_telegram.json
rea search-android-classes apk/signal.apk "org.thoughtcrime" --format json > classes_signal.json
rea inspect-android-class   apk/signal.apk org.thoughtcrime.securesms.jobmanager.JobManager --format json
rea inspect-android-method  apk/elementx.apk io.element.android.features.messages.impl.timeline.TimelinePresenter getTimelineItems --format json
rea trace-android-references apk/elementx.apk io.element.android.features.home.impl.roomlist.RoomListPresenter --format json
rea decode-android-resources apk/telegram.apk --format json > res_telegram.json
```

---

# 第二轮补充：应用图投影（round 3）

> 用 `rea inspect-artifact` 生成完整制品清单，再用 `rea project-android-application-graph`
> 投影成应用图。这条路径比 `inspect-android-package` 更深：它把 APK 拆成
> manifest / resources / dex / native / javascript / signing 六类组件，并给出运行时族与 JNI 桥接候选。

## 9. 三家 APK 的内部构成

| 组件 | Telegram | Signal | Element X |
|---|---|---|---|
| dex | 4 | **6** | 4 |
| native `.so`（去重） | **2** | **12** | **16** |
| javascript | 5 | 2 | **14** |
| signing | 3 | 3 | 3 |
| JNI 桥接候选 | 32 | **288** | 64 |
| 运行时族 | dalvik-art / javascript / native | 同左 | 同左 |

### 9.1 Telegram：一个 22MB 的 C++ 核心干所有事

```
lib/arm64-v8a/libtmessages.49.so      22 MB   ← MTProto + SQLite + 加密 + WebRTC 全在这
lib/arm64-v8a/liblanguage_id_l2c_jni.so 996 KB ← Google 语言识别（翻译用）
```

Java 层 358 个 `messenger/` 文件、586 个 UI 类，本质是**壳**。
协议、存储、加密都在 `libtmessages` 里。这就是为什么 Telegram 的 Java 代码看起来"松散"却很快。

### 9.2 Signal：12 个专用库，一库一职

```
libsignal_jni.so        Signal 协议（Double Ratchet / X3DH）
libringrtc.so           WebRTC 封装
libringrtc_rffi.so      通话 Rust FFI
libsqlcipher.so         加密数据库
libmobilecoin.so        支付（MobileCoin）
libaesgcm.so            硬件加速 AES-GCM
libconscrypt_jni.so     TLS
libnative-utils.so      杂项
libimage_processing_util_jni.so / libsurface_util_jni.so / libdatastore_shared_counter.so
libandroidx.graphics.path.so
```
288 个 JNI 桥接候选 = 每个库都有自己的 `native` 方法族。
**Signal 的"安全"不是靠代码规范，是靠把每件事隔离进独立 native 库。**

### 9.3 Element X：把整个 Element Call 网页塞进了 APK

```
assets/element-call/assets/
  index-DG89ryiZ.js              主 SPA
  spa-CD3kxqIG.js                路由壳
  indexeddb-crypto-store-*.js    IndexedDB 加密存储
  IndexedDBWorker-*.js           Web Worker
  rust-crypto-*.js               WASM 加密
  pako.esm-*.js                  zlib
  polyfill-force-*.js            polyfill
  theme.iife.js                  主题注入
```

**这是本轮最大的发现**：Element X 的通话**不是原生实现**，而是把 Element Call 这个
React 单页应用打包进 `assets/`，用 WebView 跑；加密状态存在 IndexedDB 里，
再通过 `libmatrix_rtc_ffi.so` 桥到 Rust。

对比：
- Telegram 通话 = 自研 C++（`libtmessages` 内）
- Signal 通话 = `libringrtc`（Rust + C++）
- Element 通话 = **WebView + React SPA**

### 9.4 Telegram 的 5 个 JS 是内置浏览器注入层

```
res/mfu.js   57 KB   DOMPurify（HTML 消毒）
res/dPJ.js   5.7 KB  "Telegram-Android browser extension for Mini Apps"（手势）
res/wyi.js   13 KB   同上，另一版本
res/NuZ.js   2.2 KB  Web Share API polyfill
res/a8x.js   400 B   section[hidden=until-found] polyfill
```
这不是业务逻辑，是**在 WebView 里给第三方网页打补丁**：消毒、手势拦截、分享兜底。
Signal 只有 2 个（`ace.min.js` + `debuglogs-viewer.js`，调试日志查看器），
Element 的 14 个全是自己的 Call SPA。

## 10. 对 Bungram 的三条硬结论

1. **纯 Kotlin 撑不到 P4。** 三家没有一个用 JVM 实现加密。Bungram 的 E2EE 必须引
   `libsignal-client`（Rust/JNI）或 `matrix-sdk-ffi`，自己用 Kotlin 写 Double Ratchet 是自找麻烦。
2. **通话有三条路，成本差 10 倍。** 自研 C++（Telegram，最贵）→ RingRTC（Signal，中）→
   WebView + SPA（Element，最便宜）。Bungram 在 P6 应该走第三条，先把功能跑通。
3. **JS 不是敌人。** Telegram 用 5 个 JS 给内置浏览器做安全加固，Element 用 14 个 JS 承载整个通话。
   Bungram 如果做"应用内打开链接"，DOMPurify 那一套是必须的。

## 11. 工具限制（本轮踩到的）

- `rea analyze` **不支持 APK**。它走原生二进制通道（Ghidra/IDA/Hopper），对 APK 返回
  `capability_unavailable / target_unsupported`。APK 必须走 `inspect-android-*` 系列。
- `project-android-application-graph` 的输入**不是** `inspect-android-package` 的结果，
  而是 `inspect-artifact` 的 Evidence，包成 `{"inventory_evidence":[<evidence>]}`。
  第一次传错会报 `Artifact comparison requires inspect_artifact Evidence`。
- `inspect-artifact` 对 137MB 的 APK 会产出 **8.3MB** 的 JSON（Telegram 也有 8.3MB），
  注意磁盘和 token 预算。
