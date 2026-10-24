# hiview 遥测拦截规格书（2026-10-24，v18.0.0.001 android.uid.system）

工件：system-files/hiview.apk + work/hiview/{dexstrings.txt 70k 行,manifest_full.txt} + work/hiview-intercept-spec.md。

## 攻击面（比 05 号多 5 个）
四服务（LogCollect/Ex、MulDevLinkage、BleNotify）之外：HomeVisionLogReportService、ConfigUpdateJobService、WeeklyJobService(UeCollector)、HiviewPerfettoReportService、DiagKitService + 双 receiver + LogServiceContentProvider。

## 数据出口三条
a) okhttp+GRS（grs.hihonorcloud.com 路由→logservice-*.dt.hihonorcloud.com，上传目录 /data/log/LogService/uploading）
b) JNI：NativeWriter/sendRequestToNative→libhiview.so HiView::Report→**root 守护 /system/bin/hiview（init.svc）自行回传**
c) 内嵌 HiAnalytics（CrashHianalyticsData）
**分界：只 hook java 不够**——NativeCollection 原生路径+root 守护独立直传 → 05 号 hosts 块必须保留兜底；彻底断需 PLT hook libhiview.so（超 P2 范围不做）。

## Hook 点表（P2 not-proceed 首行 return）
H1 六个上报 Service.onStartCommand（返 START_NOT_STICKY）｜H2 okhttp 出口 host 精确匹配 logservice-*（**禁断 grs 域=红线**）｜H3 NativeWriter.*（掐 JNI 主通道）｜H4 UeCollector｜H5 双 receiver 仅上报类 action｜H6 HiAnalytics.onEvent 按开关。保留 DiagKitService/ConfigUpdateJobService。
**crash 保留开关**：honorhook.hiview.mode=block_all|keep_crash（默认 keep_crash：放行 CrashHianalyticsData+JAVA/NATIVE_CRASH/ANR/chr.minidump 白名单）。

## 验收
logservice-* 零流量≥24h + 无"hiview 已停止"弹窗 + 工程菜单本地出报告可用 + 蓝牙/多设备协同 48h 回归。
风险：全短路=售后检修降级（keep_crash 缓解）；onStartCommand 短路或触发框架重拉→降级跑空命令；OTA 换版本按类名校验。
