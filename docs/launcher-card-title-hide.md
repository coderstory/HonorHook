# 系统桌面「卡片下方文本」（YOYO建议）隐藏分析

> 真机实证：AAK-AN00 / MagicOS 11.0.0 / `ro.build.magic_api_level=43` / launcher `18.0.0.234`（2026-10-10）。
> 静态基线：`YOYOCardStackPlugin.apk` + `HnLauncher6.apk`（拉取于 `.buildtmp/launcher/`）。

---

## 一、结论速览

| 问题 | 答案 |
|------|------|
| 那行文本是什么 | 卡片堆叠控件的**标题行**：`card_indicator_title_layout` → `card_indicator_title`（TextView） |
| 谁渲染它 | `com.hihonor.android.launcher.cardstack.view.HnIndicator2X4StackView`（当前屏上的时钟+天气 4x2 卡片堆叠） |
| 官方有没有开关 | **有**：桌面设置「隐藏应用名称」（pref key `launcher_hide_title`，SP `launcher_icon_title_hidden`） |
| 开关的代价 | 会**同时**隐藏桌面全部应用图标名称（支付宝/淘宝…），不能只关卡片标题 |
| 只隐藏卡片标题 | **已实现**（`LauncherCardTitleHook`：挂 `ThemeUtilImpl.isHiddenTitle()` → true，见 §八） |

---

## 二、实机定位证据

### UI 层级（`tools/dev.sh ui`）

```
launcher_root_view
└ drag_layer → workspace → workspace_screen
  └ LinearLayout [0,206][1272,882]
    └ layout (RelativeLayout)
      ├ card_stack_view_container [0,206][1272,882]
      │  └ card_stack_view (RecyclerView) [79,237][1192,762]      ← 卡片本体
      │     └ … background_view_notitle_nopadding
      │        └ hnstackview_cardview_without_title_padding
      │           └ AppWidgetHostView(content-desc="天气") …        ← 宿主小部件
      └ card_indicator_title_layout [0,762][1272,836]              ★ 标题行
        └ card_indicator_primary_title_layout
          └ card_indicator_title  text="YOYO 建议" [533,779][739,836]  ★ 目标文本
```

### 是 2X4 还是 2X2

- `card_indicator.xml`（2X4）含 `card_indicator_primary_title_layout` + `card_indicator_title_sub` + `card_indicator_primary_icon`；
- `card_indicator_2x2.xml`（2X2）**没有** primary/sub 子行，只有单个 `card_indicator_title`。

UI dump 出现 `card_indicator_primary_title_layout` ⇒ 当前实例是 **`HnIndicator2X4StackView`**。

### 类归属（关键工程约束）

`cardstack/*` 全部**只在 `YOYOCardStackPlugin.apk`**，不在 `split_yoyo.apk`：

```
updateHiddenTitleMode   split_yoyo=0   YOYOCardStackPlugin=1
StackSpUtils(=utils/z)  split_yoyo=0   YOYOCardStackPlugin=1
HnIndicatorStackView    split_yoyo=0   YOYOCardStackPlugin=6
```

而它由 `com.hihonor.android.launcher.pdk.pluginmanager.PluginManager` **用独立 ClassLoader** 装载（`PluginManager.java:174-198`）：

```java
Context splitCtx = context.createContextForSplit(str);          // str = "yoyocardstack"
ClassLoader cl = splitCtx.getClassLoader();
Class<?> cls = cl.loadClass("com.hihonor.android.launcher." + str + ".impl.PluginEntryImpl");
```

⇒ **launcher base ClassLoader 里 `loadClass("…cardstack…")` 永远拿不到**，hook 必须拿到 split ClassLoader（详见 §六）。

---

## 三、文本渲染链（谁写进去的）

```
HnCardStackView 栈顶变化 / 指示器显示
  → HnIndicator2X4StackView.updateYoyoTitle()            (:621)
    → updateTitle()                                      (:609)
      → setMultiCardTitle(c1)                            (:852)
        → setTitle(strMaster, strSub, z=true)            (:350)
          → str = String.format(R.string.yoyo_card_stack_title, cardName)
             // yoyo_card_stack_title = "YOYO Suggestions · %1$s"（zh 即「YOYO 建议 · %s」）
          → mTextView.setText(str)
```

`setTitle` 里另有 `mSubTextView`（副标题行 `card_indicator_title_sub`，默认 `visibility=gone`）。

---

## 四、隐藏判定链（总闸）

唯一判定源是 `cardstack.utils.z.d()`（`utils/z.java:39-54`）：

```java
public static boolean d() {
    if (Build.VERSION.MAGIC_SDK_INT < 43 || VersionCodes.getSdkVersion() < 17) return false;
    ILauncherService svc = n.a.a.k.getLauncherService();
    if (svc == null) return false;
    ITheme themeUtil = svc.getThemeUtil();
    return themeUtil != null && themeUtil.isHiddenTitle();
}
```

落到：

```
ThemeUtilImpl.isHiddenTitle()                      plugin/service/theme/ThemeUtilImpl.java:28
  → HiddenTitleManager.isHiddenTitle()             launcher/hidden/HiddenTitleManager.java:35
    → Settings SP "launcher_icon_title_hidden"     （-1 未加载 / 0 显示 / 1 隐藏）
```

> 本机 gate 全通：`MAGIC_SDK_INT=43 >= 43`，`VersionCodes.getSdkVersion()=27 >= 17`（硬编码返回 27）。
> ⇒ 本机 `z.d()` 恒等于 SP 的值。

### 消费点（`z.d()` 的全部 4 处调用，均为标题相关）

| 位置 | 行为 |
|------|------|
| `HnIndicator2X4StackView.java:365/367/385` | `setTitle()`：主/副标题 `setText("")`，主图标按 `h.t0()` 决定显隐 |
| `HnIndicator2X2StackView.java:507` | `setTitleVisibility(int)`：**强制 `i = 4`** → `cancelTitleAnimator()` + `mTitleLayout.setVisibility(4)` |
| `HnIndicatorStackView.java:166` | 构造器 `updateHiddenTitleMode(z.d())` → `setTitleVisibility(z ? 8 : 0)` |

### 一个不完整分支（2X4 实测口径）

`HnIndicator2X4StackView.setTitleVisibility(int)`（`:944`）只有 3 支：

```java
if (i == 1000) updateYoyoTitle();        // 刷新标题（refreshAllTitles 走这里）
else if (i != 4) a0.m(this);             // 8 落这里 → 只显示指示点/气泡，不隐藏标题
else { cancelTitleAnimatorAndHide(); … } // 4 → mTitleLayout.setVisibility(4)
```

⇒ 2X4 的 `updateHiddenTitleMode(true)`（传 8）**不会隐藏标题行**；真正让文本消失的是随后 `updateYoyoTitle → setTitle` 走空文本分支（空 TextView 高度塌陷，视觉上不留白）。
2X2 则因为 `setTitleVisibility` 内部自查 `z.d()`，能直接整行 INVISIBLE。

---

## 五、环境侧已有的官方开关（零 hook 路线）

| 入口 | 落点 |
|------|------|
| 桌面设置「隐藏应用名称」（`HomeMoreSettings.setupHideTitle`，pref key `launcher_hide_title`，文案 `R.string.magic_hide_title`） | `Workspace.refreshAllTitles(0, v)` |
| Provider call `setIconTitleHidden`（`ProviderCallUtils.java:2676`） | `Workspace.refreshAllTitles(1, v)` |
| YOYO/MCP 工具 `hideAppTitle`（`LauncherMcpService.java:176`） | `Workspace.refreshAllTitles(2, v)` |

`Workspace.refreshAllTitles(int from, boolean z)`（`:15577`）：

```java
HiddenTitleManager.setHiddeTitle(z);                 // 写 SP
… 遍历所有 CellLayout 子 View …
if (child instanceof IRefreshTitleControl) ((IRefreshTitleControl) child).refreshTitle(z);   // 图标名
if (child instanceof IStackView)           ((IStackView) child).setTitleVisibility(1000);    // 卡片堆叠
```

⇒ **同一个开关既管图标名也管卡片标题**。若接受图标名一起消失，打开设置即达成，无需 hook。

---

## 六、hook 路线（只隐藏卡片标题，保留图标名）

按依赖权重（CLAUDE.md §九-2）排序：

| 优先级 | 目标方法 | 所在 CL | 说明 |
|--------|----------|---------|------|
| **① 推荐** | `com.hihonor.android.plugin.service.theme.ThemeUtilImpl#isHiddenTitle()Z` → 恒 `true` | base（好挂） | 类名/方法名**不混淆**；全 APK 内**唯一**消费者就是插件里的 `z.d()` ⇒ 效果等价于"只隐藏卡片标题"，不动图标名 |
| ② | `com.hihonor.android.launcher.cardstack.utils.z#d()Z` → 恒 `true` | 插件 CL | 最贴近决策点，但类名/方法名是混淆短名（`z`/`d`），版本漂移风险高，且需插件 CL |
| ③ | `HnIndicator2X4StackView#setTitle(String,String,boolean)V` → 置空/短路 | 插件 CL | 类名稳定（未混淆），但属"改表现"；签名含 2 String + boolean |
| ④ | `HiddenTitleManager#isHiddenTitle()Z` → 恒 `true` | base（最易） | 但等于把官方开关打开 ⇒ 图标名一起隐藏 |
| ✖ | `updateHiddenTitleMode(boolean)` 强制 true | 插件 CL | **2X4 下无效**（见 §四） |
| ✖ | 资源 hook（改插件 `card_indicator.xml` 中 `card_indicator_title` 的 `android:visibility`） | — | 需替换插件 APK 资源，侵入过大，违反最小侵入 |

### 插件类解析姿势（必须）

因为目标是 split ClassLoader 里的类：

1. **捕获 CL**：hook `android.app.ContextImpl#createContextForSplit`（after，在 base CL 可解析）→ 取 `Context.getClassLoader()`；
   或 hook `ClassLoader#loadClass(String)` 全量拦截，按类名匹配后记录接收者 CL。
2. **在捕获到的 CL 内解析**目标类并挂载（`MethodResolver` 需接受外部 CL，而非只用 `param.getDefaultClassLoader()`）。
3. 时机：插件在桌面首次创建卡片堆叠时加载 ⇒ 必须"**类加载监听 + 重试**"，不能一次性解析。
   本项目 `MountScheduler` 的 NOT_RESOLVED 重试语义可复用，但要补 CL 维度。

### 生效即时性

`z.d()` / `ThemeUtilImpl.isHiddenTitle()` 是**读取时判断**：
- 新实例构造、`refreshAllTitles`、`updateYoyoTitle` 时都会重读 → 多数场景自动生效；
- 若已渲染的实例不刷新，可调用 `IStackView.setTitleVisibility(1000)`，或简单粗暴 `Workspace.refreshAllTitles(2, false)`（会顺带重置图标名）。

---

## 七、未实锤 / 待验证

1. 官方开关打开后的**实际视觉**（是否留白）未真机 A/B —— 依赖"空 TextView 高度塌陷"，仅代码推断。
2. 强制 `setTitleVisibility(4)` 会跳过 `a0.m(this)`（`setStackViewIndicatorColor` + `showIndicator` + `showPopupView(true)`），可能影响指示点/气泡提示 → 若走该路线需实测。
3. `ThemeUtilImpl.isHiddenTitle()` 若被其它 Honor 插件（hiBoard 等）通过 `ILauncherService.getThemeUtil()` 调用，强制 true 会外溢 —— 本 APK 内 grep 无其它消费者，跨 APK 未验证。
4. 版本漂移：`z`/`d` 为混淆短名；launcher（`18.0.0.234`）、YOYOCardStackPlugin（split `yoyocardstack:17010202`）升级后需重核。

---

## 八、实现落地与真机验证（HonorHook）

**采用路线 ①（单点，base CL，已实现）**：新目标 `LauncherCardTitleHook`
（`app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherCardTitleHook.kt`）只挂
`com.hihonor.android.plugin.service.theme.ThemeUtilImpl#isHiddenTitle()Z` → 恒 `true`。

- **为何不挂插件类**：真机探针实证 `HnIndicator2X4StackView` 在 `defaultClassLoader` / 线程 contextCL /
  Application.loader **三个 loader 上 `loadClass` 全部未命中**（LSPosed 日志 `probe: [...] => 未命中`）
  ⇒ 插件类不可经 base CL 解析；而 `ThemeUtilImpl` 在 base APK `classes3.dex`，直连反射即命中，
  **免去插件 ClassLoader 捕获/重试基建**。旧版按插件 `cardstack` 的 `setTitle` 实现真机为 `installed 0/2`（同日志），已废弃。
- **不碰 SP**：只改 ITheme 出口返回值；`HiddenTitleManager`（图标名读链，见 §五）不受影响 ⇒ 桌面图标名保留。
- **注册**：`TargetRegistry.register(LauncherCardTitleHook)`；作用域 `com.hihonor.android.launcher` 已在 `scope.list`。
  TEMP 诊断探针 `LauncherProbeHook` 已删除。`dev.sh deploy-release` 一键部署（release 427KB）。

**真机判据（AAK-AN00 / 2026-10-10 18:28）**：

```
LauncherCardTitleHook: installed 1/1
LauncherCardTitleHook 宿主版本与 hook 基线一致: 18.0.0.234
launcher-hook 生效: launcher:themeUtil.isHiddenTitle — L1 卡片标题隐藏（…）
```

视觉结果：卡片标题行文本消失、桌面应用图标名保留（用户确认「没问题了」）。

**残余影响（低）**：`com.hihonor.appmarket.topapps.impl.c`（IThemeProxy）也读 `ITheme.isHiddenTitle()`，
置 true 后其 `a()` 返回 `"0"`（= 已隐藏）—— 仅影响 appmarket topapps 的标题态上报/布局，无破坏性。

---

## 附：关键 file:line 索引

| 文件 | 行 | 内容 |
|------|----|------|
| `res/layout/card_indicator.xml`（YOYOCardStackPlugin） | 5-9 | `card_indicator_title_layout` / `card_indicator_title` |
| `HnIndicator2X4StackView.java` | 350 / 944 / 958 / 609 / 621 / 261 | `setTitle` / `setTitleVisibility` / `setupViews` / `updateTitle` / `updateYoyoTitle` / `isSupportAlwaysShowTitle` |
| `HnIndicator2X2StackView.java` | 505 / 523 | `setTitleVisibility` / `setupViews`（inflate `card_indicator_2x2`） |
| `HnIndicatorStackView.java` | 143-168 / 1185-1188 / 898 | 构造器（166 调 `updateHiddenTitleMode`）/ `updateHiddenTitleMode` / `setRootViewSize` |
| `cardstack/utils/z.java` | 39-54 | `d()` 总闸 |
| `plugin/service/theme/ThemeUtilImpl.java` | 28-30 | `isHiddenTitle()` → `HiddenTitleManager` |
| `hidden/HiddenTitleManager.java` | 11-37 | SP `launcher_icon_title_hidden` 读写 |
| `plugin/service/LauncherServiceImpl.java` | 442 | `getThemeUtil()` |
| `Workspace.java` | 15577 / 15605 | `refreshAllTitles` / `setTitleVisibility(1000)` |
| `HomeMoreSettings.java` | 319-325 / 1649-1668 | 设置项 `launcher_hide_title` / 开关回调 |
| `utils/ProviderCallUtils.java` | 2676 | `setIconTitleHidden` |
| `mcp/LauncherMcpService.java` | 176 | `hideAppTitle` |
| `pdk/pluginmanager/PluginManager.java` | 174-198 | `createContextForSplit` + 独立 CL 装载插件 |
| `YOYOCardStackPlugin/res/values/strings.xml` | 358 | `yoyo_card_stack_title = "YOYO Suggestions · %1$s"` |
