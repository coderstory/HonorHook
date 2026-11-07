# ouc OTA 拦截规格书（2026-10-24，dex 证据核验）

样本：system-files/ouc.apk（29.1MB）；工件 oucwork/{dex.json,dexdump.py,ouc-ota-intercept-spec.md}（自研解析器 19,227 类索引，可复用于漂移复核）。

## 职责分叉（「只拦固件」可精确成立）
AutoCheckService 通知层即分叉：R→showHotaNewVersion（固件）/P→showCotaNewVersion/S→showMicroPatch/Q→enterprise，方法互不共用；COTA 下载/安装=CotaService/Cota*Receiver，与 FirmwareAutoDownloadReceiver/WifiAutoDownloadManageService/FirmwareInstallReceiver 物理不同类；配件=accessory.* 独立。

## 版本锚点
XmlManager$NewVersionInfoXml$Component（162 方法 bean 未混淆）：getVersionName/getVersionId/getHotaPackageType/getAutoInstall——版本串唯一权威出口。**XML 应答无 kernel/SP 字段→拒升线须配版本表映射**（versionId→内核/SP，随工具社区 profiles 维护；未知版本默认拦+日志 UNKNOWN）。

## Hook 规格
- **P1 主路**：Component 所属 getComponentList() after-return 删除命中拒升线组件→通知/下载/UI/静默安装整链自然失明，COTA/资源/配件零影响
- **P2 兜底**：FirmwareAutoDownloadReceiver.onReceive / WifiAutoDownloadManageService.onStartCommand / FirmwareInstallReceiver.onReceive 命中即 return（不碰 Cota*）
- 判据行：`OUC-GUARD installed=N match=M verdict=BLOCK fw=vX.Y.Z id=... rule=kernel|sp|unknown`
- 验收：推送时 BLOCK 日志+设置页无固件提示+COTA/资源照常

## 风险
packageType 数值语义需实测定稿（勿盲猜）；锚点刻意只依赖未混淆类名（小版本稳）；ouc 自更新→重跑 dexdump.py 核对→漂移则回退 disable-user SOP。
