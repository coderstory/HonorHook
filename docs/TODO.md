# TODO

> 2026-10-13 整理：旧清单并入"已完成"归档，新增邮箱大师线与迭代候选。进度判据一律 = 真机 `installed N/M`（模块日志）。
> 2026-10-31 更新：并入搜狗键盘 v1.2.3 三功能验收项与 ImeHook 侧已知缺陷（未修）。

## 已放弃：网易邮箱大师（2026-10-13 拍板）

- 易盾企业壳 runtime 反制无 Java 层解：注入即 native `exit(28)`（主+子进程全灭，5/5 复现；Java 三出口探针零命中），证据链见 commit 3fd6424 与 T1 实测记录。
- 功能已下线：MailMasterHook / FeatureCatalog 两开关 / scope 条目全撤（本 commit）；调研档 `docs/reverse/archive/mailmaster.md` 留档；生态或壳策略变化时可 git revert 复活。

## 待验证

- [x] 设置应用详情页「停用」按钮：两场景重写并真机验收（2026-10-13）——用户应用零介入（Q3 恒 setText 卸载），系统应用修管控灰按钮 + HwCust 清字（按 mAppEntry.info.flags 分流，commit 8a8556c）；顺带修 scope set 被未安装包卡断致作用域抹空
- [x] MFA 去广告复验：installed 1/1，开屏已跳直达主界面，基线 1.0.5 一致（2026-10-13）
- [ ] 搜狗键盘 v1.2.3 三功能真机验收：T5 去开屏广告（`px5.b` 单点）、T6 皮肤商店去推广（`postSuccess` 响应层过滤）、皮肤付费门 + 套装安装（2026-10-28 部署后 `installed 13/13`——当时 13 点，现 17 点，未经点按验证；观测点 `INSTALL_RESULT`/`DOWNLOAD_URL` 仍在挂）

## 迭代候选（2026-10-13 推荐，按价值排序）

- [ ] **UI 面板「判据日志」页**：面板直读 installed N/M 与模块日志，验证不依赖电脑（杠杆最高）
- [ ] 主题商店「免费领」tab：搜索页本地硬编码，云控与槽位裁剪都管不到（docs/theme-store.md §七·2，调研已到位）
- [ ] OTA 适配例行化：ouc 18.0.3.206→230 实证 OTA 常态；荣耀 OTA 后例行跑 `adapt_new_version.py`（现提取 140 点，口径见 hook-inventory-audit.md §一）+ 剩余目标渐进迁 HookPoint 表
- [ ] 短信 T4 收尾：服务关时验证码卡「复制」按钮渲染路径（docs/reverse/sms-ads.md §五）
- [ ] 埋点 hook「Ex 工厂收口」1 点持续 2/3 失败（autoCollect 类不存在）：修或降级登记
- [ ] `docs/reverse/sogou-ime.md` 补皮肤面记录：10-27 的「明确不做」结论已随 56c3d97 删除，现实现（S-COOKIE 复刻 + COS 直链替换 + 付费状态机）在逆向档案里无对应段落

## 已知缺陷（未修，2026-10-31 记录）

- ImeHook `refetchSuitItems` 在 okhttp 响应回调线程内串行发 HTTPS（每皮肤一次、8s 超时）：套装 20 件最坏阻塞 ~160s。属 2026-10-28 已真机跑通的路径，改动需同期真机复验，暂不动。
- ImeHook `ID_SKIN_PAYMENT` 挂在 `org.json.JSONObject.optInt`（进程级基础设施类，非业务类）：虽有 `key == "payment"` 守卫，失效面覆盖整个进程的 JSON 解析；宿主或框架更换 JSON 实现即静默失效或误伤。
- ImeHook 判据 M = 17，其中 4 个是纯诊断点（`optInt`/`u0`/`T`/`X`）：`installed 17/17` ≠ 17 个功能生效，功能点为 13。

## 主题商店老 bug（未动）

- [ ] 字体下载进度卡 0%；动态壁纸下载后点应用没反应；小部件/图标下载后提示文件损坏；下载记录里没未付费主题

## 设备/环境待办

- [ ] Git for Windows libexec 被 Rider dll 污染（git push/fetch 全坏，github-api-push skill 兜底中）——重装 Git 后执行 `git fetch && git reset --hard origin/dev` 收掉 API 推送产生的本地/远端 sha 分叉

## 已完成（旧清单归档）

- [x] 设置首页账户名（HONOR ID）后小红点隐藏（SettingsHook S2，installed 2/2）
- [x] 禁止系统更新后台自动检测/下载/夜间安装/升级提醒（OucHook，installed 11/11；设置内手动检查保留）
- [x] 状态栏 5G 类型标隐藏（SystemUiHook U2，installed 7/7）
- [x] 搜狗键盘：去开屏广告 + 皮肤商店去推广 + 皮肤付费门（v1.2.3，2026-10-28 发布）
- [x] 清理 ImeHook / FeatureCatalog / strings 的 PoC·TEMP 注释 36 行 + 修 `ID_DBG_U0`/`ID_DBG_RESULT` 双重 `proceed`（437836b，2026-10-31）
