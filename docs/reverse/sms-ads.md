# 荣耀短信 `com.hihonor.mms` + 智能信息 `com.hihonor.intelligentmessage` — 去营销广告逆向

> 基线 **mms 19.0.9.304 / intelligentmessage 10.0.41.308**（2026-10-12 真机 `dumpsys`/`pm path` 实证，设备 AAK-AN00）。
> 反编译产物：`system-files/honor-mms-19.0.9.304-jadx/`、`system-files/intelligentmessage-10.0.41.308-jadx/`（jadx `--show-bad-code`，28/10 个方法级 error 属正常）。
> 状态：**调研期**——只有逆向实证，无任何 hook 代码。本文记录事实与候选挂载点，粒度决策见 grilling。

## 一、架构（真机实证）

| 项 | 值 |
|---|---|
| 宿主进程 | 只有 `com.hihonor.mms` 一个进程；intelligentmessage **无独立进程**，其 `base.apk` 映射进 mms 进程（`/proc/<pid>/maps` 实证）——插件式注入，hook 只打 mms 进程 |
| 插件桥接 | mms 侧 `com.smartsms.proxy.hw.HwMenuProcessorProxy` / `HwProcessorFactoryProxy` / `HwMenuEventProxy` / `com.smartsms.hwcontroller.SmartSmsProcessorFactory`——**未混淆**（跨 APK 反射留名），天然稳定锚点 |
| 引擎命名空间 | `com.ted.sms.*`（plugin/feature/framework/db/links/model），部分可读；广告管线在 `defpackage/*`（R8 混淆） |
| 菜单存储 | Room DB：`com.ted.sms.db.TedMenuDao(_Impl)`，云端下发落地本地 |
| 内嵌 SDK | `com.hihonor.dlinstall.*`（应用市场推广 SDK，日志 tag `AM_SDK`）——「查看应用」卡片的下载跳转引擎 |

## 二、三个广告面（真机 UI dump + intent 实证）

10010 会话页（`com.android.mms.ui.ComposeMessageActivity`），全部控件 resource-id 前缀 `com.hihonor.intelligentmessage`、渲染进程 `com.hihonor.mms`：

| 面 | 控件 id | 跳转 |
|---|---|---|
| 应用推广卡「查看应用」 | `left_button` | `honormarket://details?id=com.sinovatech.unicom.ui&sceneType=0204&realSource=com.hihonor.mms`（拉起 `com.hihonor.appmarket` 详情页；归因参数证明是荣耀智能短信商业化投放） |
| 流量卡按钮「1元30G」「抢100G」 | `left_button` / `right_button` | mms 内 `com.hihonor.linker.framework.StdActivity2` 容器，插件页 `com.ted.sms.feature.ui.TedWebViewActivity`（WebView H5） |
| 底部自定义菜单「流量充值」「1元30G」「百元回馈」 | `custommenu_name` | 同上链路 |

## 三、菜单渲染链（jadx 实证，核心收口点）

`com.ted.sms.plugin.TedMenuProcessor`（633 行，`IMenuProcessor` 实现，tag `TedMenu`）：

```
init(ctx, threadId, iccid, editToMenu, editRoot, menuGroup, params)
→ menu = VO.e(条件)               # TedMenuDao 本地查询
→ menuJson = findMenus(...)       # JSONArray 菜单项
→ filterMenus()                   # 引擎自带过滤先例（menuJson.remove(i)）
→ menuJson.length()==0 → return -201   # ← 空菜单 = 整个菜单不渲染，UI 干净退出
→ bindMenu() → bindMenuData(menuJson)  # inflate LinkerComposeMsgMenuItemBinding
```

**三个 surface 疑似同源**：「查看应用」的 resource-id 也是 `left_button`，推广卡大概率同样由 menuJson 驱动（`callChannelAction` 在 TedMenuProcessor 里是空实现，AM_SDK 触发点在混淆层，待二次实证）。若确认：**清空 menuJson 一个挂载点全杀**。

## 四、候选挂载点（按 repo 哲学排序，未实现）

1. **数据源**：`findMenus` 返回空数组 / `VO.e` 查询结果置空 → `init` 走 -201 退出，最接近「改数据不改逻辑」；类名可读但引擎代码随版本变，需配 DexKit 锚点（`"TedMenu"` tag 字符串、`bindMenuData menuJsonArray len:` 日志串）
2. **桥接层**：`HwMenuProcessorProxy`（mms 侧、未混淆、跨版本最稳）拦 `init` 直接返 -201
3. 点击分发拦 `honormarket://`（备选，可能误伤手动分享）
4. View 隐藏（下策）

## 五、待二次实证（grilling 后定）

- 推广卡是否真由 menuJson 渲染（写 demo hook 清空 menuJson 后真机看「查看应用」是否消失）
- 「1元30G 是广告、全杀不留白名单」已拍板（2026-10-12），但 `filterMenus` 的内置规则值得先读——引擎本来就在删某些菜单项，照抄其手法侵入最小
- 验证码复制门禁：mms 侧只有 `AutoMMSMcpService` 含「验证码」；引擎侧在 `defpackage`（C1497m5/PC/C1881sO 等 7 文件），门禁位置未定位——需读混淆层 + 真机断网测试（按钮消失 vs 报错）
- AM_SDK 触发链：`defpackage/QV、LV、TV、MV` 等引用 `DownloadInstallClient`，全混淆，DexKit 锚点 = `"honormarket"` 字符串 / `"AM_SDK"` tag / `"PageUtil"` 串
