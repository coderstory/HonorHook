# TODO

> 2026-10-13 整理：旧清单并入"已完成"归档，新增邮箱大师线与迭代候选。进度判据一律 = 真机 `installed N/M`（模块日志）。

## 在途：网易邮箱大师（去广告 + 本地会员）

- [ ] **T1 真机验证**：装最新包（含易盾闪退修复）→ LSPosed 重新启用 → 冷启动验三样：不闪退循环 / 开屏秒过 / 会员态与引导弹窗消失
- [ ] **T2**（等 `[mm-scout]` 侦察日志回来开工）：①信息流广告精确挂载（自渲染运营位不走联盟，按方法面挂）②VIP 判定真名修正（三个彩票若未命中，按 DashiVipService/Impl 真方法名重挂）
- 判据：`tools/dev.sh modlog MailMasterHook` → installed N/M + [mm-scout] 16 类方法面

## 待验证

- [ ] 设置应用详情页「停用」按钮：改条件化介入（仅原生灰按钮才恢复可点+补字）——用户 2026-10-13 拍板
- [ ] MFA 去广告是否仍有效：10-13 全天日志零记录，开一次复验；复现则按"未注入"线查（模块停用/作用域）

## 迭代候选（2026-10-13 推荐，按价值排序）

- [ ] **UI 面板「判据日志」页**：面板直读 installed N/M 与模块日志，验证不依赖电脑（杠杆最高）
- [ ] 主题商店「免费领」tab：搜索页本地硬编码，云控与槽位裁剪都管不到（docs/theme-store.md §七·2，调研已到位）
- [ ] OTA 适配例行化：ouc 18.0.3.206→230 实证 OTA 常态；荣耀 OTA 后例行跑 `adapt_new_version.py`（71 挂载点）+ 剩余 5 目标渐进迁 HookPoint 表
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
