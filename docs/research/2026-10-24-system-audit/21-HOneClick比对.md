# HOneClick 签名比对与适配面（2026-10-24）

样本：system-files/honeclick-v1.0.40.apk（29.8MB，经 gh-proxy 镜像下载）。

## 签名
- 一键越狱 cert sha256：d9354e58…（CN=Android Debug，2026-10-03）；HOneClick：8a94093a…（2026-10-07）
- 密钥对不同=**不能建立同源证明**；但两者都是随机 debug 证书，也**不能反证非同一人**。结论：签名无效证据，HOneClick 的公开 release 节奏不可作一键越狱更新参照系。

## 水印
两者均无 QQ 群号/t.me/酷安链接/作者邮箱。HOneClick 有 `github.com/superboyyy` + **酷安 @熠寻**（作者 ID）；一键越狱完全匿名（日志目录 Download/一键越狱日志/），姿态更封闭。

## 适配面（关键结论）
- HOneClick：机型白名单 **WKL-AN20/80 硬编码 dex** + 内核指纹双白名单，无 profiles.json 外部配置；资产=ksu-template.ko + syms.txt(6MB) + t64560_v36/v42（**同为 CVE-2026-64560**，两个内核 build）+ 内置 KSU/Shizuku APK。→ **换固件后顶不上 AAK-AN00**（改配置不可能，重打包=改签名）
- 一键越狱：profiles.json **可扩展结构**（"新增机型只改此文件+payload 资产"）；现有条目：AAK-AN00 ×2 内核（verified=true）、**AAP-AN00/AAP-LGRP1 ×2（verified=false）**——作者在扩机型
- 正确路线：换固件后等一键越狱作者的 profiles 更新（新内核 Image sha256/地址重推），不借 HOneClick

## 对 OTA SOP 的更新
GhostLock 家族（CVE-2026-64560/43499）跨厂活跃 + 一键越狱作者在扩机型（AAP 已列）→ 新固件适配出现的概率评估上调；但 HOneClick 不可作备胎。07 号 SOP 铁律不变。
