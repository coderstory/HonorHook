# AOD 应用深挖（2026-10-24，HnAOD 18.0.0.066）

样本：system-files/aod-magicos11.apk（40.2MB）。smali 级 dump+资源表。

## 样式引擎（三层）
A. theme/layers：Trigger/Action 体系（anologclock/chinaclock 农历/digitalclock/forestclock/patternclock…），主题包 aod_themes.xml（vmall/ecota/pcota 格式），BootReceiver 按 ecota/pcota_version_aod 比对 OTA 前后恢复样式
B. keyguardengine 时钟 JSON（=14 号引擎，style_1..15+double，aodScaleRatio 缩放；11/12/14/15 走 PersonalClockViewContainer）
C. **clockplugin widget 系统**：KeyguardWidget 基类→Simple{Hour/Date/Lunar/Battery/Notifications/**AppWidgetWidget(可嵌三方 App Widget!)**}/Composite + gridPosition 网格；viewrenderplugin ElementConfig(JSON 元素组)+colorpick+blur

内置彩蛋样式：孟菲斯/小鹿(可触摸)/北极熊(随电量)/二次元空间/莫兰迪。

## 全屏 AOD 激活路径（定案）
支持位 msc.aod.aod_support_feature bit2(4)=全屏，**本机=7 已置位**；BootReceiver 只在 enable_full_screen_AOD== -1（缺省）时自动写 1——本机非缺省未触发。**激活=`settings put secure aod_switch 1; settings put secure enable_full_screen_AOD 1`**（需 root/shell 写 secure；静态结论待实测）。

## 显示调度
模式=全天/定时/智能/轻触(默认 tap_to_show)+7T；注视亮屏=support_eyes_ability+msc.swing.face_detect_show_aod_enabled（awareness fence）；时长最终下发 sensorhub（SensorHubAodControl）；分钟刷新=TIME_TICK alarm。

## 三方内容通道
- 通知上 AOD：绑定 SystemUI HwSystemuiNotificationManagerService（三方通知可显示）
- 音乐/歌词：MediaPlayerMonitor+findCurrentMediaPlayPackage **白名单**（mediacenter/gmusic…可 hook 扩任意播放器）；真歌词需 SHOW_LYRICS 系统权限（最大工作量）
- 更新通道：AodThemeService（AOD_THEME_EXTRA 主题商店推样式包）+步数 HealthBroadcastReceiver(awareness currentStepCount)

## 可玩面（可行路径排序）
1. 全屏 AOD（2 条 settings，极低）2. 第三方时钟=keyguard_clock_style.json 自定义（纯主题包格式无权限墙，中）3. 步数=样式含步数 widget 或 hook clockplugin（中）4. 换隐藏样式=aod_themes.xml style 存储键（中）5. 三方 AppWidget 上 AOD=AppWidgetWidget+gridPosition（中高，需验证白名单）6. 自制样式包=vmall 格式逆向（中高）7. 歌词=InfoView 白名单 hook+权限伪造（高）
琉璃/琉光资源不在本包（hntheme 侧）。
