<div align="center">

# HonorHook

**荣耀 / MagicOS 的「去广告 · 解锁 · 隐私」开关箱**

一个 LSPosed 模块，22 个系统与第三方应用装上就按你要的生效 —— 只改内存态，不刷机、不动系统分区。

[![Release](https://img.shields.io/github/v/release/coderstory/HonorHook?label=%E6%9C%80%E6%96%B0%E7%89%88&color=blue)](https://github.com/coderstory/HonorHook/releases/latest)
[![License](https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF-AGPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20%7C%20arm64--v8a-3ddc84.svg)]()
[![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20API%20102-8A2BE2.svg)]()
[![Downloads](https://img.shields.io/github/downloads/coderstory/HonorHook/total?color=blue)](https://github.com/coderstory/HonorHook/releases)

| 功能面板 | 小红书：保存被模块接管 | 主题商店：会员态收口 |
| :---: | :---: | :---: |
| <img src="docs/images/panel.png" width="250" alt="HonorHook 功能面板"> | <img src="docs/images/xhs-save.png" width="250" alt="小红书下载原图确认卡"> | <img src="docs/images/theme-vip.png" width="250" alt="主题商店会员态"> |

<sub>真机截图（MagicOS 11 / AAK-AN00）· 头像与账号已打码 · 小红书背景为第三方内容，已模糊</sub>

</div>

---

## 这是什么

HonorHook 是一个 **LSPosed 模块**：装到荣耀手机上，把出厂时塞进去的那些广告、推广、埋点、会员墙，一项一项摘掉 —— 其余一切照旧。

- **装上就生效**：功能默认全开，不用你逐个去开；不想要哪项，在面板里关掉，重启那个应用即可。
- **只改运行内存**：不写系统分区、不动应用数据、不装任何额外组件。手机重启就恢复原样，卸载模块等于彻底还原。
- **不碰你的数据**：不采集、不上传任何东西；模块唯一的对外请求，是帮你查一下有没有新版本。

## 能替你省掉什么

### 系统应用

| 应用 | 你会得到什么 |
|---|---|
| **主题商店** | VIP 状态收口、资源免费化、试用不倒计时、底部推广 tab 与广告槽位消失、下载走 VIP 通道、动态壁纸应用修复 |
| **包管理器** | 安装页不请求广告与 AB 实验、不展示广告与推广卡、不拦截三方跳转、跳过联网安全检测、不提示开启安全模式 |
| **系统桌面** | 隐藏 YOYO 卡片标题（图标保留）、文件夹点开不跳二次确认、清后台保留当前应用 |
| **系统更新** | 禁后台自动检测、禁 WiFi 自动下载、禁夜间安装、禁升级提醒（**手动检查更新保留**） |
| **系统管家** | 病毒检测 / 查杀 / 病毒弹窗三路全禁（装完不再弹"发现病毒应用"） |
| **系统设置** | 应用详情页「停用」按钮恒可点（荣耀对管控应用置灰）、首页 HONOR ID 账户小红点隐藏 |
| **短信** | 推广卡片统一清除 |
| **SystemUI** | 隐藏副卡图标、5G 标识、双排信号与双排 5G 制式标，替换 WiFi 与状态栏图标，隐藏充电 / 热点 / USB 调试通知，手电筒忽略低电量 |
| **升级引导** | 「发现新版本」全屏引导页闪现即退 |
| **视频壁纸** | 高画质转码、去掉时长限制、循环播放 |

### 荣耀系应用（埋点与广告）

| 范围 | 你会得到什么 |
|---|---|
| **广告 SDK**（浏览器 / 日历 / 天气 / 游戏中心 / 优酷视频） | 信息流、横幅、开屏、插屏、激励广告全形态不再渲染 |
| **埋点上报**（日历 / 天气 / 浏览器 / 游戏中心 / 优酷视频 / 云音乐） | 上报链路在本地收口，**事件不落库、不出网** |
| **日历运营位** | 服务卡与资讯推荐位整链收口 |
| **搜狗键盘（荣耀版）** | 候选区功能免同意开启 |

### 指定第三方应用

| 应用 | 你会得到什么 |
|---|---|
| **小红书** | 解除保存限制并接管下载：保存视频零宿主提示、只弹一次模块下载卡，可批量下原图 |
| **京东** | 开屏广告跳过 |
| **MFA 身份验证器** | 开屏广告短路 |
| **成人高考题库** | 会员档位 / 有效期 / 特权等级显示为超级会员 |

## 兼容情况

下面是**真机实测**过的适配版本：

| 应用 | 适配版本 |
|---|---|
| 主题商店 | 20.1.63.305 |
| 包管理器 | 14.0.37.301 · 14.0.38.301 · 14.0.22.326 |
| 系统桌面 | 18.0.0.234 |
| 系统管家 | 17.0.0.414 |
| 小红书 | 9.50.0（9.49.1 亦兼容） |
| 系统更新 | 18.0.3.230 |
| 搜狗键盘 | 不限版本 |
| 系统设置 | 不限版本 |
| 短信 | 19.0.9.306 |
| 京东 / MFA 身份验证器 | 16.1.0 / 1.0.5 |
| 广告与埋点（日历 / 天气 / 浏览器 / 游戏中心 / 优酷 / 云音乐） | 不限版本 |

> 宿主版本比基线新时，日志里会多一行"宿主版本与基线不一致"的**提示**（不是故障）——功能照常生效。

## 常见问题

**Q：装上了没反应？**
A：① LSPosed 里模块开关是否打开、作用域是否勾上；② 目标应用是否**完全退出过**（后台不算，hook 只在进程启动时注入）；③ 面板首页是否显示"工作中"。

**Q：在哪看日志？**
A：LSPosed 的模块日志（不是 logcat）。每个目标启动时会有一行 `xxxHook[应用包名]: installed N/M`，N=M 就是全好。

**Q：会不会变砖 / 会不会留下痕迹？**
A：不会。全部改动只发生在进程内存里，**不写系统分区、不改应用数据、不装额外组件**，卸载模块即彻底还原。

**Q：某个功能我不想要怎么办？**
A：功能页里把对应开关关掉，再重启那个应用即可。不关就是全开。

**Q：为什么小红书保存后弹的是模块的卡片？**
A：这是设计如此 —— 接管下载后由模块确认下载哪些原图，**不弹宿主的分享引导**，也不会重复弹。

## 免责声明

本项目仅供个人学习与研究 Android 框架与逆向工程之用，请于 24 小时内自行删除，勿用于任何商业用途；
因使用本项目产生的任何问题由使用者自行承担。

## 开源许可

本项目按 **[GNU AGPL-3.0](LICENSE)** 发布（[NOTICE](NOTICE) 里是完整署名）。

其中包含第三方项目 **[XHS_Downloader_Android](https://github.com/NEORUAA/XHS_Downloader_Android)**（同为 AGPL-3.0）的小红书下载解析代码 —— 以 git submodule 方式引入、**原样未作任何修改**，仅 7 个文件参与编译。对应源码见 [本仓库](https://github.com/coderstory/HonorHook) 与 [上游对应 commit](https://github.com/NEORUAA/XHS_Downloader_Android/tree/d5c0070328f82ff5f748de5c0b826161d5eaca8b)。

荣耀 / Honor / MagicOS / YOYO 等商标属其各自权利人所有，本项目与之无隶属或背书关系。

---

### 开发者

- 全部文档索引：[`docs/README.md`](docs/README.md)
- 挂载点台账与真机判据快照：[`docs/hook-inventory-audit.md`](docs/hook-inventory-audit.md)
- 怎么写 hook：[`docs/hook-craft.md`](docs/hook-craft.md) · 怎么逆向：[`docs/reverse-craft.md`](docs/reverse-craft.md)
- 构建与设备操作一律走 `tools/dev.sh`（`tools/dev.sh help` 是唯一命令索引）
