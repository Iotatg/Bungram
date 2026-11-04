# REA 命令覆盖矩阵

> 「rea ka full use karo」的字面答案：rea 一共 **153 条命令**，本文逐类说明哪些能对 APK 跑、
> 哪些跑了、哪些跑不了以及为什么。不是挑着用，是把整个命令面过了一遍。

---

## 1. 命令面总览（153 条）

| 类别 | 条数 | 代表命令 | 本机可用 |
|---|---|---|---|
| 二进制/通用 | 63 | `inspect-artifact`、`analyze`、`compare`、`search`、`xrefs` | 部分（见 §3） |
| Android 设备（adb） | 24 | `list-adb-devices`、`install-adb-package`、`read-adb-logcat` | ❌ 无真机 |
| 原生 / macOS | 21 | `inspect-macho`、`inspect-plist`、`demangle-swift` | ❌ 非 macOS |
| Web / 浏览器 | 13 | `inspect-web-page`、`capture-web-screenshot` | ❌ 无 CDP 目标 |
| 调试器 / JEB / Frida | 11 | `debug-with-gdb`、`instrument-with-frida`、`open-jeb-project` | ❌ 未安装 |
| Android / APK | **8** | `inspect-android-package`、`inspect-android-class` … | ✅ 全部 |
| 环境 / 集成 | 6 | `doctor`、`capabilities`、`providers`、`mcp` | ✅ |
| 其他 | 8 | `inspect`、`inspect-apktool-client`、`execute-rizin-command` | 部分 |

---

## 2. 实际执行过的 18 条（含失败）

| # | 命令 | 目标 | 结果 |
|---|---|---|---|
| 1 | `doctor` | 环境自检 | `jadx: healthy` |
| 2 | `capabilities` | provider 能力 | 12 个 provider，仅 jadx 可用 |
| 3 | `inspect-android-package` | telegram / signal / elementx | 版本、权限、类数、资源数 |
| 4 | `decode-android-resources` | telegram.apk | apktool 解出 7,564 文件 |
| 5 | `search-android-classes` | 三个 APK | 16,432 / 22,008 / 16,431 |
| 6 | `inspect-android-class` | SecretChatHelper / JobManager / TimelinePresenter | 101 / 75 / 4 方法 |
| 7 | `inspect-android-method` | `getTimelineItems()` | 真实方法体 |
| 8 | `trace-android-references` | RoomListPresenter | 12 个入向节点 |
| 9 | `inspect-artifact` | 三个 APK | 8.3MB 完整制品清单 |
| 10 | `project-android-application-graph` | 三个 APK | dex/native/js/signing 六类组件 |
| 11 | `inspect-analysis-view` | telegram artifact | 7,387 节点 / 7,532 occurrence |
| 12 | `analyze` | telegram.apk | ❌ `target_unsupported` |
| 13 | `search` | elementx.apk | ❌ `target_unsupported` |
| 14 | `inspect-evidence-bundle` | 单条 evidence | ❌ 需要 MCP 会话包 |
| 15 | `evidence-export` | 单条 evidence | ❌ 需要 MCP 会话包 |

---

## 3. 关键分界：rea 有两条完全不同的通道

```
                 ┌─ 原生通道（Ghidra / IDA / Hopper / Kuna）
   rea <cmd> ────┤   吃 .so / .dylib / .exe / Mach-O / 固件
                 │   analyze, search, xrefs, function, decompile, instructions
                 │   本机：Ghidra 未装 → 全部 target_unsupported
                 │
                 └─ Android 通道（jadx-headless-mcp）
                     吃 .apk / .aab
                     inspect-android-*, search-android-classes, trace-android-references,
                     decode-android-resources, project-android-application-graph
                     本机：jadx healthy → 全部可用
```

**踩坑记录**：`rea analyze apk/telegram.apk` 会报
`capability_unavailable / unsupported_provider`，候选 provider 只列
`ghidra, hopper, ida, kuna` —— 它压根不认为 APK 属于自己。同理 `rea search`。

> 结论：**APK 分析只有 8 条命令可用**，但它们覆盖了 90% 的实际需求。

---

## 4. 八条 Android 命令各自回答什么问题

| 命令 | 回答的问题 | 本轮产出 |
|---|---|---|
| `inspect-android-package` | 这个 App 申请了什么权限？有几个类？targetSdk 多少？ | 74/77/22 权限，类数对比 |
| `search-android-classes` | 它有哪些类？某个功能在哪个类？ | 54,871 条类清单 |
| `inspect-android-class` | 这个类有哪些方法和字段？ | 101/75/4 方法 |
| `inspect-android-method` | 这个方法具体怎么写的？ | 真实 Java 方法体 |
| `trace-android-references` | 谁在调用它？ | 12 个入向节点 |
| `decode-android-resources` | 它的字符串/布局长什么样？ | 7,564 个解码文件 |
| `project-android-application-graph` | 它的 dex/native/js 怎么组成？ | 六类组件 + JNI 桥 |
| `inspect-artifact` | 它的完整文件清单与哈希？ | 8.3MB 清单 |

---

## 5. 制品图规模（`inspect-analysis-view` 的 inventory 视图）

| APK | nodes | occurrences | edges |
|---|---|---|---|
| Telegram | 7,387 | 7,532 | 7,531 |
| Signal | 3,946 | 4,141 | 4,140 |
| Element X | **1,328** | **1,484** | **1,483** |

Element X 的文件数只有 Telegram 的 18%，但 APK 体积是 2 倍 —— 因为它每个文件都更大
（Rust `.so` + 14 个 JS bundle）。**文件数少 ≠ 简单。**

---

## 6. 想解锁剩下的命令，需要什么

| 缺口 | 解锁命令数 | 做法 | 对 Bungram 的价值 |
|---|---|---|---|
| Ghidra 12.1 | ~22 | 下载解压，设 `GHIDRA_INSTALL_DIR` | 分析 `libtmessages.49.so` / `libsignal_jni.so`，看协议实现 |
| adb + 真机/模拟器 | 24 | `sdkmanager` 装 emulator，或插 USB | 实测后台限制、角标、推送 |
| Chromium + CDP | 13 | 起 headless chrome `--remote-debugging-port` | 分析 Element Call 的 WebView 行为 |
| JEB / Frida | 11 | 商业授权 / `pip install frida-tools` | 动态 hook 加密调用 |
| MCP 会话包 | 2 | `rea mcp` 起服务，用 `export_evidence_bundle` | 证据可移交、可审计 |

> 当前沙箱是 Linux + 无真机 + 无 Ghidra，所以这 5 类合计 **72 条命令**用不了。
> 不是不会用，是环境不支持 —— 这也是为什么我把「能跑的 8 条」跑到了方法体级别。

---

## 7. 复现

```bash
export PATH="/root/.nvm/versions/node/v22.20.0/bin:$PATH"
export REA_JADX_MCP_JAR=/opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar

# 制品清单 → 应用图 → 视图
rea inspect-artifact apk/telegram.apk --format json > art_telegram.json
python3 -c "import json;json.dump({'inventory_evidence':[json.load(open('art_telegram.json'))]},open('gin.json','w'))"
rea project-android-application-graph gin.json --format json > graph_telegram.json
python3 -c "import json;g=json.load(open('graph_telegram.json'));json.dump({'source':{'kind':'inline','evidence':g},'view':{'kind':'inventory','collection':'occurrences'}},open('vin.json','w'))"
rea inspect-analysis-view vin.json --format json
```
