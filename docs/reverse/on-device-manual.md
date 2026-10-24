# 安卓逆向 on-device 手册（手机上独立完成：环境 → 逆向 → 版本适配）

> 适用场景：**没有 PC**，在手机（Termux/DSH + MT 管理器）上对已安装或本地 APK 做逆向、
> 以及 LSPosed 模块的宿主版本适配。桌面机全量反编译路线见 [`../reverse-craft.md`](../reverse-craft.md)
> 与 `.claude/skills/reverse-decompile`；本文只写 on-device 特有的环境契约、流程与陷阱。
> 沉淀自 2026-10-23 实战：主题商店 49 点 / 小红书 9.51 七点 / 短信插件 T4，全部 MT MCP 路线完成。

## 一、环境契约（每条都踩过坑）

| 事实 | 后果 | 处置 |
|---|---|---|
| `/storage` 挂载 **noexec + 无符号链接** | 二进制放工作区跑不起来；软链建不了 | 二进制装进 app 私有 `$PREFIX`（包管理器）；apk/jar 等数据文件可放工作区 |
| DSH 的 dpkg **不做 Termux 路径重写**，deb 内嵌 `com.termux` 绝对路径 | `apt install` 文件解到不存在的路径 | `dpkg-deb --fsys-tarfile X.deb \| tar -x -C <tmp>` 再把 `data/data/com.termux/files/usr/.` 并入 `$PREFIX`；postinst 的 shebang 同理需 sed |
| Termux 编译的 JDK **硬编码路径**：`java.io.tmpdir`、`user.home` 指向 `com.termux` | Gradle 起不来（tmpdir 不存在）、jadx 初始化 `$HOME/.cache` 直接炸 | `JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=… -Duser.home=…"` 覆盖（dev.sh linux 适配层已内置） |
| 真机内存有限（LMK 无情） | jadx 全量反编译 40k 类 97% 处被杀 | jadx 锁 `-Xmx2g -j 1 --no-res --no-debug-info`；Gradle `-Xmx2048m`、Kotlin daemon `-Xmx1536m`、`workers.max=2` |
| apt 源可能缺包/子进程 PATH 被重置 | `apt-get install` 半途死 | `apt-get install --download-only` + 直接 `dpkg -i *.deb`，或按上面的 tar 手动并入 |

工具清单（pkg）：`openjdk-21`（含 jlink）、`android-tools`（adb）、`aapt`/`aapt2`（AGP 需
`android.aapt2FromMavenOverride` 指向 Termux aapt2——官方 build-tools 是 glibc 交叉版，bionic 上
无法执行）；jadx 解包到 `$PREFIX/opt/jadx`。

**最高原则：能不反编译就不反编译。** MT 管理器 MCP（`tools/mtmcp.py`）直读 dex/smali，
按类/按锚点查，零全量反编译、零内存压力。

## 二、版本适配 SOP（六步）

```
① 判据先行      LSPosed 日志 installed N/M 找出失点（tools/dev.sh modlog-grep 'installed'）
② 拉包          su -c "cat $(pm path <pkg> | head -1 | sed s/package://)" > system-files/<pkg>-<ver>.apk
③ MT 开工作区   python3 tools/mtmcp.py list <apk> <前缀>   # 或直接跑 ④
④ 逐点核对      python3 tools/check_points.py <apk> <spec.json>
                → 汇总 存活/漂移/丢失；漂移点进入 ⑤
⑤ 漂移点定位    按 §三 的性价比顺序找新家；锚点证据写进坐标表注释
⑥ 落表 + 构建   XhsPoints overrides / <Target>Map_<版本>.kt / FeatureCatalog.extraVersions
                → dev.sh build + test → 安装 → 真机 A/B（判据行 + 行为）
```

SPEC 格式（`check_points.py`）：`[{"class":"…","points":[["方法名",参数数,"备注id"],…]}]`；
退出码非 0 = 有丢失/漂移，可当门禁。

## 三、漂移点定位手段（按性价比排序，全部实战验证）

1. **功能文案 / 日志串**（dex_strings）：混淆不动的字符串常量是最稳锚点。
   例：`"save video failed:"` 一发命中 noteSaveEntry 新家；`addDownloadTask 强制下载` 命中下载入口。
2. **R8 保留的源文件名**（dex_strings 搜 `XxxHelper.java` / `AimAppView.kt`）：混淆改类名不改
   `source` 属性。例：`u15`→`t15`（SharepreferenceUtils.java）、`bz2`→`ez2`（LiveWallpaperHelper.java）、
   `f71`→`g71`（DownloadItemIfVip.java）——比盲扫类形状快一个量级。
3. **smali 签名反查**（smaliq）：拿旧方法描述符当 query，列出「定义处 + 全部调用点」，
   再在调用点里找语义最近的变化。
4. **同包 sibling 惯性**：一组协作的混淆类通常整包迁移（saveClick `ji9.b`→`yi9.b` 的同时
   videoClick 就在隔壁 `yi9.f`）。先 outline 同包邻居再全库扫。
5. **形状匹配 + 实例化点甄别**：ctor/字段特征（如 `(NoteFeed,int)` 捕获 lambda）会批量命中
   十几个同构类——必须用 xref 找实例化点、看 invoke 体调了什么，**不许唯一候选就采纳**。

## 四、陷阱清单（本轮全部踩过，每个都浪费过时间）

| 陷阱 | 表现 | 处置 |
|---|---|---|
| outline 窗口截断 | 大类只返回前 N 方法 →「方法不存在」**假阴性**（`shouldBlockDetailAd` 曾被误判丢失） | 翻页（`pagination.hasMore` + `mt_apk_continue`）；或用 dex_method 检索交叉验证。`check_points.py` 已内置翻页 |
| smali 描述符**无逗号** | 数逗数参数全错（`(II)` 误算 1 参） | 数 token：正则 `\[*L[^;]+;|\[*[BCDFIJSZ]`；0 参特判 |
| R8 **跨版本同名异类** | `b75.h` 9.51 变成同名 SuspendLambda，「类存在」误判存活 | 类名存在性 ≠ 点存活，必须方法级核对；真身用串锚另找（`g75.h`） |
| 0 方法空壳类 | `mg4.b` 类在但空壳，直连挂载失败 | outline 看方法数；空壳 = 点已死，另找锚 |
| 同构 lambda 批量出现 | 6 个 `(NoteFeed,int)` ctor 候选全同形状 | 实例化点 xref + invoke 体语义甄别；唯一胜出才采纳是 DexKit 纪律，手工同理 |
| 插件 dex 不可达 | mms 智能信息插件是独立 apk，宿主 DexKit 扫不到 | 单独拉插件包开 workspace；插件类名跨 APK 反射留名，直连即中 |
| 模板容器换链 | 类名可读 ≠ 链路不变：卡片渲染整体迁到 AIM 富媒体模板，旧点挂了也不触发 | UI dump 先看按钮所在容器 id 归属哪条链，再决定挂哪 |
| 相对路径 / 瞬时失败 | MT 的 workspace_open 按它自己的 Home 解析相对路径；偶发 `PATH_NOT_FOUND` | 传绝对路径 + 退避重试（`mtmcp.py` 已内置） |
| 安装签名墙 | release 签名包不能被 debug 包覆盖 | 要么保住 release keystore，要么卸载重装（丢 LSPosed 勾选与模块配置） |

## 五、诚实挂载规则（不可违背，违反 = 改坏宿主或静默失效）

- **无锚不挂**：没有内容锚点（串/参数类型序列）的漂移点，宁可版本表填 null 跳过，不猜。
- **形状不同不挂**：新家方法形状对不上（如落盘闸 `(File,…,mime)boolean` → `(…,Uri,Z)void`），
  硬挂会拦全 App 同类操作。
- **null 要留档**：跳过的点在坐标表注释里写清「为什么跳、疑似新家、怎么补」——下轮适配的入口。
- **锚点证据随行**：每个新坐标注明定位依据（串锚/源名/同构），下个人不用重查。

## 六、脚本速查

| 脚本 | 用途 |
|---|---|
| `tools/mtmcp.py` | MT MCP 客户端：outline / smali / strings / names / smaliq / list |
| `tools/check_points.py` | 逐点存活核对（SPEC 驱动，退出码门禁） |
| `tools/adapt_new_version.py` | 桌面机全量反编译路线的适配核对（jadx） |
| `tools/sync-skills.sh` | `.claude/skills` → `.agents/skills` 同步（DSH 读后者） |
| `tools/dev.sh env / build / test / modlog` | 工具链解析 / 构建 / 单测 / 判据日志 |

## 七、MT MCP 使用坑（2026-10-24 实战补录）

| 坑 | 表现 | 处置 |
|---|---|---|
| 会话过期 | 一段时间不动后 outline/list 报 Invalid locator / Missing session | `mt_apk_session_create` 重建即可（`mtmcp.py` 已自动重试） |
| 瞬时 PATH_NOT_FOUND | workspace_open 明明文件在却报不存在 | 退避重试（`mtmcp.py` 已内置 3 次退避） |
| MT 被杀/切后台 | 所有调用 `Connection refused`（8787 不监听） | 把 MT 管理器打开（保持后台存活）再继续 |
| outline 窗口截断 | 大类只返回前 N 方法 →「方法不存在」假阴性 | 翻页 `pagination.hasMore + mt_apk_continue`（check_points.py 已内置）；或 dex_method 检索交叉验证 |
| MT 服务卡顿 | 调用超时（真机后台被限流） | 隔几秒重试；避免在 App 前台高频调用时叠请求 |

## 八、Android 15 执行限制（on-device 工具链新增坑）

| 坑 | 表现 | 处置 |
|---|---|---|
| **非 PIE 静态 ELF 被拒** | Android 15 linker 报 `unexpected e_type: 2`（gh/CLI 官方静态二进制全中） | 用 **Termux 仓库的 PIE 构建**（`apt-get download gh` + `dpkg -x` 解包，绕过 dpkg 路径重写缺陷） |
| **App 目录 `#!` 脚本 exec 被拒** | Go 程序（gh）fork/exec 任何 shell 包装脚本 → `permission denied`（SELinux execute_no_trans） | 让 Go 程序 exec **真 ELF**：把 `$PREFIX/bin/git.real` 拷到 PATH 前部目录；gh 更省事的做法 = 永远带 `-R <owner>/<repo>` 或 `export GH_REPO=…`，根本不 fork git |
| DSH 的 `usr/bin/git` 是包装脚本 | 直接 exec 它必死（shebang `#!/system/bin/sh` 不可达） | 真 ELF = `$PREFIX/bin/git.real`（包装脚本只是重定位 GIT_EXEC_PATH） |

## 九、定位手段补充：资源 id 数值反查消费类

服务端下发的 UI 元素（营销卡/入口行）文案在 dex/资源表**都搜不到**（响应体不入包）。
此时从 UI dump 拿 view-id 名（如 `operationsRecyclerView`），三步反查到消费类：

```
resource_table_names 搜 id 名 → 得数值 id（如 0x7f0a1bad）
→ smali 全文搜该数值常量 → 命中 R$id定义类 + 消费类（如 com.hihonor.module.home.R$id）
→ 消费包内 outline/追链 → 定位 adapter/convert/manager（未混淆方法名直读语义）
```

本轮实战：`天天赚现金` 卡（服务端下发）→ operationsRecyclerView → `module.home.OperationsManager`
→ 5 个 `update*` 写入口断根。**写入口断根优于响应裁剪**：写入方法全是 private void/suspend，
不 proceed 零返回值风险；响应裁剪要懂 bean 结构还要防误伤功能卡。

### 两条配套教训

- **探针先行**：字段实际值没有运行时证据时，先挂只读探针列清单（cardEntityToModel 探针
  实测列出 7 张功能卡的 name/cardId/type），数据在手再写过滤条件——不猜字段值。
- **过度拦截会白屏**：`getHomeModulesData → empty` 当「营销总入口」收口，实测功能卡同管道
  → 首页内容区白屏。撤销记录留在 HonorQinxuanHook 注释里。收口前必须确认管道**只**承载目标内容。
