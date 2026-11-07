# 相机电影模式/LUT 开启规格书（2026-10-24，jadx 全源实证）

jadx 源码已解出 ~/jtmp/camout/sources。**修正 24/44 号：3dQingchunLutSupported=普通滤镜「青春」索引，非电影 LUT 门**；电影 LUT 真门=sdrMovieVideoEffectSupported/hdrMovieVideoEffectSupported int[]，HAL 仅 tag 定义未上报值→同光绘处境恒 false。

## 锚点签名
- `com.hihonor.camera2.utils.CustomConfigurationUtil`（未混淆）：`public static synchronized Object getSystemProperty(String,Object,Class)`（:858）→ 底层 SystemPropertiesEx（**prop 路径非 Settings**）；⚠️ 有静态缓存 systemPropertiesMaps（:2466）——**hook 3 参 public 入口绕过缓存**
- isMovieModeConfigurationSupported()：getSystemProperty("msc.camera.video_movie_mode",FALSE,Boolean)（:1637）
- isMovieModeSupported()=!isAfterVersionBaklava()&&上者（first_api_level≥36 拦，本机<36 不拦）
- CameraUtil.isSupportLutMovieMode=纯模式名白名单**不是门勿 hook**；isModeSupportLut 读 HAL NormalVideoSupportArriLut（未上报恒 false）

## 模式装配链
p8.C1788a.B()（initModeEntries 混淆）→ isMovieModeSupported() 才注册 MODE_NAME_MOVIE_VIDEO+3 子模式（SlowMotion/BokehBeauty/TimeLapseProLite[HAL 判]）；movie/Activator.start() 无条件注册插件 → **prop 一翻模式+3 子模式全量出现**。pro 参数页 LUT 项（K5/w.java:90）只看 isMovieModeConfigurationSupported=**LUT 唯一纯 prop 入口**（K5 纯 UI 显示）。

## P1 hook 方案（定稿）
- hook 点：getSystemProperty(String,Object,Class) static；before：key=="msc.camera.video_movie_mode"&&cls==Boolean → param[1]=TRUE 后 proceed（改默认值贴原语义）
- DexHint：类名直连；锚=类内字符串 "msc.camera.video_movie_mode"；instanceOnly=false
- 备选：isMovieModeSupported() 直接返 TRUE（零缓存问题，二选一）
- 姊妹门：provideo→TRUE 兜底；**livephoto/vlog 等不同 hook 逐门灰度**

## 验收（33 号衔接）
1. 快验：resetprop msc.camera.video_movie_mode true + force-stop 相机 → 更多列表出现「电影」（30 秒）
2. 长效：hook installed 1/1 + 可拍 24/30fps 不崩
3. **LUT 子选项预期不出现**（HAL int[] 缺失）；60fps 同硬门（movie60fpsSupported 未上报，最高 30fps）
失败分级：模式出现但黑屏/崩=HAL 录制档缺→降级只开 K5 LUT 显示关模式注册；相机崩=PROTECTIVE 回退+resetprop -d 清残留。

## 净结论
单点 hook 翻 video_movie_mode 一键真开电影模式（24/30fps 低风险）；LUT 滚动条/60fps=HAL 硬门不承诺。
