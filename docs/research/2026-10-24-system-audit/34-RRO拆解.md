# RRO overlay 拆解（2026-10-24）

产物：system-files/odm-vendor-scan/{rro-overlay-report.md, preinstalled-packages-platform-overlays.xml}。
修正 29/31 号：overlay 实际在 /product_h/overlay、/system/magic/base/overlay、/preas/china/overlay、/system_ext/overlay 共 12 APK。登记文件 preinstalled-*.xml 是 AOSP 原版非荣耀分化点。

## overlay 全表（→target→分化内容）
1. **frameworkres.overlay(/product_h)→android：核心门控**——默认浏览器绑定、AOD dozeComponent、语音助手包名、IMS、挖孔形状 config_mainBuiltInDisplayCutout、圆角半径、状态栏高度、VoLTE/VT/WFC、位置服务包名
2. frameworkreshnext.overlay→androidhnext：MagicOS 扩展资源（hn_transparent_material、动态壁纸窗口模糊、launcher 透明度、AI 键）
3. **systemui.overlay→systemui**：状态栏 padding、**灵动胶囊 capsule_* 时长**、g2 圆角、控制中心 custom_config、AOD 通知区
4. newcall.overlay→com.newcall：AR 通话/小程序/屏幕共享包名绑定
5. credentialmanager.overlay→credentialmanager（static）
6. wifi.resources.overlay：**WiFi 能力位**（5GHz/SAE/MAC 随机化/软AP 11ax/MultiSta）
7-8. networkstack/tethering.overlay→Google 组件
9. **gmsconfig.common(/preas 区域分区)→android：区域门控**（位置/凭据/IME 白名单、managed device 要求）
10-12. navbar 三件套（gestural/hide[含 hn 导航交互模式]/threebutton，运行时 RRO priority=1；其余 9 个 isStatic=true）

## 结论
- 机型/区域差异=product_h 三静态 overlay + preas 区域 overlay 承载：默认应用绑定、屏形几何、WiFi 能力、GMS 白名单——**特性门控的 config 层**，与 pkg 门控互补
- **自制 RRO 非特权不可行**：cmd overlay 需特权；target=android 运行时 overlay 要平台签名/预装分区
- 可行路径=特权通道注入 runtime overlay 覆盖 frameworkResOverlay 的 string/bool（重绑 dozeComponent/默认浏览器等，资源 ID 可构造）——留作会话层定制备选
