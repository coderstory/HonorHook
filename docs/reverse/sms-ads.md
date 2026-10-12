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

## 四、挂载点（T2 已实现并真机验收，2026-10-12）

| 点 | 层 | 状态 |
|---|---|---|
| `SmartSmsProcessorFactory.getMenuProcessor`（static 0 参返 Optional，未混淆） | 载体：proceed 后从返回的 TedMenuProcessor 对象**捕获插件 ClassLoader** | **实测生效**（installed 2/2，载体命中日志） |
| `TedMenuProcessor.findMenus`（private static，经捕获的插件 CL 解析）→ 返空 JSONArray | 数据源：menuJson 空 → init -201 干净退出 | **实测生效**：底部菜单消失、正文保留、无 crash |
| `SimpleActionLayout.b(msgId, json, smsNumber, tedCard)`（插件侧，全类唯一 4 参 b） | 参数：json 置空串 → 引擎空数据路径 `setVisibility(GONE)` | **实测生效**（T3）：卡片按钮/文本动作消息全灭，双态比对无回归 |

修正（实测推翻调研期判断）：
- `HwMenuProcessorProxy` 桥接是**死代码**——`SmartSmsSwitcher.isHihonorSmartSms()` 见插件包已装即走 Honor 分支，Hw* 从不实例化（第一版载体 installed 2/2 但零触发，换载体后全链生效）
- DexKit 兜底对插件类不适用：engine 只扫 mms apk；插件类名跨 APK 反射留名，直连即中
- **卡片按钮（left_button/right_button）与 menuJson 无关**（T2 后真机 dump 仍在）——数据链待 T3 定位；「查看应用」推广卡同归 T3

## 五、待二次实证（T4 范围）

- **验证码卡 = `tedCard.code == "0EFF76FF"`**：T3 实证该卡动作区由 `SimpleActionLayout.c()` 从 `tedCard.subTitle` **本地合成**复制按钮（不经 json menus）——引擎自带本地复制路径，T4 任务 = 服务关时让它渲染/工作。T3 对该 code 放行原 json，不与 KEY_MMS_CODE_FREE 抢地盘
- 「查看应用」推广卡云端未下发无法目检，同 id 同链路（SimpleActionLayout）推断已死，卡出现时补验
- 验证码服务门禁：`SmartSmsUtilControl.getIntelligentIpMessageEnable`（T1 探针已挂，可观测当前值）；**断网场景用户指示不测**
- 卡片数据链全貌（T3 定位）：`TedMessage.action` JSON（"menus" 数组，Room DB action 列）→ `TedMsgItemProcessor.doBindCard/doBindTextAction` → `SimpleActionLayout.b` 统一收口。**不 hook getAction()**：TedMessageDao_Impl 落库直读 getter，全局改返回值会洗 DB 列 = 持久突变不可回滚
