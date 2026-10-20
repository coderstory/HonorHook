# TODO

> 2026-10-13 整理：旧清单并入"已完成"归档，新增邮箱大师线与迭代候选。进度判据一律 = 真机 `installed N/M`（模块日志）。

## 已放弃：网易邮箱大师（2026-10-13 拍板）

- 易盾企业壳 runtime 反制无 Java 层解：注入即 native `exit(28)`（主+子进程全灭，5/5 复现；Java 三出口探针零命中），证据链见 commit 3fd6424 与 T1 实测记录。
- 功能已下线：MailMasterHook / FeatureCatalog 两开关 / scope 条目全撤（本 commit）；调研档 `docs/reverse/archive/mailmaster.md` 留档；生态或壳策略变化时可 git revert 复活。

## 待验证

- [x] 设置应用详情页「停用」按钮：两场景重写并真机验收（2026-10-13）——用户应用零介入（Q3 恒 setText 卸载），系统应用修管控灰按钮 + HwCust 清字（按 mAppEntry.info.flags 分流，commit 8a8556c）；顺带修 scope set 被未安装包卡断致作用域抹空
- [x] MFA 去广告复验：installed 1/1，开屏已跳直达主界面，基线 1.0.5 一致（2026-10-13）

## 迭代候选（2026-10-13 推荐，按价值排序）

- [ ] **UI 面板「判据日志」页**：面板直读 installed N/M 与模块日志，验证不依赖电脑（杠杆最高）
- [ ] 主题商店「免费领」tab：搜索页本地硬编码，云控与槽位裁剪都管不到（docs/theme-store.md §七·2，调研已到位）
- [ ] OTA 适配例行化：ouc 18.0.3.206→230 实证 OTA 常态；荣耀 OTA 后例行跑 `adapt_new_version.py`（现提取 140 点，口径见 hook-inventory-audit.md §一）+ 剩余目标渐进迁 HookPoint 表
- [ ] 短信 T4 收尾：服务关时验证码卡「复制」按钮渲染路径（docs/reverse/sms-ads.md §五）
- [ ] 埋点 hook「Ex 工厂收口」1 点持续 2/3 失败（autoCollect 类不存在）：修或降级登记

## 主题商店老 bug（未动）

- [ ] 字体下载进度卡 0%；动态壁纸下载后点应用没反应；小部件/图标下载后提示文件损坏；下载记录里没未付费主题

## 设备/环境待办

- [ ] Git for Windows libexec 被 Rider dll 污染（git push/fetch 全坏，github-api-push skill 兜底中）——重装 Git 后执行 `git fetch && git reset --hard origin/dev` 收掉 API 推送产生的本地/远端 sha 分叉

## 已完成（旧清单归档）

- [x] 设置首页账户名（HONOR ID）后小红点隐藏（SettingsHook S2，installed 2/2）
- [x] 禁止系统更新后台自动检测/下载/夜间安装/升级提醒（OucHook，installed 11/11；设置内手动检查保留）
- [x] 状态栏 5G 类型标隐藏（SystemUiHook U2，installed 7/7）
