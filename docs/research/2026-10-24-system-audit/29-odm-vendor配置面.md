# odm/vendor/product 配置面 + /system/magic（2026-10-24）

产物：system-files/odm-vendor-scan/（REPORT.md + extracts/ 9 个明文提取 + all-files.tsv 9203 文件清单）。

## Top 发现
1. **local.prop（/product_h/etc/prop/，16KB 明文，~370 条能力种子）**：版本伪装规则 ro.build.hide.matchers（11.0.0→10.0.1 替换表）+ ro.build.hide.settings（伪装 CPU/内存/分辨率表）；msc.camera.* 全套（livephoto=false/rawphoto.save=true/4k_limit=15）；msc.super_device.capability_level=1..22；cpu_info_display=骁龙8至尊版；fingerprintName=HONOR-Z252；projectmenu chargemaxlevel=60。**「这台机器到底支持什么」直接查此表**。
2. 云控种子明文：/product_h/etc/magicgate/CloudConfig.json——MagicGate is_use_vlm/llm=true（端侧大模型基线）；magicavatar device_series=Magic/bio_face=1；wallpapercapability AI 能力矩阵（subType=AAK）。
3. iaware 不在文件层：HAL 下发（vintf manifest iawareperf/iawareperfpolicy）；odm 唯一调度文件 sched_config_cust.xml 0640 挡住；hnpg CPU 策略与相机 featureConfig=**加密二进制**（核心策略密文）。
4. feature 表精确 diff 基础：odm 11 个 *.unavailable.xml 负声明 + vendor ~60 硬件 feature（vulkan1_3/nfc ese/hce/strongbox-hn）+ sku_kera/sun/tuna 传感器矩阵。
5. QTI perflock 明文：perf/{common,target}resourceconfigs.xml、LightningLaunchesFeature.xml（提频参数）、gamesched/gamesr（含原神超分模型 .bin）。
6. 白名单：tz_whitelist.json（TrustZone）、camera PrelaunchBlackList.xml（算法清单 FaceDetectionV3/CyberFocus）、qti_whitelist。
7. RRO：product 无独立 overlay 目录，由 preinstalled-packages-platform-overlays.xml 登记（targets 未拆，待挖）。

## /system/magic（837 文件，主会话补扫）
magic_cfg_policy.txt 揭示查找路径 /system/magic/{base,china,china_honor,oversea_honor}：
- base/：HYPERHOLD、accessibilitypolicy、aimeeting、animatoropt、appclipmanagerservice、appclone、appstartupopt、aps…
- china/：SOSALARMCONFIG、activityintercept、aidockbar、**antifraud**、antivirus、art、audio_config、**behaviorcontrollist**…
- china_honor/：parent_cust.cfg、prop、xml
**= 每特性一目录的运行时配置面，值得专项拆（队列中）**。

## 留待特权档位
~~/data/cust~~ **44 号已探：仅运营商 prop/xml 六文件，无 bootanimation——本报告「cust 为 bootanimation 优先位」结论不成立**（02 号开机动画路线彻底死透：boot 时序不可达 + cust 无内容）。
