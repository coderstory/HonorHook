# /system/magic 配置面（2026-10-24，837 文件全扫）

产物：system-files/magic-scan/{all-files.txt, excerpts.md}。结构：base 636（全机型特性）/china 177（国内安全合规）/china_honor 22（最终定制层）；magic_cfg_version=40.00.000。

## 重点深读
- **behaviorcontrollist/BehaviorControlList.json（1.16MB，10550 包名）**：每包 {riskLevel,controlDate,addPath,removePath}；middle 9929/high 505/low 116——国行出厂预标上万三方 app 行为管控；仅 4 包差异化（美团/剪映/快手系 low）。控制码语义在 behaviorcontrolpolicy（riskLevel→21xxx/22xxx/23xxx/24xxx 码表+AI 分支 2300007/8）。云端才是主更新源。
- **antifraud**：FraudMonitorPolicy 监控周期与阈值（riskAppCount≥2/通话 70/SIM 换 7/辅助 14）、96110、拦截分级；regular-expression.json 诈骗话术正则（"薇/扣"变体字）；antivirus/VirusBWList.xml=**白名单包名+签名 MD5**（拼多多/mms 在列）。仅了解未绕。
- **appclone/app_clone_config.xml**：supportClonedApp ~45 包白名单（微信/QQ/淘宝/抖音/国际系）；forbidClonedApp=反诈中心/政务/银行类；forbidAllApps=false；总开关 msc.config.support_clone_app=true——**纯静态无签名字段，可改**（root 后）。
- HYPERHOLD：空壳（真开关在 HWOUC xml）。
- **appstartupopt/hn_startup_opt_features.xml**：按包挂 early_stop/bg_relaunch/pause_parallel/fast_cached_adj（抖音/快手/微信/番茄）；single_app_mode 参数（kill 间隔 5s/cached 保留 3）；StartupExempt 荣耀自家豁免清单——**国行自动查杀策略明文**。
- **activityintercept/ActivityStartInterceptPolicy.json**：coolingTime=30s；whiteList 9 包；interceptList 100+（后台拉活 Activity 冷却清单）——与 behaviorcontrollist 互补（码 vs Activity）。
- aidockbar/AIServiceConfig.json：服务条目内嵌 static_check 直接读 msc prop 作门控（ai_caption/call_subtitle/magictranslate）——**msc prop→功能门控的显式映射证据**。
- trustspace/PaymentMonitorConfig.json：支付入口 Activity 粒度清单（WXPayEntryActivity/MspContainerActivity）；**AccessibilityTrustedConfig+accessibilitypolicy=无障碍信任白名单（包名+证书 SHA256，荣耀全家桶共用平台签名）→ hook/自动化被检的判据来源**。
- emcom：handoff 证书映射（多屏协同校验）+155 per-PLMN 运营商 xml；appclipmanagerservice：快应用同步 1440min/老化 45d+110 图标。

## 架构结论（交叉验证）
magic 配置≠msc prop 下游，是并列面：**msc prop=能力开关/硬件参数；magic xml/json=按包名策略/白名单**；aidockbar 反把 msc prop 当静态判据（prop 上游、策略数据在 magic）。magic 3 份 local.prop 是 odm local.prop（557 行超集）的分层投影；运行时值已被云控演化（call_subtitle 0→62）。CloudConfig.json 命名=云控下发出厂种子，开机后 /data 侧才是最终值。

## 可利用点（未实施）
①behaviorcontrollist 明文→离线判任意包控制码 ②appclone 白名单静态可改 ③activityintercept 白名单/30s 冷却=hook 判据 ④VirusBWList/AccessibilityTrusted 签名摘要→平台签名与白名单准入推断 ⑤FraudMonitor 阈值=防诈触发判据 ⑥static_check=prop→门控映射表
