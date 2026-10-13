# 小米运动健康 `com.mi.health` — 表盘试用永久化逆向

> 基线 **3.59.1**（真机 pull，`dev.sh pull-apk` 实证）。
> 状态：hook 已落码（`MiHealthHook.kt`，B 轨 BT wire 手术版），判据以真机自执行验证清单为准（本文 §Y·判据）。

## 一、目标形态

表盘商店是 **React Native 应用**（dial bundle）。业务逻辑在 JS 侧，Java 层只是容器 + BT 推送通道
——业务类名随 RN bundle 周更动态化随时漂移，**业务层类不是可用锚点**；BT 通道
`YRNBluetoothModule.sendRaw` 未混淆（jadx 复核），是现行唯一挂载面。

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

## 三、方案（现行 = B 轨 BT wire 手术）

> 原三件套方案（A 缓存回放 / B 上游 license 置空 / C UI 免费化，挂 okhttp Builder.build）
> **已废弃（2026-10-13）**：响应层不可行——download/list 响应是 Mi WUP 加密二进制，okhttp 链
> 任何位置都拿不到明文（死路实证见 §X），`MiHealthHook.kt` 中三件套代码已整体删除。

现行方案 = **B 轨 BT wire 手术**（详见 §Y）：挂 `YRNBluetoothModule.sendRaw`，在 BT 出包
字节流上识别 `WearPacket{type=4(WATCH_FACE), id=4(PREPARE_INSTALL_WATCH_FACE)}` 试用包，
把 `PrepareInfo.verification` 整段抹成零长消息（tag 保留 + len=0 = 全默认值）。手表按
免费安装收货 = 试用永不过期。不碰组包逻辑上游、不碰响应层、不落盘。

| 接缝 | 内容 | 判定 |
|---|---|---|
| B' BT 出包字节 | sendRaw 未混淆、所有 WearPacket 出手表必经；字节可手术 | **可 hook（现行）** |
| C 手表端 sign 覆盖范围 | 手表端校验哪些字段被 sign 覆盖 = **黑箱**（手表固件侧不可见） | **唯一实验变量**：空 verification 是否被手表接受，只能真机实测（§Y 判据 3/4） |

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

## Y、BT wire 手术（现行方案）

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
`verification{info=license, sign, trialDuration}`。抹空 verification（原包字节中
`[tagStart, valEnd)` 段替换为 `0x2A, 0x00`——tag 保留 + 零长消息 = 全默认值）
= 手表按免费安装收货 = 试用永不过期。

安全边界：rewrite 带结构轻校验（verification 必须含 field1/field2 字符串字段），任一步
解析失败/字段不唯一 → null 放行原包，绝不改坏其它协议包；hook 回调整体 runCatching，
任何异常（含 base64 解码失败）= 原样 proceed，绝不 throw（okhttp 期真机闪退教训：
回调抛非预期异常打爆宿主线程）。

### 判据清单（用户自执行）

1. 挂载：模块日志 `MiHealthHook: installed 1/1`（单挂载点 sendRaw）。
2. 试用安装付费表盘 → 判据日志
   `mih-face: PREPARE_INSTALL verification 已抹空 faceId=<id>`（每 faceId 1 次；
   hook 就绪时另有 `mih-face: sendRaw hook 就绪` 一行）。
3. 手表安装成功且 **10 天后不还原** = 实验 C 通过（此前实证：试用期 10 天后表盘还原）。
4. 若手表拒绝安装 / 装完即还原 = 手表端校验 sign，本路线关闭，回填本文档。

## 六、运维注记

- RN dial bundle **周更动态化**：JS 侧组包逻辑随时漂移（proto 字段号 / WearPacket 封装方式），
  仍属赌注，漂移时按日志缺判据行排查。
- proto 字段号随宿主版本可能漂移：rewrite 有结构轻校验兜底（type/id 语义门 + verification
  字段存在性 + 唯一性），结构不符一律放行原包——漂移的表现 = 判据 2 缺行 + 手表试用到期还原，
  而非崩溃或其它包损坏。
