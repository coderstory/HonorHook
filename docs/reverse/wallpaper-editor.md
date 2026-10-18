# 壁纸编辑器 · `com.hihonor.wallpapereditor`

> 需求：**锁屏和桌面使用不同的视频** —— 图库给视频选「设为壁纸」时弹窗只有 [设为锁屏, 设为锁屏和桌面] 两项，
> 补出第三项「设为桌面」并把整条链路按"仅桌面"走通。
> **状态（2026-10-18）：功能已放弃，hook 代码已全部删除**——本文档保留作逆向记录与未来重启依据，先读文末 §九（写链终局 + 现行方案评价 + 放弃原因）。
> 本文只写代码与文件里读不到的东西：实证行号、锚点、判据、边界、未验证项。

## 一、身份与基线

| 项 | 值 | 实证 |
|---|---|---|
| 目标包 | `com.hihonor.wallpapereditor` | 设备 `cmd package query-activities`（`video/*` → `com.hihonor.wallpapereditor/com.android.gallery3d.app.Wallpaper`） |
| 基线 | `153.0.0.423`（versionCode 1530000423） | 真机 `dumpsys package` 2026-10-16 |
| 设备形态 | `AAK-AN00` 直板机（`ro.build.characteristics=default`，无 fold 属性） | 真机 `getprop` 2026-10-16 ⇒ `le.k.j()`（`HwFoldScreenManagerEx.isFoldable()`）为 false ⇒ 见 §五·2 |
| 图库角色 | **只发 Intent**（图库侧不是 hook 面） | `Photos-153.0.0.405-jadx`：`WallpaperUtils.java:328 jumpToVideoWallpaperPage` + `WallpaperConstant.java:13` |
| 逆向产物 | `system-files/WallpaperEditor-153.0.0.423-jadx/`（阅读用）、`system-files/WallpaperEditorManifest-apktool/`（**判据用**）、`.buildtmp/fw/wss/sources/com/hihonor/android/app/WallpaperSetSession.java`（框架单类） | apktool 产物不完整（10066 个 smali，缺 `w6/d.smali` 等），但**本目标宿主侧四点的类都在**——`h8.smali`/`Wallpaper.smali` 已逐行核对；`be/o1.smali`/`y6.smali`/`w6/i.smali` 需用本轮全量产物（见下条） |
| ⚠ 现有 apktool 产物**缺 `com/hihonor/theme/**`**（连 `ThemeEditorViewModel.smali` 都没有，`be/o1.smali` 有） | 2026-10-16 复现：`tools/dev.sh decompile` 因 framework 资源缺失**中途退出**（`Could not find framework resources for package of id: 2`，exit=1）⇒ smali 只落了一部分 | 读 `V()`/`D0` 这类 jadx 解不出的方法时必须**重新全量 baksmali**。**正确姿势 = `-r`（只解 smali、不解资源）**：`JAVA_HOME=… java -jar tools/apktool.jar d -f -r <apk> -o /tmp/x` —— 绕开 framework 资源报错，一次拿全 12059 个 smali（不加 `-r` 只落 10171 个，仍缺 `com/hihonor/theme/**`；加 `-s` 则是"不解 smali"，反而一个都不出）。本轮全部结论基于该全量产物 |

## 二、链路与证据

| 段 | 位置 | 事实 |
|---|---|---|
| 弹窗 | `WallpaperDialogActivity.java:34 a(Intent)` / `:58` | `setItems(zM \|\| z3 ? R.array.wallpaper_setas_option_for_video : R.array.wallpaper_setas_option, listener)`；`z3` = intent type 含 video（:56），`zM` = `t7/r.java:283` 的 `spatial_resource` 数组非空 |
| 文案 | `resources/res/values-zh-rCN/arrays.xml` | 视频数组 = [设为锁屏, 设为锁屏和桌面]（两项）；图片数组 = [设为锁屏, 设为桌面, 同时设置] |
| 点击 | `WallpaperDialogActivity.java:66-69` | video 分支 → `d8.k.b().c(合成 lambda r)`（视频有效性校验）→ 回调 `com/facebook/imagepipeline/producers/r.java:191 b(int)` → `Wallpaper.j(ctx, intent, i10)`，`i10 = i9==1 ? 2 : i9` |
| mode 语义 | `Wallpaper.java:131 j(Context,Intent,int)` | `0` → `set_as=intent_set_lockscreen` / `show_preview=lockscreen`；`1` → `intent_set_desktop` / `desktop`；`2` → `intent_set_both`。**video 分支只产出 0 或 2** ⇒ 仅桌面必须在此改写 |
| mode 1 是宿主既有路径 | `Wallpaper.java:1044-1048` | `type_res_directly_set_desktop` 直接调 `j(ctx, intent, 1)`；`:161-163` 还专门把 `live_photo_wallpaper` 转成 `others_wallpaper` ⇒ 动态壁纸走 mode 1 是宿主既有能力，非本模块臆造 |
| 应用层 | `h8.java:39 a(...)` / `:113 b(...)` / `:131 c(...)` / `:165 d(...)` | `a(session, screenId, which, type, uri)` 是「哪一侧」的唯一下沉点（`which` 直通框架）；`b` = 折叠屏 expand 屏、`c` = 主屏；`d` = 动态壁纸属性构造器 |
| which 语义 | `h8.smali`（`a → d(i10,...)`）+ 框架 `WallpaperSetSession.java` | `1` = 桌面、`2` = 锁屏、`3` = 两侧。框架 4 参 `addWallpaperWithComponent` 把 `which` 直通 `setWallpaperAttribute`，另存 `HnWallpaperSetBean(cn, which)`；5 参重载才自己拼 `whichFlags`（system→`\|=1`、lock→`\|=2`）⇒ **which=1（仅桌面）合法** |
| 宿主写死"动态不支持仅桌面" | `h8.java:166 d(...)` | `3==i9 ? (type,type,type,type,uri) : i9==2 ? (type,null,type,null,uri) : (null,null,null,null,null)` —— which==1 全 null ⇒ 即便走到桌面分支也拿不到属性 |
| 属性类 | `data/WallpaperAttribute.java:27-34` | ctor 序 = `lockType, desktopType, lockName, desktopName, uri`；`@SerializedName` 键 LockscreenType/DesktopType/LockscreenWallpaperName/DesktopWallpaperName/SelectImageUri |
| 引擎侧 | `LivePaper-11.0.0.252-jadx`（`com.hihonor.livewallpaper.paradise`） | 动态壁纸走 `VideoWallpaperService`；用户已确认"选同时设置时桌面就是视频"⇒ 渲染链通，缺的只是选项与属性填充 |

### 二·2、「仅桌面」被谁劫持：`h8.c` 的短路

`h8.java:150-157`（等价 `h8.b:125-127`）：

```java
if (Objects.equals(桌面type, 锁屏type) && (六种动态类型之一))
    return a(session, iN, 3, 桌面type, null);            // ← 同一段视频设两边
return a(session, iN, 1, 桌面type, 桌面uri) & a(session, iN, 2, 锁屏type, 锁屏uri);
```

本需求的前提**恰好命中**该条件：用户锁屏本来就有视频 A ⇒ 桌面 type（新视频 B）与锁屏 type（视频 A）都是
`custom_video_wallpaper` ⇒ 短路成立。而短路用的 uri 是 `h8.a` 内按 `which=3` 从**锁屏侧**取出的
`selectImageUri`（`h8.java:53-55`）⇒ 若不干预，桌面会被设成**视频 A**、锁屏同时被动过。

### 二·3、`g`（isOnlyDesktopChanged）：不用模块侧标记的门

`w6/d` 的 `g` 字段 = `!锁屏变更 && 桌面变更`：

- 赋值：`be/n1.smali:791 iput-boolean v7, v6, Lw6/d;->g:Z`（上文 769-790 行即 `!first && second`）
- 语义：`be/o1.java:6315` 用同一 Pair 判定 `both/unlock/home/none`，`first`=解锁、`second`=桌面
- 消费：`f8.smali`、`h5.smali`（avatar 分支"only desktop changed"）

⇒ 宿主自算、无需模块侧跨"点击→校验→编辑→转码→保存"维护标记，且**非"仅桌面"场景恒为 false**（天然零干预）。

> **第二轮更新**：路线 B（§二·7）不再依赖 `g` 作主门——`which` 改写要覆盖『落盘』与『应用』两段，宿主没有跨这两段的持久状态可用（`g` 只在应用层），故改用模块侧 `DesktopSlotSession` 窗口。`g` 仍是 **W4 窗口外分支**的门（保留原语义：绕开『两边同一段视频』短路）。

### 二·4、根因（2026-10-16 第一轮真机失败）：新媒体进不了桌面卡 —— 缺 `desktop_wallpaper_path`

> ⚠ **本节已被 §二·6 取代（保留作历史）**：它只解决了『预览不显示新视频』，没有解决『桌面被当成静态图』——后者是 §二·6 的宿主模型问题。本节提到的 `desktop_wallpaper_path` 补写代码已随路线 B 删除（W2 改走 mode 0，由宿主自己的 `g(ctx,intent,true)` 写桌面 extras）。

**症状**（真机 AAK-AN00，153.0.0.423）：`installed 4/4`、W1/W2 命中，但 **W3/W4 从未触发**；
选了「设为桌面」后预览页显示的不是刚选的视频（是旧锁屏/当前壁纸），点「应用」无任何效果。

**证据链**（全部为反编译产物 file:line，jadx 侧 `WallpaperEditor-153.0.0.423-jadx/`；
`ThemeEditorViewModel.V` 与 `be/o1.D0` 是 jadx 解不出的方法，改用 baksmali 逐指令核对）：

1. **编辑器读桌面新媒体的唯一入口 = `desktop_wallpaper_path`。**
   桌面卡 `ad/g.java`（`HomeEditFragment`）`onViewCreated` 取
   `this.O = themeEditorViewModel.F`（桌面 type）、**`this.P = themeEditorViewModel.I`（桌面 uri）**、
   `this.f228c0 = themeEditorViewModel.C`（set_as）（`ad/g.java:862-864`）；
   `P0()`（`ad/g.java:320-368`）的 `case "others_wallpaper"` 里：
   ```java
   if ("intent_set_desktop".equals(this.f228c0)) {
       h0().getDesktopInfo().setSelectImageUri(this.P);   // ad/g.java:356，日志 "updateRecordDesktopUri"
   }
   ```
   而 `ThemeEditorViewModel.I` 来自 extra `desktop_wallpaper_path`（默认字符串 `"null"`），
   `F` 来自 `desktop_wallpaper_type`（默认 `"others_wallpaper"`）——见 `V()` 的 baksmali
   （`smali/com/hihonor/theme/feature/base/viewmodel/ThemeEditorViewModel.smali`，`V` 方法内
   `iput-object ... ->I` 紧邻 `const-string "desktop_wallpaper_path"`）。
   **全编辑器只有这一处给 `DesktopInfo` 写 `selectImageUri`**（`grep setSelectImageUri` 全命中点：
   `ad/g.java:356` 是唯一桌面侧）。

2. **应用层 which==1（仅桌面）的 uri 也取自同一个字段。**
   `h8.a(session, screenId, which, type, name)`：`which` 的第 2 位为 0 时取
   `record.getDesktopInfo().getSelectImageUri()`（`h8.java:47-54`）。
   ⇒ 桌面卡的 `setSelectImageUri` 是"仅桌面"媒体链的唯一上游。

3. **宿主 `Wallpaper.j` 的 mode 1 在非折叠机上不写这个 extra。**
   `Wallpaper.java:159-185`：`i9==1` → `g(context,intent,false)` 只写**保持不变的那一侧**
   （`lockscreen_wallpaper_type` / `lockscreen_wallpaper_path`，`Wallpaper.java:126-129`）；
   唯一写 `desktop_wallpaper_path` 的分支包在 `if (wallpaperIntentParamsFromIntentObject != null)` 里，
   而该对象 = `k.j() ? fromIntentObject(intent) : null`（`Wallpaper.java:133`）⇒ **折叠屏才有**。
   本机 `le.k.j()` = `HwFoldScreenManagerEx.isFoldable()` = false（AAK-AN00 直板）⇒ 该 extra 缺失。
   新媒体此时只存在于 intent 的 data uri（mode 1 不改 data）。

4. **缺失 → 字面量 `"null"` 被写进 record。** `I` 的默认值是字符串 `"null"`
   （`dVar.h("desktop_wallpaper_path", "null")`），于是
   `record.getDesktopInfo().setSelectImageUri("null")`；随后 `x0()` →
   `HomeEditViewModel.x(ctx, P)` 里 `q8.j0(ctx, Uri.parse("null"))` 取不到 MediaItem，
   日志 `loadBitmap: desktopItem is null` 直接返回 ⇒ **预览保持旧壁纸**。

5. **桌面侧因此被判"未变更" ⇒ 应用层收口点根本不被调用。**
   保存时 `be/c.java:74` 用 `r7.f.v().J0()` 比对**内存 record 与磁盘存档**
   （`r7.f.J()`：`!record.getDesktopInfo().equals(local.getDesktopInfo())`，`r7/f.java` 的 `J` 方法），
   桌面 uri 没变 ⇒ `f3355d1.second == false` ⇒ `be/n1.SaveOutput` 里
   `if (f3355d1.second) o1.D0(dVar, y6Var, ...)` 不执行（`be/n1.java:311-314`）
   ⇒ `w6.d.f18837c`（桌面 type）保持 null ⇒ `h8.c` / `h8.d` 一次都不被调用。
   **这同时解释了 W3/W4 从未触发**——不是选点错，是桌面侧压根没进入应用流程。

6. **对照 mode 2（同时设置，真机可用）为什么不缺这一步**：`h8.a` 的 `which==3` 取
   `record.getLockScreenInfo().getSelectImageUri()`（`h8.java:47-54` 的 `(i10 & 2) > 0` 分支），
   即"两侧"时 uri 从**锁屏**侧取，而锁屏侧的 uri 由锁屏链路写好了 ⇒ 天然绕开了这个缺口。

**结论：(A) —— 只是 extra 没喂对，最小修法在 W2。**
在改写 mode 的同时把 intent 的 data uri 也写进 `desktop_wallpaper_path`，
并显式写 `desktop_wallpaper_type = "others_wallpaper"`：
- `"others_wallpaper"` 是宿主自己的通用值，也是 `ad/g.P0()` 里**唯一**会回填 uri 的分支；
- 宿主对 mode 1 也做同款归一化（`live_photo_wallpaper` → `others_wallpaper`，`Wallpaper.java:161-163`）；
- 宿主自己的"带新媒体进编辑器"入口同样成对写这两个 extra
  （`pd/e.java:620` 锁屏风格选择器、`yd/a.java:150-160` 历史记录、`Wallpaper.java:505-511` 历史），
  这就是这个 intent 协议里"新媒体"的标准携带方式。

**残留前置条件（未验证）**：桌面侧要拿到**动态**类型，`be/o1.D0` 要求
`y6.c = a.A(recordL) = ("group_image".equals(record.getDesktopInfo().getOptionType()))` 为真
（baksmali 实证：`smali/be/o1.smali` 的 `D0`，每个动态分支前都有 `iget-boolean ... Ly6;->c:Z` +
`if-eqz` 跳过）。即"桌面跟随锁屏"时才会算成 `custom_video_wallpaper`；
不跟随则落到 `image_wallpaper`（静态帧）或 `system_dynamic_wallpaper`（`h8.e` 判为 no-op）。
本需求前提（锁屏已有视频）与 W4 的短路前提**本来就要求该条件成立**（两侧 type 都等于
`custom_video_wallpaper` 才会短路），故按同一前提推进；真机若仍不触发，见 §七·5 的分诊。

### 二·5、`W1` 弹窗补项的两条既有路径不受影响

`Wallpaper.j` 的 mode 0/2 分支与 `Wallpaper.j` 的 `g()` 调用完全没被改（W2 只在
"本模块新第三项被点过 + mode==2 + video + 非 spatial" 时改写 mode）⇒
图片路径、视频「设为锁屏」「设为锁屏和桌面」三条回归基线零变化（详见 §五·4 的逐条论证）。

### 二·6、根因（2026-10-16 第二轮真机）：宿主的"动态桌面"≡"桌面跟随锁屏"

第二轮真机（`installed 4/4`，W1/W2 命中，W3/W4 判据行不出现）：
- `dumpsys wallpaper`：桌面（System）`mWhich=1 mBindSource=SET_STATIC` + `ImageWallpaper`；锁屏（Lock）`mWhich=2 mBindSource=SET_LIVE` + `paradise/VideoWallpaperService`。
- 肉眼：预览已显示新视频（§二·4 的修复生效），但裁剪时间轴里是**旧的锁屏视频**，应用后桌面 = **所选视频的第一帧静态图**。

**证据链（全部 file:line；jadx 侧 `WallpaperEditor-153.0.0.423-jadx/`，smali 侧本轮全量 baksmali 产物）**：

1. **桌面侧的 apply type 只有一个计算点：`be/o1.D0(w6.d, y6, String)`**（`be/o1.smali:3186`）。
   它按 `y6` 的位写 `dVar.c`（= `w6.d#c` = 桌面 type）；`custom_video_wallpaper` 要求
   `y6.h && y6.c`（`be/o1.smali:3225-3235`）。
2. **`y6.c` = "桌面跟随锁屏"**：`y6` 构造器（`com/hihonor/gallery/wallpaper/y6.smali`）
   `iput-boolean … ->c:Z` 取自 `com.android.gallery3d.data.utils.a.A(recordL)` = `r7.f.Z(recordL)`
   = `"group_image".equals(recordL.getDesktopInfo().getOptionType())`（`r7/f.java:683-689`，方法名即 `isFollowLockscreen`），
   `recordL = r7.f.v().l()`（主记录）。`y6.h` 同理 = `a.u(recordL)` = `r7.f.S(recordL)`
   = `recordL.getLockScreenInfo().getVideoInfo() != null`（`r7/f.java:650-656`）。
3. **`y6.c` 为假 ⇒ `D0` 落 `image_wallpaper`**（`be/o1.smali` 的 `:cond_a` 收尾分支）
   ⇒ `h8.c` → `h8.a(session, iN, 1, "image_wallpaper", desktopName)` → `addWallpaper(screenId,1,json)`（静态）。
   `h8.d`（W3）**只被 h8.a 的动态分支调用**（`h8.java:68/81`）⇒ W3 永不触发；
   W4 的门要求两侧 type 相等且属动态类型（`h8.java:150`）⇒ 也永不触发。**这解释了全部三条现象。**
4. **`group_image` 在编辑器里只有一个写入点：`be/o1.s3`（"setDesktopFollowLockScreen"，`be/o1.java:6582`）**：
   ```java
   if (this.P0.equals("intent_set_both") || a.v(record, "group_image")) {   // P0 = set_as
       this.f3389p.l0("group_image");
       … record.getDesktopInfo().setOptionType("group_image"); …
   }
   ```
   它的调用者是**锁屏卡**的初始化链（`be/c0` Runnable / `be/f1` Handler → `o1.s3`）。而
   `Wallpaper.j` 只在 `i9==2`（同时设置）时保持 `show_preview="lockscreen"`（`Wallpaper.java:131-192`，
   `str2` 只在 `i9==1` 分支被改成 `"desktop"`）⇒ **只有"同时设置"会展示锁屏卡 ⇒ `s3` 只在那条路上跑**。
   「设为桌面」的 `set_as = intent_set_desktop`、`show_preview = desktop`，两个条件都不满足 ⇒ `y6.c == false`。
5. **对照"同时设置"为什么能用**：`set_as = intent_set_both` ⇒ `s3` 成立 ⇒ `y6.c = true`，
   加上锁屏卡初始化时 `D4` 写了 `VideoInfo`（`y6.h = true`）⇒ 桌面 type 也是 `custom_video_wallpaper`
   ⇒ 两侧 type 相等 ⇒ `h8.c` 走短路 `a(session, iN, 3, type, null)`，`h8.a` 按 `(i10 & 2) > 0` 取**锁屏** uri
   ⇒ 两侧同一段视频。**即：宿主的"动态桌面"与"桌面跟随锁屏"是同一个谓词。**

**⇒ 宿主模型结论（本目标最重要的前提）**：

- **跟随时**：桌面视频 = 锁屏视频（`h8.a` 的 uri 取自锁屏记录，`h8.java:51,54`）；
- **不跟随时**：`y6.c == false` ⇒ 桌面只能落 `image_wallpaper` / `system_dynamic_wallpaper`；
- 桌面的 live-paper 配置（`live_paper_config_main_home`，**which=1**）只在 `y6.h && y6.c` 时写入
  （`be/o1.a0`，`be/o1.java:3599-3605`）；视频文件只在**锁屏侧变更**时写入（`be/o1.e0`→`l0`，
  `be/n1.java:307-314` 按 `d1.first`）；
- 宿主文件表里**根本没有 which=1 的视频项**：`video_live_wallpaper.mp4` / `unlock_video.mp4` /
  `unlock_wallpaper_video.mp4` 全是 `f(2,1,2,…)`（`w6/i.java:259,267,279`，`f18866c=2`）。

⇒ **宿主不存在"桌面独立视频源"这个概念**，路线 A（把桌面做成一等动态源）在记录模型上不可表达。

### 二·7、选定路线：借道锁屏管线，会话层把 `which` 2→1（路线 B）

不试图造"独立动态桌面"，而是让宿主用它**完整可用**的锁屏视频管线准备用户选的视频，再把产物搬到桌面槽：

| 步骤 | 位置 | 动作 |
|---|---|---|
| ① 改写 mode | **W2** `Wallpaper.j` | 「设为桌面」的 mode 2 → **0（设为锁屏）**（不是 1）。宿主于是把用户选的视频当锁屏视频完整准备（预览/裁剪时间轴/转码/落盘/属性）。mode 0 的 `g(ctx,intent,true)`（`Wallpaper.java:136`）自带把**当前桌面**写回 `desktop_wallpaper_type/path` ⇒ 桌面侧天然"未变更" |
| ② 属性形状 | **W3** `h8.d` | 会话窗口内 `which==2` 也产出**桌面侧形状**（`WallpaperAttribute(null,type,null,type,uri)`）。必须在这里：`h8.a` 已按 which=2 从**锁屏记录**取到正确 uri（`h8.java:51`），`which` 要到更下游的 `addWallpaperWithComponent` 才被改成 1 ⇒ 此处是"uri 已取对、形状还没换"的唯一插入点 |
| ③ 桌面落哨兵 | **W4** `h8.c` | 会话窗口内把桌面 type 置 `skip_wallpaper` ⇒ `h8.e` 直接返回 true、桌面侧零写入；于是 `h8.c` 只剩锁屏侧那一次 `a(iN,2,…)` |
| ④ 落盘槽 | **W5/W6** `WallpaperSetSession#writeWallpaperFile` / `#writeWallpaperBitmap` | 会话窗口内 `which` 2→1 ⇒ 视频文件/live-paper 配置落到**桌面槽**（落盘路径由 `getWallpaperFileTempPath(…, which)` 决定；`z7` 的 `i20 = (f18866c & 2) != 0 ? 2 : 1`） |
| ⑤ 应用槽 | **W7/W8** `WallpaperSetSession#addWallpaperWithComponent` / `#addWallpaper` | 会话窗口内 `which` 2→1。框架把 4 参重载的 `which` 直通 `setWallpaperAttribute` 与 `HnWallpaperSetBean(cn, which)`（`WallpaperSetSession.java:72-84`） |

**自洽性（静态证据）**：框架与引擎**全部按 `which` 取件**——
引擎侧 `com.hihonor.wallpaper.base.n.a()` 用 `HnWallpaperSetBean.which` 读 live-paper 配置
（`which==3` 时先试 1 再试 2；`LivePaper-11.0.0.252-jadx/sources/com/hihonor/wallpaper/base/n.java:46-58`），
配置 URI 由 `s2.a.z(screenId, orientation, which, "livepaper_config")` 给出
⇒ `which=1` 的应用会让引擎去读**桌面槽**的配置与视频。落盘与取件用同一个 `which`，不存在错位。

**桌面槽的两件必备产物，mode 0 都会产出（且都被 W5/W6 改到 which=1）**：
- **视频文件**：`be/o1.l0`（`be/o1.java:4884`）在 `a.u(record)`（锁屏有 VideoInfo）时写入
  `w6.i.t(d8.m.d(record))`（= `unlock_video.mp4`，`f18866c=2`）+ `new File(d8.m.e(record))`；
  它的唯一调用者 `be/o1.e0`（`be/o1.java:4224`）由 `be/n1` 在 **`d1.first`（锁屏侧变更）** 时触发
  （`be/n1.java:307-310`）—— mode 0 正是『锁屏变更』⇒ 必然产出。
- **live-paper 配置**：同一个 `l0` 里 `this.P.put(w6.i.t("live_paper_config_main_lock"), …)`
  （which=2，tag `livepaper_config`，文件 `livepaper_config.xml`）⇒ 改写成 which=1 后正好落在
  引擎为桌面读的路径（`live_paper_config_main_home` 与它**同 tag、同文件名**，只差 which，
  `w6/i.java:262-263` 两行对照）。
  （`be/o1.a0` 里那份 `live_paper_config_main_home` 是宿主『跟随锁屏』时另写的一份，本路线用不到。）

**为什么不需要改 `writeWallpaperExtraFile`**：那是 `w6.f#f18869g=true` 的"附属文件"（记录 JSON
`galley_lock_wallpaper_config`、裁剪图、origin 图），宿主自己的 mode 0 流程也写 `which=2`；它们不参与
`setWallpaperAttribute` / `HnWallpaperSetBean`，单写它们不改任何一侧的壁纸 ⇒ 保持宿主原样最稳
（少一个改动面）。视频文件（`unlock_video.mp4`）与 live-paper 配置（`livepaper_config`）都是 `false` ⇒ 走 W5/W6，已覆盖。

**为什么 W7 不需要动属性 JSON、W8 需要**：W7 的属性由 `h8.a` → `h8.d` 构造，形状已被 W3 换成桌面侧；
W8（静态图分支）的属性 JSON 由 `h8.a` 按**当时的** `i10` 内联构造（which=2 ⇒ 锁屏形状），
故 W8 内额外把 `Lockscreen*` 键镜像到 `Desktop*`（`WallpaperAttribute` 的 `@SerializedName` 键：
`LockscreenType`/`DesktopType`/`LockscreenWallpaperName`/`DesktopWallpaperName`/`SelectImageUri`，
`data/WallpaperAttribute.java:8-25`）。W8 在本路线下正常不会命中（桌面侧已被 W4 落哨兵），保留它是为了
"会话窗口内任何 which=2 的应用都落桌面槽"这一不变量成立。

**会话窗口（模块侧标记，必须自持）**：`which` 改写要覆盖**两段**跨线程调用——编辑器落盘（`z7` 的任务）
与应用（`h8.c`→`h8.a`→`addWallpaper*`），中间隔着裁剪/转码，宿主自身没有可用的"这是仅桌面请求"持久状态
（`w6.d#g` 只在应用层，且我们已改用哨兵）。故 `DesktopSlotSession`：W2 命中即 arm、**每次** `Wallpaper.j`
调用都会重置（未命中即 disarm）、W4 收口后立即 disarm（一次保存只消费一次）、兜底 15 分钟超时
（防编辑器被强杀/折叠屏走 `h8.b` 时窗口悬空）。

**已知副作用**：mode 0 的 `show_preview = "lockscreen"` ⇒ 选「设为桌面」后编辑器展示的是**锁屏卡**
（预览/裁剪时间轴里是用户刚选的视频）。属本路线固有代价，见 §七·4。

### 二·8、为什么不用路线 A（记录，避免后人重走）

路线 A = 在 apply 层直接把桌面 type 强制成视频类型。已评估并放弃：

1. **单改 `D0`/`h8.c` 不够**：桌面拿到 `VideoWallpaperService` + which=1，但
   - 桌面槽**没有视频文件**（宿主文件表里无 which=1 的视频项，且 `l0` 只在锁屏侧变更时跑）；
   - 桌面槽**没有 live-paper 配置**（`a0` 的门是 `y6.h && y6.c`）；
   ⇒ 结果是"绑了视频服务但无内容"，比现在的静态首帧更糟。
2. **让桌面"跟随锁屏"也不行**：`y6.c` 为真时 `h8.a` 会去取**锁屏** uri（同一谓词），桌面视频只能是锁屏那段。
3. 要把 1+2 都补上，需要同时覆盖 `D0`、`a0`、`l0`/`e0` 与文件表——其中 `a0`/`D0` 是**弱锚点**
   （纯分支赋值、无独有字符串），违反本项目"弱锚点不合格"的纪律；改动面也远超路线 B。
4. 路线 A 的"独立视频源"与宿主模型（桌面视频 ≡ 锁屏视频）直接冲突 ⇒ 即使实现出来，
   下一版宿主任何一处 `y6`/`h8.a` 重构都会静默退化。

**退路（若路线 B 真机仍不通）**：降级为"桌面视频 = 锁屏视频的裁剪结果"——
即只保留 W1（弹窗三项）+ W2 改写 mode 2→0、不启用 W3–W8 的改槽，用户选「设为桌面」时把新视频设到锁屏；
或回退到"同时设置"（两侧同一段视频）。二者都不满足"两侧不同视频"，但是宿主模型内的诚实能力边界。


## 三、挂载点与锚点（W1–W8）

| id | 目标（设备包 smali 实证） | 直连锚点 | DexKit 内容锚点 | 判据 |
|---|---|---|---|---|
| `wallpaper:dialog.setItems`（W1） | `android.app.AlertDialog$Builder#setItems`（**框架方法，无混淆**，且不在宿主 dex 内 ⇒ DexKit 无意义） | `paramTypes=[CharSequence[], DialogInterface$OnClickListener]`（同名的 `(int, listener)` 重载必须靠它消歧） | — | `wallpaper-hook 生效: wallpaper:dialog.setItems` |
| `wallpaper:Wallpaper.j`（W2） | `com.android.gallery3d.app.Wallpaper#j(Context,Intent,int)` static void（类名 manifest 保证） | 同名 + `paramCount=3` + `returnType=void` + paramTypes | `usingStrings("intent_set_both")`（该包内唯一，`Wallpaper.java:192`）+ paramTypes | `…mode 2 改写为 0（借道锁屏管线）` |
| `wallpaper:h8.d`（W3） | `com.hihonor.gallery.wallpaper.h8#d(int,String,String)` static → `WallpaperAttribute` | 同名 + arity + 返回类型名 | `classStrings("WallpaperSaveUtils")`（h8 类常量，全 APK 唯一，`h8.java:37`）+ `paramTypes=[int,String,String]`；**不设返回类型过滤**（该类名会随重构变） | `…桌面侧属性补齐（which=1 / 窗口内 which=2）` |
| `wallpaper:h8.c`（W4） | `com.hihonor.gallery.wallpaper.h8#c(Context,WallpaperSetSession,w6/d,boolean)` static boolean | 同名 + `paramCount=4` + `returnType=boolean`（h8 内 name+arity+返回类型已唯一，故**不加**含 `w6/d` 的 paramTypes——只多一个失败面） | `usingStrings("has_ever_apply_lockscreen_wallpaper")`（全 APK 唯一，仅 h8.c）+ `classStrings("WallpaperSaveUtils")` | 窗口内：`…桌面侧落 skip_wallpaper`；窗口外：`…绕开『两边同一段视频』短路` |
| `wallpaper:wss.writeFile`（W5） | `com.hihonor.android.app.WallpaperSetSession#writeWallpaperFile(int,int,int,String,InputStream)` int（**框架类、不在宿主 dex 内、无混淆**） | 同名 + arity + `returnType=int` + paramTypes（全框架类型名） | —（DexKit 只扫宿主 APK，对本类无意义；`resolvePoint` 对该类直连失败即诚实 `NOT_RESOLVED`） | `…落盘槽改写 which 2→1（writeWallpaperFile）` |
| `wallpaper:wss.writeBitmap`（W6） | `…WallpaperSetSession#writeWallpaperBitmap(int,int,int,String,Bitmap$CompressFormat,Bitmap)` int | 同名 + arity + `returnType=int` + paramTypes | — | `…落盘槽改写 which 2→1（writeWallpaperBitmap）` |
| `wallpaper:wss.addComponent`（W7） | `…WallpaperSetSession#addWallpaperWithComponent(ComponentName,int,int,String)` boolean（4 参重载，`which` 直通框架） | 同名 + arity + `returnType=boolean` + paramTypes | — | `…动态壁纸应用槽改写 which 2→1（VideoWallpaperService）` |
| `wallpaper:wss.addStatic`（W8） | `…WallpaperSetSession#addWallpaper(int,int,String)` boolean | 同名 + arity + `returnType=boolean` + paramTypes | — | `…静态壁纸应用槽改写 which 2→1（含属性 JSON 改键）` |

**W5–W8 的锚点强度说明**：这四点是**框架类**（`com.hihonor.android.app`，宿主 dex 之外、无 R8 混淆），
方法名跨版本稳定 ⇒ "类名 + 方法名 + 参数个数 + 返回类型 + 参数类型序列"已是强锚点，
配 `dex = null`（DexKit 对非宿主类无意义）。项目"弱锚点（仅参数个数）不合格"的纪律针对的是
**混淆宿主类**（类名/方法名随版本重映射），不适用此处。另注意 5 参重载
`addWallpaperWithComponent(ComponentName,int,int,SparseArray,SparseArray)` 存在 ⇒ paramTypes 是消歧必需。

## 四、混淆性质（实测，与"想当然"不同）

1. **jadx 会把短字段名改写成 `f<hash><字母>`，运行时名字是字母本身。** `w6/d.java` 打印
   `f18840g`／`f18836b`／`f18837c`，而 `h8.smali` 里的真实访问是 `Lw6/d;->g:Z` / `->b:Ljava/lang/String;` /
   `->c:Ljava/lang/String;`。**以 smali 为准**，否则 `getField("f18840g")` 必然 NoSuchField → 门恒 false（静默无效）。
   本次用到的字段映射：`b`=锁屏 type、`c`=桌面 type、`g`=isOnlyDesktopChanged、`d`/`e`=锁屏/桌面 expand type、
   `o`/`p`=锁屏/桌面 wallpaperName。
2. **本 app 的类名/方法名是源码原名**（不是 R8 产物）：`com/hihonor/gallery/wallpaper/h8.smali`、
   `Lw6/d;`、`Wallpaper.j`、`h8.d(...)Lcom/hihonor/gallery/wallpaper/data/WallpaperAttribute;` 都是 dex 里的原样名字。
   但**没有任何 keep 保障** ⇒ 宿主重构（加一个类、换个方法号）就会漂移，故仍走"直连 + DexKit 兜底"两条轨。
3. **结构缺口：共享的 DexKit 兜底对静态方法整体失效。** `DexKitHelper.findMethodByFeatures` 硬过滤
   `Modifier.isStatic`（`xposed/core/DexKitHelper.kt:140`），而本目标 W2/W3/W4 三点全是静态方法 ⇒ 走
   `MethodResolver` 的兜底线等于不兜底。**处置**：在目标文件内自带静态版查找
   （`resolveStaticByFeatures`，锚点维度与共享实现逐条一致，只保留 static 命中、不按方法名过滤），
   **不动共享核心**（改它会波及所有目标的兜底命中面）。⇒ 待决：是否把静态放行下沉进 `DexKitHelper`
   （一行过滤 + `MethodResolver` 的 `firstOrNull` 语义要复核），见 §七 结论建议。
4. `h8.d` 无方法体字符串（纯 new 语句）⇒ 它的锚点是"所在类字符串 + 参数类型序列"，弱于 W2/W4 的独有日志串。

## 五、边界与已知偏差

1. **spatial 弹窗会多出第三项，但选了等同"同时设置"。** `WallpaperDialogActivity.java:58` 里 video 与
   spatial 共用同一个两项目数组，`setItems` 处无法区分二者（区分信息只在宿主局部变量 `zM`）。W2 因此显式
   排除 `spatial_resource` 非空的 intent（`t7/r.java:283` 同款判据）：spatial 单独桌面无任何实证，不冒险改写
   mode。⇒ 需求三张样图（连笔签名/盾徽+字母/点状花卉）都不涉 spatial，实际影响面极小；若要覆盖，需先取
   spatial 单独桌面的实证（Unity 引擎侧）。
2. **折叠屏 expand 屏门（`h8.b`）未挂。** `h8.f:484-486` 走 `le.k.j()`（折叠屏）分支时才会调 `h8.b`；
   本机 `AAK-AN00` 直板 ⇒ `h8.f:511` 只调 `h8.c`，挂 `h8.b` 就是**证实永不触发的死挂载**（项目纪律：随需求一起删）。
   若日后出现折叠屏宿主，补一行即可：`h8.b` 的桌面/锁屏 expand 字段是 `e`/`d`，短路判断与 `h8.c` 同形，
   改写点同为"proceed 期间把 `d` 置 `skip_wallpaper`"。
3. **未动 `h8.f` 的 flowing_light 专分支**（`h8.f:454`）：`flowing_light_theme` 不在 W4 的
   `VIDEO_DYNAMIC_TYPES` 里，该分支行为与宿主原样一致（不扩面）。
4. **回归基线为什么不被波及（逐条论证；三条基线 = 图片「设为壁纸」三项 / 视频「设为锁屏」/ 视频「设为锁屏和桌面」）**：
   本模块所有改写都以 `DesktopOnlyRequest`（W1 的第三项被点过）与 `DesktopSlotSession`
   （W2 在"标记 + mode==2 + video + 非 spatial"下 arm）为门，二者都**只能由本模块新增的第三项触发**：
   - **图片三项**：`WallpaperDialogActivity` 走的是 `R.array.wallpaper_setas_option`（三项，`z3==false`）
     ⇒ `setItems` 的内容判据（items 恰为**视频两项目**文本）不命中 ⇒ W1 不改写；后续 W2 无标记、
     `DesktopSlotSession` 从未 arm ⇒ W3/W5–W8 全部走"原样放行"分支；W4 的窗口分支不成立，
     窗口外分支的门（`g && 两侧 type 相等 && 属三个视频类型）也要求桌面 type 是视频类型 ⇒ 图片路径恒不触发。
   - **视频「设为锁屏」**：弹窗原第 1 项 ⇒ `DesktopChoiceRelay` 的 `else` 分支（`which==0`）
     ⇒ `DesktopOnlyRequest.disarm()` + 原回调 ⇒ `Wallpaper.j` 收 mode 0、无标记 ⇒ W2 走
     "非本模块请求"分支（且会顺手 `DesktopSlotSession.disarm()`）⇒ 其后所有改写点全部不生效，
     宿主原语义一字不变。
   - **视频「设为锁屏和桌面」**：弹窗原第 2 项 ⇒ `which==2` ⇒ `disarm()` + 原回调（mode 2，无标记）
     ⇒ 同上，W2 走"非本模块请求"分支。**唯一行为差异**：W2 现在会顺带 `DesktopSlotSession.disarm()`
     ——那只是清一个本就为空的窗口，对宿主零影响。
   - **窗口内只改 `which==2`**：W5–W8 只把 `WHICH_LOCKSCREEN` 改写成 `WHICH_DESKTOP`，
     `which==1`（桌面自己的静态图）与 `which==3`（两侧）原样通过 ⇒ 即使窗口意外悬空，
     也不会把"两侧"的写入降级成单侧。
   - **回滚粒度**：删 `TargetRegistry` 一行即回到"宿主原行为"（W1 不再补项 ⇒ 无标记 ⇒ 全链零改写）；
     只回滚改槽而保留弹窗，则需同时去掉 W3 的窗口分支 + W4 的窗口分支 + W5–W8 四条表行。

## 六、未验证 / 假定（真机跑通前不要当结论）

> 2026-10-16 第二轮真机后按路线 B 重写。**§二·4 的 `desktop_wallpaper_path` 修复在第二轮仍需要吗？**
> 不需要了——W2 已改走 mode 0，宿主自己的 `g(ctx,intent,true)` 会写桌面 extras，且编辑器展示的是锁屏卡。
> 该 extra 相关代码已删除（它是为 mode 1 补的）。

| 项 | 性质 | 若证伪的现象与处置 |
|---|---|---|
| 宿主模型：动态桌面 ≡ 跟随锁屏（`y6.c` / `q8.K0` 同一谓词） | **静态已证**（§二·6 证据链 1–5：`be/o1.smali:3186-3311`、`y6.smali` 构造器、`r7/f.java:650-689`、`h8.java:51,54`、`be/o1.java:6582`、`w6/i.java:259,267,279`） | 若真机显示桌面能独立成为动态壁纸 ⇒ 说明本轮模型判断有误，路线 B 是过度设计（此时可回退路线 A 重新评估） |
| **mode 0（设为锁屏）会把用户选的视频完整准备进锁屏管线** | **假定，但有实证支撑**：这是用户已确认的回归基线（"视频「设为锁屏」"真机可用）⇒ 该管线端到端通。**未追到具体代码路径**：Photos 只发 `data` + `type=video/*`（`Photos-153.0.0.405-jadx` `WallpaperUtils.java:328`），不给 `lockscreen_wallpaper_type/path`，而 `be/o1` 的 `Q0/T0` 只从这两个 extra 取（`be/o1.java:725,727`）——中间必有本轮未定位的补全路径 | 选「设为桌面」后编辑器预览/裁剪里**不是**刚选的视频 ⇒ mode 0 没吃到新媒体；此时路线 B 前提不成立，需先定位该补全路径（抓 `initializeByIntent:LockscreenWallpaperType:…` 与 `initializeLockscreenData,type: …` 两行） |
| `which` 是"落盘槽 + 应用槽 + 引擎取件"三者的唯一键 | **静态已证**（`WallpaperSetSession.java:72-84` 直通；`LivePaper` `com/hihonor/wallpaper/base/n.java:46-58` 用 `HnWallpaperSetBean.which` 读配置；落盘路径由 `getWallpaperFileTempPath(…, which)` 决定） | 应用成功但桌面不播放/黑屏 ⇒ 引擎按 which=1 取件失败（看 `LivePaper` 侧 `readWallpaperFile returned null, fallback to file path`），需补 W5/W6 覆盖的文件项或改 tag |
| `which=1` 被框架与引擎受理（`setWallpaperAttribute(session, screen, 1, attr)` + `HnWallpaperSetBean(cn,1)`） | **假定**（框架源码只证 4 参重载的 `which` 直通、5 参重载会自己拼 `whichFlags`；无运行时实证） | 桌面不变 + 无报错 ⇒ 诊断 `WallpaperSetSession` 日志 `addWallpaperWithComponent:…:1:` 有无、`applyWallpaper failed` 有无 |
| `h8.a` 的 `(i10 & 2) > 0` 让 which=2 取到**锁屏**记录 uri（= 用户选的视频） | **静态已证**（`h8.java:51`） | 桌面设成了旧壁纸 ⇒ uri 取错侧（看 `h8.a` 的 `add wallpaper type=…, which=…, screenId=…, wallpaperName=…` 日志） |
| W4 窗口内把桌面 type 置 `skip_wallpaper` ⇒ 桌面侧零写入 | **假定**（依据：`h8.e("skip_wallpaper")` 直接 return true，`h8.c` 就不会调 `a(iN,1,…)`） | 桌面被写成静态图 ⇒ W4 窗口分支没命中（看 `wallpaper-hook` 是否只有 W2 一行）或 `h8.c` 的 `dVar.c` 被别处重写 |
| 锁屏保持不动（全程无 which=2 写入） | **设计不变量**：W4 落哨兵后桌面侧不写；锁屏侧那一次 `a(iN,2,…)` 的 which 被 W5–W8 改成 1 ⇒ 没有任何 which=2 的应用；`writeWallpaperExtraFile`（唯一的 which=2 落盘残留）不参与 `setWallpaperAttribute`/`HnWallpaperSetBean` | 锁屏被换掉/变黑 ⇒ 立刻回滚 W7/W8（两行表行）并抓 `dumpsys wallpaper` 的 Lock 段与 `wallpaper-hook` 判据行 |
| 会话窗口不误伤后续流程 | **设计约束**：W2 每次调用都重置窗口；W4 收口后立即 disarm；15 分钟兜底超时 | 后续正常的「设为锁屏」被改槽到桌面 ⇒ 窗口悬空；处置 = 缩短超时/在 `Wallpaper.j` 非本模块分支补 disarm（已有） |
| 图片路径 / 锁屏路径 / 既有"同时"路径零变化 | **设计约束 + 逐条论证**（§五·4） | 静态图片设壁纸异常 ⇒ 回滚 W3 的窗口分支（一行）；「同时设置」行为变化 ⇒ 回滚 W2 的 mode 改写（一行） |
| **记录 JSON 的一致性** | **未验证的已知瑕疵**：走 mode 0 时宿主写的 `galley_lock_wallpaper_config.json` 会描述"锁屏 = 视频 B"，而实际锁屏仍是 A。该文件经 `writeWallpaperExtraFile(tag="extra_files")` 落盘（我们**未**改其 which） | 下次进编辑器锁屏卡显示成视频 B ⇒ 需在保存后把记录的锁屏侧改回 A（本轮未做；要么 hook JSON 序列化，要么在 W4 里回写记录，都会扩大改动面）。**在真机确认桌面能播之前不动它** |

## 七、真机验证清单（用户自执行；AI 未装未启未 force-stop）

前置：`bash tools/dev.sh install` → 启用模块（覆盖安装后 LSPosed 会停用，需重新启用）→ 冷启动图库走一次流程。

1. **挂载判据**（唯一金标准，LSPosed 框架日志，不在 logcat）：
   ```
   bash tools/dev.sh modlog-f HonorHook        # 尾部应有 WallpaperEditorHook: installed 8/8（宿主 153.0.0.423）
   ```
   `installed 8/8` = 八点全挂；`8/8 — 失败: …` 或 N<8 ⇒ 先对版本（`dumpsys package com.hihonor.wallpapereditor`），
   **别先改 hook 实现**。另看通路行应为 `W1: 1/1; W2: 1/1; W3: 1/1; W4: 1/1; W5: 1/1; W6: 1/1; W7: 1/1; W8: 1/1`。
   W5–W8 是框架类 `com.hihonor.android.app.WallpaperSetSession`——若它们全灭，先确认该类在编辑器进程里可加载
   （`adb shell dumpsys package com.hihonor.wallpapereditor | grep -i hihonor.framework` / 或看该四点的
   `hook 未挂载（类/方法不存在…）` 行）。
2. **触发判据**（弹窗点开 → 选「设为桌面」→ 编辑器里应用，各一次后）：
   ```
   bash tools/dev.sh modlog-f wallpaper-hook
   ```
   期望**六类**（顺序即调用顺序；W5/W6 视落盘项数量出现多条）：
   - `视频弹窗已补出第三项（设为锁屏/设为桌面/设为锁屏和桌面）`（W1）
   - `「设为桌面」→ mode 2 改写为 0（借道锁屏管线；W5–W8 会话层 which 2→1 落到桌面槽）`（W2）
   - 若干条 `落盘槽改写 which 2→1（writeWallpaperFile / writeWallpaperBitmap）`（W5/W6）
   - `桌面侧属性补齐（which=2, type=custom_video_wallpaper）`（W3，**窗口内是 which=2 而不是 1**）
   - `动态壁纸应用槽改写 which 2→1（VideoWallpaperService）`（W7）
   - `「设为桌面」：桌面侧落 skip_wallpaper（产物已由 W5–W8 改槽到桌面）`（W4，**最后一条**）
   缺哪条读 §六 对应行。注意 **W8 正常不出现**（桌面侧已被 W4 落哨兵）；W8 出现说明 W4 窗口分支没命中。
3. **桌面/锁屏落地取证（核心判据）**：
   ```
   bash tools/dev.sh shell dumpsys wallpaper | grep -E "mWhich|mBindSource|mWallpaperComponent|mWallpaperProperties"
   ```
   期望：
   - **桌面（System）**：`mWhich=1`、`mBindSource=SET_LIVE`、`mWallpaperComponent=com.hihonor.livewallpaper.paradise/…VideoWallpaperService`
     （对比修复前：`SET_STATIC` + `ImageWallpaper`）；
   - **锁屏（Lock）**：与操作前**完全一致**（`mWhich=2`、`SET_LIVE`、原视频组件、`mWallpaperProperties` 不变）。
   另可看 `wallpaper-hook` 无关的宿主日志：`add wallpaper type=custom_video_wallpaper, which=1, screenId=…`（`h8.a` 的收尾行）。
4. **UI 预期**：图库 → 打开一段视频 → 「设为壁纸」→ 弹窗出现**三项**且第一、三项文案与原有完全一致
   （设为锁屏 / 设为桌面 / 设为锁屏和桌面）；选「设为桌面」→ 编辑器展示**锁屏卡**（本路线固有副作用），
   预览与裁剪时间轴里应是**刚选的视频**（对比修复前：时间轴里是旧锁屏视频）→ 应用后
   **桌面播放视频 B、锁屏仍是视频 A**。
5. **失败分诊顺序**：
   ① `installed N/M` 与宿主版本 → ② `wallpaper-hook` 判据缺哪条（§六 对应行）→ ③ 该点的直连 miss 日志
   （`直连反射未命中 …#…`）与 `hook 未挂载（…锚点未命中）` → ④ 第 3 步的 `dumpsys wallpaper` 三段
   （桌面 / 锁屏 / 组件名）→ ⑤ 分场景：
   - **编辑器预览/时间轴不是新视频** ⇒ §六 第二行（mode 0 没吃到新媒体），路线 B 前提不成立，先定位该补全路径。
   - **桌面仍是静态（SET_STATIC）** ⇒ W7 未生效（`动态壁纸应用槽改写 which 2→1` 行有无）或 W4 提前把桌面写成静态。
   - **桌面变成 SET_LIVE 但黑屏/不播放** ⇒ which=1 的取件失败（§六 第三行）；抓 LivePaper 侧
     `readWallpaperFile returned null, fallback to file path` 与 `getVideoPathFromThemesDir`。
   - **锁屏被改掉** ⇒ 立即回滚 W7/W8 两条表行，并抓 `dumpsys wallpaper` 的 Lock 段。
6. **回滚**：
   - 用户级：关闭模块开关 `wallpaper.video_desktop`（UI 功能页）→ 目标进程下次注入即恢复宿主原行为；
   - 代码级（一键）：删 `TargetRegistry` 一行 ⇒ 弹窗不再出现第三项 ⇒ 无标记 ⇒ 全链零改写（宿主原行为）；
   - 代码级（只回滚改槽、保留弹窗）：删 W5–W8 四条表行 + W3 的窗口分支 + W4 的窗口分支
     （三处，见 §五·4 末条）。

## 八、遗留建议（未做决策）

1. **DexKit 静态缺口**：把 `DexKitHelper.kt:140` 的 `Modifier.isStatic` 过滤放开 + 复核 `MethodResolver` 的
   `firstOrNull` 命中面（会让其它目标的兜底也可能命中静态方法 ⇒ 需回归 themestore/installer/launcher）。
   放开后本文件的 `resolveStaticByFeatures` 可删（回归统一机制）。**本次未动**（超出变更清单，波及全部目标）。
2. **`tools/adapt_new_version.py` 不认本目标的表格式**：脚本只解析 `HookPoint(...)`（数据表）与
   `MountTask(kind = ...)`（源码直挂）两种写法（见该文件头部"挂载点清单提取方式"），而本目标用的是
   `MountEngine.SimpleTask(...)` ⇒ **本目标 8 个挂载点都不会被新版适配校验纳入**（静默漏，不报错）。
   若要让脚本覆盖：给它加第三个匹配分支（`SimpleTask(`，取 `className`/`methodName`/`paramCount` 三个具名参数）
   ——`SimpleTask` 的这三个字段语义与 `MountTask` 完全一致，改动量约 5 行。
3. 根 `README.md` 的"功能"清单需在发布时补一条（本目标用户可见），`RELEASE_NOTES.md` 由 release skill 写。

## 九、写链终局与现行方案（2026-10-17，40+ 轮真机取证）

> §三 的 W3–W8 与 §五/§六/§七 的"借锁屏管线 + which 改写"路线**全部作废**——那些挂载点/拦截器已删
> （挂载点永不触发，见 §九·2）。历史过程保留在 git 历史，此处只写结论。

### 1. 真实写链（客户端全进程探针网的最终结论）

一次「设为壁纸」apply 的数据流：

```
编辑器进程 (com.hihonor.wallpapereditor)
  ├─ mcs 库转码：VideoTranscoder(activity, uri|path, cache/tmp.mp4)   → 转码产物在 cache/tmp.mp4
  ├─ z7(HashMap<tag,File>) 把材料写 /storage/emulated/0/Honor/ThemeTmp/wallpaper/**
  │    （java FileOutputStream 直写；ThemeTmp 用后即删）
  ├─ WallpaperManagerEx.prepareWallpaperMetaData(metaJson)
  │    = new WallpaperSetSession(HwWallpaperManagerEx, metaJson)      ← metaJson：作用域唯一声明处
  │      {"wallpaper":[{"home":{…},"keyguard":{…},"orientation":1,"screen_id":2}],"version":1}
  │      （相对路径根 = ThemeTmp；screen_id=2 是物理屏 id 非 which）
  └─ initWallpaperSetSession(metaJson) → system_server
        HwWallpaperManagerServiceEx.initWallpaperSetSession：
        mkdir .target/{sessionId} + 解析 metaJson 注册会话
system_server (services-magic.jar 的 HwWallpaperManagerServiceEx)
  └─ 自己把 ThemeTmp 材料搬进 /data/themes/0/wallpaper/（属主 system:media_rw）
     + setWallpaperComponent(VideoWallpaperService, which)（both 时）
```

**客户端进程零写**（editor/thememanager/paradise/photos 四进程、以下探针全部零写命中）：
WallpaperManager 156 方法、WallpaperManagerEx 44、HwWallpaperManagerEx 123（AIDL 实现），
IWallpaperManager$Stub$Proxy 48、IHwWallpaperManager$Stub$Proxy 40（运行期反射取得，
类名 `com.hihonor.android.wallpaper.IHwWallpaperManager$Stub$Proxy`——**framework-magic 实证，
接口无 Ex 后缀；此前猜的 `IHwWallpaperManagerEx` 全错**）、WallpaperSetSession 全方法、
ContentResolver/ContentProviderClient 写形状、FileOutputStream/FileDescriptor、nio、
WallpaperFileProvider 写 uri。**f Vors falls：fd 命中是 UNIXProcess 管道（转码子进程），非文件写。**

### 2. 引擎模型（paradise 进程 LivePaper 11.0.0.252）

`n4.z`（VideoWallpaperPlayer）**单 player 单视频文件**：`f3507v` = `live_photo/unlock_video.mp4`；
keyguard 态只切模板段（`AVPlayer`=锁屏带 position / `AVPlayer_desk`=桌面无 position，均无视频路径
字段）。G==3（组件绑 both）解锁后引擎不 release、`updateTemplateSource(AVPlayer_desk)` 继续播
**同一文件**——"桌面动态"播的就是锁屏管线产物。`video_live_wallpaper.mp4`（s2.a 主题链）编辑器
链路不产。**⇒ 桌面独立动态视频 = 引擎层不可表达，必须 hook。**

### 3. 现行方案（已真机验证：锁屏 A 动态 + 桌面 B 动态，一步「设为桌面」）

| 点 | 进程 | 挂载 | 作用 |
|---|---|---|---|
| W1 | editor | `AlertDialog.Builder#setItems`（内容判据） | 弹窗补第三项「设为桌面」 |
| W2 | editor | `Wallpaper#j(Context,Intent,int)`（锚点 `intent_set_both`） | 识别本模块点击 → 放行原生 both 链 + arm 会话窗口 |
| W9 | editor | `WallpaperSetSession(IHwWallpaperManagerEx,String)` 构造器 + `WallpaperManagerEx#prepareWallpaperMetaData(String)`（直通包装，双挂防漏） | metaJson 删 keyguard 键 + home.extra_files 剔 `galley_lock_wallpaper_config.json`（两段记录合并文件，防止锁屏记录被桌面 scope 覆盖） |
| W14 | editor | `java.io.FileOutputStream` 全构造器 | 窗口内把 ThemeTmp 的**锁屏侧材料**写重定向到 `/data/local/tmp/hh_wp_sink.*` ⇒ system_server 无源可搬 ⇒ 锁屏零接触；`unlock_video.mp4` **放行**（桌面态引擎播它） |
| W13 | editor | W9 proceed 后 Handler post 10s | `WallpaperManager.setWallpaperComponent(VideoWallpaperService)`（反射——honor 编译 SDK 裁了该方法）：收窄会让 system_server 清锁屏活组件（SET_LIVE_TO_CLEAR），重绑回 both |
| W12 | paradise | `l3.l(VisionControllerImpl)#createPlayer(holder,path,json)`（DexKit 锚点 `createPlayer start, videoPath = `，searchPackages wallpaperpluginsdk+l3） | json 含 position（锁屏段）：首次备份 unlock_video.mp4 → filesDir/hh_lock_video.mp4，之后锁屏恒播备份；桌面段不动（播 unlock_video.mp4 = 桌面 apply 写入的 B） |

**用户操作流**：视频 A → 设为锁屏（原生）；视频 B → 设为壁纸 → 「设为桌面」（本模块注入项）。
一步完成，锁屏零接触。

### 4. 已知限制与坑

1. **备份刷新**：W12 备份只在不存在时创建。用户更换锁屏视频后需清 paradise
   `files/hh_lock_video.mp4`（后续 UI 提供重置入口）。
2. **W14 sink 文件清单**与 `isLocksideTmpPath` 的路径特征耦合宿主版本（新增锁屏侧文件类型需补特征）。
3. **装机坑**：`INSTALL_FAILED_ABORTED: User rejected permissions` 在无人值守时会连环失败（确认弹窗
   无人点）；每次 install 必跟 enable-module。
4. **宿主升级适配**：W2 锚点 `intent_set_both`；W12 锚点 `createPlayer start, videoPath = `（mcs/
   pluginsdk 类名不混淆，随 LivePaper 版本核对）；W9 框架类名稳定；W14 路径特征见 2。
5. mcs 库类（`com.hihonor.mcs.media.codec.frameextractor.VideoTranscoder` 等）**不在 paradise/
   photos 进程 dex**，只在 editor——挂它必须 editor 进程。

> **状态（2026-10-18）：功能已放弃，hook 代码已全部删除**（`WallpaperEditorHook`/`WallpaperEngineHook`
> 及其挂载表/注册/scope/开关均已拆除）。本文档保留作逆向记录与未来重启的依据——§九 的写链终局与
> 引擎模型是本轮 40+ 轮真机取证的结论，仍然成立；v1–v5 各路线的失败/放弃原因见 §九·3 与下文各节。
> 若未来重启：从 §九·3 的表格重建，优先评估「v5 极简形态」（原生 both + 引擎换源双挂点），并先解决
> 验证难点（锁屏与桌面视频画面高度相似导致人工验证不可靠——需抽帧 diff 或更换差异明显的测试素材）。
