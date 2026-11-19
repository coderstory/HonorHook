# search（荣耀智慧搜索）逆向档案

宿主：`com.hihonor.search` **v10.0.33.300**（aapt2 实证，2026-11-18 真机拉取 base.apk 到 system-files/com.hihonor.search/，10 dex 46678 类）。
需求：桌面下拉搜索（综合 tab）隐藏「应用市场」推荐卡与「搜索大全」网络内容卡（含热搜）。本机 tab 保留不动。

## 结果页架构（综合 tab 数据流）

```
搜索请求 → fq5/gq5（协程 lambda，R8 名）→ uo5（GeneralDataProcessor，tag 实证）
  → cp5.<init>(Query, List, LocalAiInfo, OperStrategyInfo, Boolean, int)   ← 唯一汇聚点（hook 点）
  → general/b.smali 包 GeneralTabViewModel$Intent$SetPageData
  → general/e（状态 holder，字段 i:Lcp5）→ GeneralTabFragment
  → GeneralTabAdapter=hp5 extends jf9 extends oc8 extends androidx f0
  → 路由器 if9（字段 a=List<Strategy>，22 个 generaltab Strategy 全部未混淆名）
```

- `uo5` 静态映射器：`k(GeneralDataRsp,ContentCard,Query,I,String,String)` 构建 `TopAppCard`；
  `c(...)` 是大分发器（按卡片类型字符串 "topCard"/"recommendApp"/… 分派），merge 点
  `Collection.add(v6)` **无 null 守卫** ⇒ k() 返 null 会把 null 塞进列表（NPE 风险）。
- `cp5` 字段（smali 实证）：a=Query, b=List, c=List, d=String, e=LocalAiInfo, f=IntentInfo,
  g=OperStrategyInfo, h=List, i=Boolean, j=String。**6 参构造只收一个 List（存 b）**，
  zwf/qs5/nn8/gq5/general/b/general/e 六处调用点全走它。
- `cp5.<init>` 参数序列**全 dex 唯一**（声明 + 6 调用点 = 7 处精确匹配，全为未混淆类型）⇒ DexKit 锚点。

## 被否决的候选点（勿重走）

| 候选 | 否决原因 |
|---|---|
| `uo5.k` → 返 null | c() merge 无 null 守卫，null 进列表下游崩；且 fq5:1738 的调用点本来就丢弃返回值（预热性质） |
| 路由器 `if9.a` 返 -1 | `if9.d(parent,viewType)` = `List.get(viewType)`，-1 → IndexOutOfBounds **崩宿主**；`if9.b` 同 |
| `DisabledWebpage` 借道 | WebpageDisableStrategy.c() bind 是空 return-void，但 e() 会 inflate `layout/item_web_disable`(0x7f0d0332)=LinearLayout+HwImageView+HwTextView **可见占位** |
| 卡片构造器置空 apps | TopAppCardStrategy.f 对 `getApps().isEmpty()` 早退（不设标题不装 adapter）但**空壳行仍在列表里**（布局已 inflate） |
| `zvc.S()` / `gvc.E()` | WebView 可用性探测 / 全局开关，8 处调用面太大，勿 hook |

## Hook 点（SearchHook.PAGE_DATA_POINTS，1 点）

| id | 类#方法 | 签名 | 手法 |
|---|---|---|---|
| search:page.SetPageData.filter | `qp5`#`<init>` **10 参主构造器**（R8 名，OTA 漂移走 DexKit） | (Query, List, List, String, LocalAiInfo, IntentInfo, OperStrategyInfo, List, Boolean, String)→void | **before 替换 List 实参**：剔除类名后缀 ∈ {TopAppCard, AdCard, Webpage, DisabledWebpage, HotSearchData} 的项，proceedWith 原方法（同 HonorQinxuanHook 运营条目置空手法） |

**选点演变**（2026-11-18 真机三轮实证）：初版挂 synthetic 6 参构造器（6 处 SetPageData 生产者
直调它），installed 1/1 但用户搜索路径**零调用**（诊断版 logcat 实证）——数据实际走 10 参主构造器
与 static `a()` 复制工厂，二者最终都汇聚到 10 参主构造器（synthetic 6 参内部也是 invoke-direct/range
委托它，qp5.smali:73）⇒ **10 参主构造器才是唯一总漏斗**。最终版挂 10 参，真机判据 installed 1/1 +
用户实测应用市场/搜索大全块全消失。

剔除的卡片（全部未混淆，resultpage/domain/model）：

| 模型 | Strategy | 界面 |
|---|---|---|
| TopAppCard | TopAppCardStrategy | 「应用市场」安装推荐卡（标题 = string/app_market_name 0x7f130135） |
| AdCard | AdCardStrategy | 「应用市场」广告卡（同一标题） |
| Webpage | WebpageStrategy | 「搜索大全」网络内容卡（footer = string/general_tab_web_content 0x7f1302e3「以下内容由"%s"提供」） |
| DisabledWebpage | WebpageDisableStrategy | WebView 不可用降级卡（一并剔除） |
| HotSearchData | HotSearchInfoStrategy | 热搜卡 |

## 判据与验证清单（用户自执行）

1. `bash tools/dev.sh build-release` 后 MT 管理器安装（`am start` VIEW intent，见 CLAUDE.md）；
2. `dev.sh enable-module`（桌面）或 LSPosed 手动重启用；force-stop `com.hihonor.search`；
3. LSPosed 日志（modlog）：`search-hook 生效: search:page.SetPageData.filter`；
4. 桌面下拉搜「蓝牙」：应用市场区块与搜索大全块应消失，本机应用/设置搜索不受影响。

## 10.0.38.300 适配（2026-11-18，真机 OTA 实证）

- 逆向当天宿主即从 33.300 自动升级到 38.300，首轮真机判据 `installed 0/1`——直接导因：R8 改名
  **`cp5` → `qp5`**，直连反射与严格轨（类名钉死）全灭。
- 构造器两形态**签名逐字节未变**（`.buildtmp/search-smali-38/qp5.smali`）：
  synthetic 6 参 `<init>(Query, List, LocalAiInfo, OperStrategyInfo, Boolean, int)` 行 29（6 处调用点：
  tu5、general/b、general/e、dt5、oo8、tq5——即全部 SetPageData 生产者）；全量 10 参 行 106（仅 sq5 调用）。
  实体类 `feature/resultpage/data/entity/*` 未混淆未迁包。`general/e.i:Lqp5` 槽位不变。
- **38.300 installed 0/1 真根因（2026-11-18 离线探针+读码定案，非适配回归）**：引擎不支持构造器解析——
  直连轨只查 `declaredMethods`（`<init>` 永远不在其中）；DexKit 轨命中构造器后调
  `getMethodInstance` 抛 `NoSuchMethodException` 被吞成"候选集不完整"→ 严格轨唯一命中被拒收 →
  三轨全灭。DexKit 2.2.0 对构造器必须走 `getConstructorInstance(loader): Constructor`
  （MethodData.class 字符串池实证）。33.300 基线从未真机验证过（OTA 抢先），故该缺陷首次在此暴露；
  MMS T4 不受累（它绕过引擎自带 ClassLoader 直查构造器）。**已修**：解析链 Method→Executable 加宽
  （直连轨 `<init>` 走 declaredConstructors；findMethods 按 isConstructor 分派；
  nameOf/returnTypeNameOf 归一；漂移判定改 nameOf）。此前"点分名/描述符疑点"作废：
  DexKit 官方 README 明确 paramTypes/addFieldForType 收 java 点分名，锚点口径一直没问题。
- 本点维护法：数据类无字符串锚点，宽松轨不可依赖；OTA 后反编译 grep
  `synthetic constructor <init>(Lcom/hihonor/search/feature/resultpage/data/entity/Query;Ljava/util/List;L.*LocalAiInfo`
  找新类名，改 `SearchHook.CLS_PAGE_DATA` + `FeatureCatalog.baselineVersion`。

## 已知边界

- `uo5.c` 内部仅 3 处 kotlin Result 包装（`ky5.u`=createFailure），无 COROUTINE_SUSPENDED 探测，
  判定为纯同步映射——但因 hook 收口在 cp5 构造（更下游），此判定不影响正确性。
- 热搜**输入联想**（AssociateWordStrategy）不在剔除范围（属输入框联想词，非结果页推荐）。
- jadx 全量反编译 63% OOM（30773 类仅出 23217 .java），本档结论全部以 apktool baksmali 全量
  smali（`.buildtmp/search-smali`，非入库）+ MT MCP xref 实证；重验用 `tools/dev.sh` 反编译子命令。
