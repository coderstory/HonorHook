# OTA 防线与固件窗口期 SOP（2026-10-24）

方法：设备实测（通道暂停前拿全）+ 命令包（网络故障未核验项如实标注）。

## OTA 组件
| 组件 | 角色 | 处置 |
|---|---|---|
| com.hihonor.ouc v18.0.3.230 | OTA 主客户端 + COTA 云配置 + YOYO 资源更新 + 字体/息屏资源安装（持 INSTALL_PACKAGES）+ 配件固件 + 推送 + 尝鲜报名 | 平时只关开关；**OTA 到来时才 pm disable-user 止血**（可逆，/data 持久） |
| com.hihonor.systemappsupdater | 系统应用更新 | 可单独 disable-user，低风险 |
| com.hihonor.android.upgradeguide | 升级向导 UI | 跟随 |
| com.google.android.configupdater | 谷歌 COTA | 已被停用 |

## 六键（现值基本已关，复核用）
global: hn_system_update_auto_download=0、ouc_first_version_auto_install=0、（保险）wifi_auto_update；secure: hn_system_night_upgrade_switch=0、hn_upgrade_remind=0；system: set_forceupdate_status=0。（sys_update_service_state 语义未确证勿盲改）
哨兵键（变化=有推送活动）：is_hota_search_success / ota_last_firmware_version_oobe / last_hota_version。每周一查。

## 四阶段 SOP
①平时：六键保持关值+每周哨兵自查+不点升级弹窗+工具渠道每周一查
②OTA 到来：新固件→工具有无新 profiles？内核>6.6.118 或 SP>2026-08-01 → 按已补处理；无适配=不升，临时 disable-user ouc 止血
③一定要升：预期临时 root 必失且大概率不可复得；保留=/data 层（settings 键、冻结表、模块文件）；丢失=root 运行态；升级前记录 getprop 全套+备份外设+关六键；升级后勿盲目烧开机预算，先等新 profiles
④永久失 root：/data 持久层依旧生效，设备回官方干净态

## 漏洞状态
本机内核 6.6.118-android15、SP 2026-08-01，UHID 路径未补（工具可用即证）。uhid.c 在 6.6.y 持续收加固，任何 >118 版本视为可能已修。profiles.json 适配表已核实（当前固件在列、verified=true）。
工具更新渠道：dex 无 URL，手动换 APK；核验命令包已存（api.github.com search aakan00 / honor temp root / magicos uhid；酷安换网查；TG 搜荣耀WIN root）。

## 附：临时 root 运行事实（一键越狱 v1.6 beta 解剖，18 号已修正漏洞编号）
**CVE-2026-64560（用户确认，payload 目录同名）**（dex 内含 futex 计时串，UHID 为读桥；同机型研究仓标注的 CVE-2026-43499 关系待考）→ pipe/fanout 堆喷（order-1 0x2000）→内核代码物理地址定向写→加载 KernelSU LKM；零分区写；读桥约 1/8 开机遇一次、一轮 24 次开机预算、单次 150s 硬超时；DIRTY=停手等 panic；重启即失。工具无公开分发渠道（封闭群组，见 18 号）。
