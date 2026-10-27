# 搜狗输入法会话审计报告（2026-10-28 夜间会话独立复盘）

范围：本会话对 ImeHook 的全部改动、每个挂载点的有效性判定、两问题（套装穿戴 / 商店广告）的根因分析、遗留事项。

## 一、挂载点逐项审计

| 挂载点 | 判据 | 判定 | 处置 |
|---|---|---|---|
| `ThemePreviewActivity.r1`→1 | 已触发（01:07） | **有效但低价值**：命中老预览页，用户实际走 SmartThemeSkinDetail | 保留（PoC 取证，标注 TEMP） |
| `JSONObject.optInt("payment")`→0改1 | **未观测到触发** | **无效且高风险**：全局改动 org.json 解析层，可能误伤其他 payment 语义读取 | **删除**（本轮） |
| `SmartThemeSkinDetailActivity.N1`→3 | 已触发（01:12） | 有效：用户实际详情页，按钮态已变 | 保留（PoC） |
| `CostumeDetailDataBean.getPayStatus`→1 | 已触发（01:12/01:48） | 有效：套装按钮放行实证 | 保留（PoC） |
| `okhttp.a.postSuccess` 广告过滤 | 已触发（02:08 起 4 次） | 部分有效：more_recommend/detail 过滤生效；**首页混排位漏网**（键名白名单缺陷） | 保留，规则已改通用（本轮） |
| `d34.f`/`nb0.c`/`fu1.f`/`by3.b` 四个调试点 | 挂载失败（11/12、11/13） | **全部无效**：defpackage 静态方法挂载失败模式未查明（引擎侧待查） | 已删（by3）/待删（本轮清理） |
| `theme.b.Z` 死链→COS | 已触发（02:21/02:29） | 有效但不够：死链替换成功，下游仍有断点 | 保留 |
| `HomeThemeService.downloadAndInstallTheme` 注入 | **未验证**（部署于 02:40，用户最后测试在 02:39） | 逻辑经静态推演成立（见 §二） | 保留，待一次验证 |

## 二、套装穿戴「皮肤包错误」根因分析（完整决策树）

```
启用 → downloadAndInstallTheme(activity, true, info, …)
 └→ T0(info, skinId)
     ① db6.b：当前主题重复→3；大九格→2；c0()→4；否则 0 继续
     ② g = info 拷贝；this.i = 文件名（ssfDownloadUrl 尾段）
     ③ 后台 M(fullPath, hd6.a(z))：本地扫描
        - fullPath.ssf 存在且 R()==0 → 本地安装（bool=false）
        - 扫候选目录命中 this.i 且 R()==0 → 本地安装（false）
        - 都没有 → true（需网络下载）
     ④ onNext(bool)：
        true  → R0：strZ = skinId空 ? ssfDownloadUrl : Z()〔skindl.php〕
                → db6.a(strZ)：空→7；断网→5
                → np0.Q()：外部存储未挂载→sdcard 提示
                → doThemeDownload(this.h=strZ) → 广播 result_code → X()
        false → S0(info) → 本地安装 → 文件无效→皮肤包错误
```

### 三重根因（按因果顺序）

1. **服务端下线**：`download.sginput.qq.com/skin/skindl.php` → **404**（curl 实证）；`srv-ios ssf_download.php` 拒服务；`shouji.sogou.com/skindl/download.php` 空响应。老格式主题包**已无任何分发源**。
2. **客户端数据缺口**：`v1/store/suit/items` 的 item.info **无 `ssf_download_url` 字段**（仅死链 `fileAdd`，且该字段 Android 端无消费者）→ `ThemePreviewItem.ssfUrl=null` → `ThemeItemInfo.ssfDownloadUrl=null` → R() 判「非网络主题」→ **不发起下载直接报皮肤包错误**（02:32 后各次测试现象，Z/X 零触发与此吻合）。
3. **格式兼容性（此前误判，已纠正）**：`h87` 双格式兼容——`phoneTheme.ini` 存在走新格式分支（h()），否则要求顶层 `skin.ini`。COS repack 文件（新格式）**可被正常安装链解析**。新格式可用 ≠ 格式障碍，我此前「格式不兼容」结论有误，撤回。

### 修复设计（DAI + Z 双点，已在 12 点构建中）

- `downloadAndInstallTheme` 入口：补 `ssfDownloadUrl=cosUrlMap[skinId]`（缺则匿名补查 v1/skin/detail）、清 `skinId`（强制走 R0 下载分支）、清 `md5`（跳过本地校验）、置 `isNetTheme=true`；
- `Z()` 兜底：构造出 skindl 死链时查 `cosUrlMap` 替换（cosUrlMap 由 postSuccess 从一切含 `ssf_download_url`+`skin_id` 的响应递归收集）；
- 下载真文件 → `phoneTheme.ini` 新格式分支解析 → 安装。

**当前状态：DAI 构建于 02:40 部署、02:41 会话已加载（installed 13/13），尚未经用户点击验证。** 若仍失败，下一个观测点是 `INSTALL_RESULT code=`（X hook 已挂）。

## 三、商店广告根因分析与规则定稿

- 广告形态一：详情页底部 `more_recommend`（付费皮肤推广条，ad_type 恒 0）→ 整体清空 ✅（判据已触发）
- 广告形态二：首页 `toolbox.content_list`（运营推荐付费皮肤位，随 `content_recommend_switch`）→ 整体清空 ✅
- 广告形态三：`data.home_store_recommend_cate.home_store_recommend_item_list` 等任意混排位条目（真机投放，ad_type≠0）→ 通用规则剔除 ✅（本轮新增，此前键名白名单漏网——「广告还在」的主因）
- 广告形态四：`banner_list` 轮播横幅 → 清空 ✅
- **已知局限**：商店页对 home_page 响应启用 okhttp 缓存（`x12.a`：`.V(true)` secSginput 缓存节）——okhttp 缓存命中时 postSuccess 仍会触发（缓存对 Call 层透明），故过滤有效；但**页面自身的 ViewModel/持久化缓存**（若存在）不受影响，需实测确认。AMS 广告 SDK 信息流（`hf3.d` 单元 1947507188770668544）为另一独立来源，本轮未覆盖。

## 四、构建陷阱实录（本轮两处重大踩坑）

1. **sdcard FAT 时间戳粒度**：源码修改后 1-2 秒内构建，Gradle 增量编译误判「未变更」跳过 Kotlin 编译——导致「md5已清」等改动实际未进 APK，两轮测试打了旧包。对策：`--rerun-tasks` 强制重编，或改前 touch + sleep 2。
2. **`strings` 工具 UTF-8 假阴性**：以 `strings dex | grep 中文` 验证构建产物，中文字符串（非 ASCII）被 strings 丢弃 → 全部误判「未包含」。对策：`unzip -p apk classes*.dex | grep -ac '中文'`（解压后字节级 grep）。

## 五、清理清单（本轮已执行）

- 删除：`JSONObject.optInt("payment")` 全局 hook（无效+高风险）
- 删除：DUMP 分块转储、FERR 异常日志、INSTALL_RESULT 调试（若后续排查可再临时加回）
- 待删（用户确认披露完成后）：r1/N1/payStatus/DAI/Z 五点 TEMP PoC 及 KEY_IME_SKIN_PAY_BYPASS 开关
