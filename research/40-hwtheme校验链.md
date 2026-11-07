# hwtheme 校验链拆解（2026-10-24，翻案 27 号）

样本：system-files/hwtheme-service/。**重大更正**：HwThemeManagerEx 不是 binder 服务（servicemanager 无 hwthememanager），是 framework.jar boot-classpath 静态门面；真实链路=调用方进程门面→HwPackageManager binder→**system_server 的 HwPackageManagerServiceExImpl/HwThemeInstaller#pmInstallTheme**。

## 核心结论：pm 安装层无任何签名校验
- 唯一门=checkCallingOrSelfPermission(WRITE_MEDIA_STORAGE 或 SET_WALLPAPER_COMPONENT)，均 signature|privileged
- checkInstallThemePath 任何路径全放行；checkThemeFile 仅存在+≤300MB+zip 完整性；全程无 Certificate/Signature/Digest
- overlay 完整性校验 verifyOverlayTheme（manifest.mf 的 name/size/lastModified 三元组）**运行在调用方进程**——可直接 hook，且 createThemeVerifyFile 就是官方生成器（仅需 MANAGE_EXTERNAL_STORAGE）
- **hwt=明文 zip 无加密容器**；大雪编辑器产物可直接吃

## 三路判定
a) hook 服务端校验——不存在此方法；system_server hook 高危不必要 ❌
b) 伪造签名——不存在签名校验，无需伪造 ❌（不需要）
c) **官方后门=权限门本身：让特权进程（thememanager，LSPosed hook 低风险）替调 `HwThemeManagerEx.installHwTheme(path)`（或反射，app 内 w5.java 有先例）** ✅
付费/试用分叉：system 侧仅 ThemeProvider DB attribute=trial 影响解压目录不影响安装；**绕付费仍走 app 侧（27 号 empowerToFile/vipState 锚点成立），导入免校验走权限调用——两件事正交**。

## 观测面
无 dumpsys；主题状态=content query `content://com.hihonor.systemserver.theme.ThemeProvider/themes`（type/id/attribute=trial/title）；`dumpsys package permission android.permission.SET_WALLPAPER_COMPONENT` 查谁有调用权。

## 锚点表
HwThemeManagerEx#installHwTheme(String,boolean)（framework-magic，公开静态门面）→ HwThemeManagerImpl#pmInstallHwTheme → HwPackageManager#pmInstallHwTheme（binder 出口）→ system_server：HwPackageManagerServiceExImpl#pmInstallHwTheme → **HwThemeInstaller#pmInstallTheme（唯一权限门 synchronized）**；checkThemeFile/checkInstallThemePath；verifyOverlayTheme/createThemeVerifyFile（客户端）。

## 对路线图影响
「自制/第三方主题导入」从不可行 → **v1.4.x 可行项**（hook thememanager 调门面 + app 侧付费锚点 + 大雪编辑器生态），P3 清单新增一行。
