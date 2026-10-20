# 小米运动健康 `com.mi.health` — 表盘试用永久化逆向

> **已归档（2026-10-20）**：该目标 2026-10-14 关线（未注册 TargetRegistry、功能不可用）⇒
> 逆向记录移入 `docs/reverse/archive/`，仅作历史证据；关线理由见本文件 §关线。

> 基线 **3.59.1**（真机 pull，`dev.sh pull-apk` 实证）。
> 状态：hook 已落码 **v3「试用截获 → 免费流装载」**（`MiHealthHook.kt`，文件层手术），判据以真机自执行验证清单为准（本文 §Z·判据）。控制通道手术 v1/v2（§Y）已双证死透，仅作历史记录保留。

## 一、目标形态

表盘商店是 **React Native 应用**（dial bundle）。业务逻辑在 JS 侧，Java 层只是容器 + BT 推送通道
——业务类名随 RN bundle 周更动态化随时漂移，**业务层类不是可用锚点**；BT 通道
`YRNBluetoothModule`（类未混淆，jadx 复核）是现行唯一挂载面。

## 二、数据链（实证）

付费表盘安装链：

```
list/tab 接口（face 记录驱动 UI 按钮状态机：price / purchaseStatus / onTrial）
  → GET /ops/v1/paidwatchface/download
      响应 JSON：
        downloadInfo.configFileV2   # CDN 签名 URL，Expires=Long.MAX 永不过期
        license                     # JSON 串，含 trial_duration
        sign
  → JS 侧 license 非空 ⇒ BT 推送 verification{info, sign, trialDuration} ⇒ 试用
    JS 侧 license 空   ⇒ 免费安装
  → startDownload(url, md5) → prepareInfo{size, versionCode, id, verification{...}}
      经 YRNBluetoothModule BT 推手表
```

免费表盘走**同链路同结构**（实证：license 为空的响应可正常走完安装）。

## 三、方案历史（均已废弃）

> **现行方案 = v3 免费流装载（§Z）。** 本节仅留演进脉络。

原三件套方案（A 缓存回放 / B 上游 license 置空 / C UI 免费化，挂 okhttp Builder.build）
**已废弃（2026-10-13）**：响应层不可行——download/list 响应是 Mi WUP 加密二进制，okhttp 链
任何位置都拿不到明文（死路实证见 §X）。

B 轨 BT wire 手术（挂 `sendRaw` 改 `PrepareInfo.verification` 字节）**已废弃（2026-10-13）**：
v1 抹空 verification、v2 保 info/sign 改 trialDuration 均被手表拒收（双证见 §Z·一），代码保留
为历史记录，不再生成挂载任务。

| 接缝 | 内容 | 判定 |
|---|---|---|
| B' BT 出包字节 | sendRaw 未混淆、所有 WearPacket 出手表必经；字节可手术 | **v1/v2 实测：手表校验 sign，改字节死路** |
| C 手表端 sign 覆盖范围 | 手表端校验哪些字段被 sign 覆盖 | **已实证：sign 覆盖全字段（v1/v2 双拒收）** |
| 文件层（§Z） | 传输的就是文件本身，参数零改动 | **现行（v3）** |

## X、响应层死路实证（2026-10-13 真机 [diag]）

okhttp 挂载期三轮诊断，全部拿到密文：

1. **头插**（builder.interceptors() 头部）：download 响应 body = Base64，len=1560；
2. **尾插**（列表末 = 最内层 application interceptor，位于宿主 CloudInterceptor 之后）：
   同样 Base64 密文（len 1560）；
3. **消费式 body.string()**（真正消费 body，走宿主惰性解密包装）：仍为 Base64；
   b64 解码 round1 = **二进制乱码**（非 JSON/文本）——即响应是 Mi WUP 加密二进制的
   base64 传输封装，解密不在 okhttp 层。

jadx 复核宿主 `HttpHandlerInterceptor` = 空壳，无解密逻辑；解密发生在 **JS/桥层**
（RN dial bundle 内）。结论：**响应层（okhttp 拦截器/缓存/回放/JSON 免费化）整体不可行**，
相关 hook 代码已删除。

## Y、BT wire 手术（v1/v2 历史，已死透——本节仅存档）

### 链路

```
JS 侧 protobuf.js 序列化 WearPacket（common bundle 内 encoder，字段号已核对）
  → base64 字符串
  → com.xiaomi.wearable.yrn.modules.bluetooth.YRNBluetoothModule.sendRaw(requestData: String,
      options: ReadableMap, callback: Callback)     # @ReactMethod，未混淆
      内部 base64 decode → DeviceContact.call(did, 101, bytes)   # jadx 复核
  → BT 推手表
```

### proto schema（字段号已核对）

```
WearPacket                  { 1: type(int32), 2: id(uint32), 6: watchFace(msg) }
WatchFace                   { 6: prepareInfo(msg) }
PrepareInfo                 { 1: id(string), 2: size, 3: versionCode, 5: verification(msg) }
PrepareInfo.Verification    { 1: info(string), 2: sign(string), 3: trialDuration(varint) }
```

顶层 tag 速查：field1 varint=0x08 / field2 varint=0x10 / field6 len=0x32；
WatchFace 内 prepareInfo=0x32；PrepareInfo 内 verification=0x2A。

语义：type=4(WATCH_FACE) 且 id=4(PREPARE_INSTALL_WATCH_FACE) 的包带试用授权
`verification{info=license, sign, trialDuration}`。

安全边界（历史）：rewrite 带结构轻校验，任一步解析失败/字段不唯一 → null 放行原包；
hook 回调整体 runCatching，任何异常 = 原样 proceed，绝不 throw。

### 判据（历史，v1/v2 均已实测拒收）

- v1（verification 抹空 tag+len=0）：判据日志命中×2、手表拒收安装失败 ⇒ **手表校验 verification 结构**。
- v2（保 info/sign 原字节，仅 trialDuration→36500）：同样拒收 ⇒ **sign 覆盖全字段，改任何字段死路**。

## Z、v3 免费流装载（现行方案）

### 一、为什么离开控制通道（v1/v2 拒收实证）

2026-10-13 真机两轮安装实验：

1. **v1**：sendRaw 侧把 `PrepareInfo.verification` 整段抹成零长消息（tag 保留 + len=0 =
   全默认值 = 免费安装语义）→ 判据日志命中，**手表拒收，安装失败** ⇒ 手表校验 verification。
2. **v2**：保 `info`/`sign` 原字节，仅把 `trialDuration` 改 36500 → 同样**拒收** ⇒
   sign 覆盖不止 license 串（全字段）。

结论：**prepareInfo 一个字节都不能动**。v3 完全不碰 prepareInfo（历史实证免费流手表不校验
文件↔表盘绑定），转文件层。

### 二、挂载点（jadx 已复核）

`com.xiaomi.wearable.yrn.modules.bluetooth.YRNBluetoothModule`（类未混淆）：

```
@ReactMethod sendFileToDevice(did: String, type: Int, detailType: Int, path: String,
                              segmentLength: Int, options: ReadableMap, finishCallback: Callback)
```

7 参，未混淆；`path`（args[3]）= 待传文件的本地路径。试用付费表盘时此方法被调用、path 指向
临时文件（**安装后即删，错过即无**）；免费表盘走同一方法——参数无法区分付费/免费流。

### 三、截获 / 装载 / md5 判别

语义「试用截获 → 免费流装载」，同一挂载点两种模式：

- **截获**：持久目录（`<dataDir>/files/honorhook_face_payload/`）无 `payload.json` → 本次是
  截获：把 path 文件字节复制为 `payload.bin` + 写 `payload.json`
  （`{"srcMd5":"…","srcLen":…,"savedAt":…}`）+ 判据日志。faceId 在 JS 层，元数据角度拿不到，不存。
- **装载**：有截获记录且当前 path 文件 md5 ≠ `srcMd5`（= 本次传输的不是截获那份，即免费表盘
  安装）→ 用 `payload.bin` 覆写 path 处临时文件，**参数零改动原样 proceed**（文件在盘上被换，
  传输层照常读）。
- **判别锚**：临时文件路径含表盘 hash 不可靠、付费/免费流参数不可分 → 唯一可靠锚 = 文件内容
  md5（`java.security.MessageDigest` 流式计算）。重装同一付费表盘（服务端重发，md5 相同）不会
  自装载；装载不一次性消耗，每次非同 md5 安装都换（可给多个免费位换装）。
- **覆写细节**：临时文件可能正被宿主持有写句柄 → 清空重写（`RandomAccessFile setLength(0) +
  write`，同 inode）而非 delete+create，避免句柄失效；payload 先整体读入内存缩小截断窗口。
  写失败 → WARN + 放行（传输原文件）。
- **异常边界**：挂载回调整体 runCatching，任何异常 = 不动文件放行；元数据缺失/损坏 =
  重新截获（良性自愈）；`proceed` 唯一出口（防二次调用）。

### 四、判据清单（用户自执行）

1. 挂载：模块日志 `MiHealthHook: installed 1/1`（单挂载点 sendFileToDevice）；hook 就绪时另有
   `mih-face: sendFileToDevice hook 就绪` 一行。
2. 试用安装付费表盘 → 判据日志
   `mih-face: 付费表盘文件已截获 len=<len> md5=<md5 前 8>`（每进程 1 次）。
3. 再装一个**免费**表盘 → 判据日志
   `mih-face: 已装载截获文件 len=<newLen>（原 len=<srcLen>）`（每进程 1 次）。
4. **手表上显示的是付费表盘 = 成功**；手表拒装 / 装完闪退黑屏 = 失败关线（文件↔表盘绑定被
   手表校验，回填本文档）。

### 五、风险

- **文件大小/结构校验未知**：手表是否校验收到文件与安装请求中 size/id 的一致性 = 黑箱。
  历史人工实证「免费安装 + 换文件」可行，但换文件的具体时点/方式与 v3 不完全同构。
- 付费/免费文件大小差异大时，传输时长异常可能触发宿主侧超时（未实证）。
- 首次截获的时机依赖「试用安装走 sendFileToDevice 且文件此刻完整落盘」——若宿主改用其它
  传输方法/流式直传，判据 2 缺行即暴露。

## 六、运维注记

- RN dial bundle **周更动态化**：JS 侧业务逻辑随时漂移，仍属赌注，漂移时按日志缺判据行排查。
- v3 持久目录 `<dataDir>/files/honorhook_face_payload/`：截获记录跨进程存活；排查时可看
  `payload.json` 的 srcMd5/srcLen 与判据日志对账。
- v1/v2 的 proto 字段号漂移防护（结构轻校验）逻辑仍在 `MiHealthHook.kt`（未挂载），供后续
  变体实验复用。

## §关线（2026-10-14 用户拍板）

三轮真机实验全灭，手机侧攻击面穷尽：

| 轮 | 手法 | 手表反应 |
|---|---|---|
| v1 | verification 整段置零长（免费安装语义） | 拒收（抹空判据命中×2） |
| v2 | 保 info/sign 原字节，trialDuration→36500 | 拒收（判据命中，sign 盖全字段） |
| v3 | sendFileToDevice 截获付费文件 + 免费流装载（prepareInfo 零改动） | 拒收（截获/装载判据全命中，文件内容被校验或绑定） |

结论：手表端三重校验（verification 签名 / 时长字段 / 文件内容），控制通道与文件通道都不可伪造。
残留活路（非 hook）：
1. **试用循环**：trialDuration 单位=分钟（默认 10 分钟还原），服务端对已试表盘重发授权已实证（2026-10-13 23:38 重试 200+装上）→ 还原后再点试用即可循环；
2. 历史上「免费表盘+换文件」可装 = 旧机型/旧版本行为，本机型（AAK 手机 + 3.59.1）不复现。

分支 worktree-mih-face 已删；本文件为唯一存档（代码不进 dev）。
