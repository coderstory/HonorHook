# pushagent 营销通知净化规格书（2026-10-24）

样本：system-files/pushagent.apk；工件 .work/pa/pa_dex.json（10000 类）。

## 判据定案（白盒一等判据，非渠道前缀猜测）
`NotifyMsgBean.getNotifyMsgType()==NotifyMsgType.MSG_TYPE_RECOMMEND`（枚举仅 4 值：RECOMMEND/NORMAL/GRS_CHANGE/NOTICE_CHANGE，服务端字段 notify_msg_type）——非营销绝不落 RECOMMEND，保守可靠。**严禁 LOW/HIGH 渠道名或包名黑名单匹配**（LOW=importance 档位，验证码也走 LOW=误杀红线）。
佐证可加严：getRealChannelId 含 "RECOMMEND" AND getRecmPkgName()!=null。

## 关键链
- 渠道命名：`com.hihonor.android.pushagent@<pkg>.<TYPE>.<n>pnc`（02=普通/03=headsup 代际；m81#a 构造 ChannelInfo）→ m81#c get-or-create（**单关渠道会被重建，须配套**）→ gp0#o 反射 createNotificationChannelsForPackage
- 下发：r81#e(Context,NotifyMsgBean) 唯一展示入口（'triggerNotification'）——**入口按判据早退即拦营销**，内部 'message abandoned' 路径同源不惊动上报
- 修复任务：tt#run（reportChannelSwitch/cleanExpiredChannels 周期重建上报）

## Hook 阶梯
P1 首发=仅 hook r81#e before 双条件 skip（单点最小面，服务通知零接触）；P2=+H4 tt#run（防周期回写）；P3=+H2（RECOMMEND 渠道 importance=NONE）+H3（阻代目标包建营销渠道，消灭设置页 26 个入口）。
P4/P5 不推荐/禁止（isRecommendMsg 恒 true / NMS 系统层按包拦）。

## 云回写
判据不经 settings，云回写不影响拦截有效性；05 号「隔天复查」保留为观测项。

## 判据行
`installed 26/26 渠道组营销档 | 验收=72h 营销零到达（'精彩推荐'=recommend_app_name 串）+验证码/系统推送 100% 正常+无 crash`
观测：dumpsys notification | grep pnc 每日快照。

## 红线
误杀验证码=只许正向白盒判据；fail-safe 放行（宁漏勿误杀）；pushagent 禁冻不动域（trs-drcn.push 维持 05 号红线）；混淆名漂移→字符串锚（'03pnc'/'triggerNotification'）重定位。
