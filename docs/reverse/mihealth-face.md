# 小米运动健康 `com.mi.health` — 表盘试用永久化逆向

> 基线 **3.59.1**（真机 pull，`dev.sh pull-apk` 实证）。
> 状态：hook 已落码（`MiHealthHook.kt`），判据以真机自执行验证清单为准（本文 §五）。

## 一、目标形态

表盘商店是 **React Native 应用**（dial bundle）。业务逻辑在 JS 侧，Java 层只是容器 + 网络栈 +
BT 推送通道——业务类名随 RN bundle 周更动态化随时漂移，**业务层类不是可用锚点**；网络栈
okhttp3 未混淆、公开 API，是唯一跨版本稳定挂载面。

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

## 三、三接缝分析

| 接缝 | 内容 | 判定 |
|---|---|---|
| A 手机端 fileHash 校验 | 下载链的文件哈希校验在手机侧，客户端可绕 | **可绕** |
| B BT 组包 | `verification{info, sign, trialDuration}` 的组包在 Java 层（YRNBluetoothModule 通道），可 hook | **可 hook**——但本方案不直接动它，改上游 license 输入 |
| C 手表端 sign 覆盖范围 | 手表端校验哪些字段被 sign 覆盖 = **黑箱**（手表固件侧不可见） | **唯一实验变量**：空 verification 是否被手表接受，只能真机实测 |

**方案取向**：不碰 B 的组包逻辑，在数据源层（download 响应）把 license/sign 置空——
JS 侧走「license 空 ⇒ 免费安装」的原生路径，BT 组包自然产出空 verification。
这与 hook-craft「改数据源，不挂消费端」一致；手表端接不接受是实验 C 的结论，代码侧不假设。

## 四、免费记录样例字段（驱动 UI 按钮状态机）

免费表盘 face 记录字段样例：

| 字段 | 值 |
|---|---|
| purchaseType | 22 |
| purchaseStatus | 0 |
| price | 0 |
| status | 101 |

list/tab 免费化即把已缓存 faceId 的记录改写到这组值（price=0 / purchaseStatus=0 / onTrial=false）。

## 五、判据清单（用户自执行）

1. **缓存生成**：试用期内装一次付费表盘 → 判据日志
   `mih-face: 授权已缓存并置空 license faceId=<id>`（缓存目录
   `<dataDir>/files/honorhook_face_cache/<key>.json` 生成）。
2. **H2（UI 免费化）**：强停重进 → 已过期/试用过的表盘详情页出现「安装」按钮 → 判据日志
   `mih-face: 记录免费化 id=<id>`。
3. **H3（回放安装）**：点安装走通 → 服务端拒绝 download 时判据日志
   `mih-face: 服务端拒绝，已回放缓存 faceId=<id>`，安装完成。
4. **实验 C**：手表端表盘还原与否/是否持续可用 = sign 覆盖范围的最终结论（代码侧不假设，
   真机结果回填本文档）。

挂载判据：模块日志 `MiHealthHook: installed 1/1`（单挂载点 okhttp Builder.build）。

## 六、运维注记

- RN dial bundle **周更动态化**：接口路径 / 字段名可能随版本漂移（download 路径、
  list/tab 前缀、license/sign 字段层级——hook 侧已做 data 层与顶层双尝试、多 list 前缀并陈，
  仍属赌注，漂移时按日志缺判据行排查）。
- 挂载点 `okhttp3.OkHttpClient$Builder.build()` 是 okhttp 公开 API，稳定；okhttp 大版本升级
  （3→4→5）方法名经 @JvmName 保持兼容，风险低。
- 缓存 key 优先真实 faceId（URL query / 请求体提取），取不到用 URL MD5——后者的记录
  免费化匹配不到 list 记录 id，属降级路径。
