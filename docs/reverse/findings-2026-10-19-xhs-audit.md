# findings：HonorHook XHS 目标全量 hook 审计（2026-10-19 快照）

> 审计范围：target/xhs/ 7 文件 + FeatureCatalog + strings.xml + vendored 7 文件 + docs 6 份。
> 快照语义：单链定案（SavePivot/BatchSave/VideoSave），五轨实验全量回退无残留。

## ① 挂载点总表（9 点 = 8 静态 + 1 动态）
| # | 功能 | 类#方法 | proceed 语义 | 真机验证 |
|---|---|---|---|---|
| X1a | 保存解锁 | MediaSaveConfig.b()→false | proceed 后改返回值 | ✅ 9.50 3/3 |
| X1b | 保存解锁 | FunctionSwitch.a()→true | 按条件改 true，付费放行 | ✅ |
| X1c | 保存解锁 | ShareInfoDetail$Operate.a()→false | 按 type 条件改 false | ✅ |
| X2a | 去水印 | MediaSaveConfig.c()→true | 不 proceed | ✅ |
| X2b | 去水印 | nd8.j.b→null（DexHint 3 串锚） | 不 proceed | ❌ 新点未装机 |
| X2c | 去水印 | ImageBean disable_ai getter（注解反射） | proceed 后恒 true | ✅ 9.49；9.50 未单测 |
| X4 | 视频直链 | OkHttpClient$Builder.build() 注拦截器 | proceed 必回传（null 闪退实证） | ✅ 1/1 |
| X5图 | 批量保存 | ji9.b#invoke | 条件拦截（链接可得不 proceed） | ⚠️ 拦截态未验 |
| X5视频 | 批量保存 | a59.q#invoke（classFieldTypes 锚） | 条件拦截 + 回落反馈卡 | ⚠️ 拦截态未验 |

触发链：点击轨 availableShareLink（内存判定）→ 可得=拦截+解析；miss=proceed+回落。响应轨仅回落路触发；ASK_DEDUPE 10s 收口双轨。单链：shareLinkOf → hostShortUrl（i3c.d→getShortUrl）→ vendored resolve（expandShortUrl 302）→ ResolvedNote。

## ② 死代码（6 项，grep 零引用）
D1 XhsImageUrl toOriginal+QUALITY_QUERY；D2 lastVideoNoteId；D3 stashedFeed/stashFeed；D4 availableShareLink module 留参；D5 VideoCandidate bitrate/codec（选档不消费）；D6 "xhs:noWatermark.qc8j.b" 旧 id 串（改名动日志指纹，拍板）。附：strings.xml batch_save desc「保存本张」与现行交互不符。

## ③ 冲突/语义
拦截后响应轨结构性不触发（一致）；parsedEmptyNotes 无 TTL + 黑名单后仍白跑解析；响应轨不查黑名单；kotlin.Unit 自造返回值未实证；拦截跳过宿主 default 副作用未验证；deleteLowQuality 跨应用窗（E13 取舍）；peekBody 512KB 截断；历史进入无 share_info 响应 → 回落；u0/getShortUrl 双混淆名漂移=安全回落。

## ④ 文档漂移
xhs.md §9.3/9.4（旧采集架构）、§三/§四（X3/9.49.1/5 Feature）；xhs-compat（大面积过期）；xhs-downloader-parity（提案已落码未销账）；xhs-anchors（X3 行去留、缺 getShortUrl/X2c 行）；FeatureCatalog:254 注释。

## ⑤ 风险
P0=0。P1=3：①19:26 后全链零真机验证 ②kotlin.Unit 返回值 ③X2b 未装机。
P2=8（见③）。vendored 7/7 byte-identical submodule 1.4.1；compileOnly 运行期受控。

## ⑥ 清理动作
删除：D1/D2/D4/D5 + FeatureCatalog:254 注释。修复（拍板）：D3 去留、D6 改名、黑名单预查/TTL、strings:121、anchors 表、xhs.md 重画、compat/parity 销账。验证：装机 judge + 视频三路×拦截矩阵 + 图片失败卡回落 + 进度/取消 + Unit 回归。
