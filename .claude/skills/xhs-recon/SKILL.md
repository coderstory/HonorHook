---
name: xhs-recon
description: 小红书宿主的逆向分析与 hook 适配固定流程——从锚点登记表出发定位/验证挂载点，禁止从零重读反编译、禁止全量反编译、findings 必须落库。当需要适配小红书新版本、找新挂载点、排查保存/解锁类功能失效、或被要求分析 com.xingin.xhs 行为时使用。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-19'
  keywords:
  - 小红书
  - xhs
  - 逆向
  - 锚点
  - 适配
---

# xhs-recon：小红书逆向固定流程

> 存在原因：2026-10-19 前的会话反复重读同一份反编译产物——知识没有可查询落点。
> 本 skill 把流程钉死：**先查登记表，产物不重读，findings 必落库**。

## 铁律

1. **先读 `docs/reverse/xhs-anchors.md`**（锚点登记表，唯一事实源）。表里有的点，禁止重新逆向——直接 grep 锚行验证。
2. **禁全量反编译**（183MB/40min 起步且 9.50 一轮 67% 崩溃）。定向命令在 `docs/reverse/xhs.md` §八头部。
3. **findings 落 `docs/reverse/findings-<日期>-<主题>.md` 入库**。`.buildtmp/findings-*` 是临时区，收口后必须搬家，否则下个会话从零开始。
4. **每个结论带证据**：file:line + 锚行原文 + 验证版本。查不到写「产物内未见，需补 dex N」。
5. 真机判据用 `tools/dev.sh xhs-judge`（模块日志 + MediaStore + 落盘目录三件套）；模块日志模式查询用 `dev.sh modlog-grep <pattern>`。
   **坑**：lspd 日志按启动时刻轮转命名，当天日志可能在旧日期名文件里——永远 ls -t 取正在写的（两个子命令已内置）。

## 流程（新需求 / 版本适配 / 失效排查通用）

1. **定性**：症状属于哪条业务流？查 `docs/reverse/xhs.md` §九「保存业务图谱」（入口矩阵/三路保存/数据源/失败模式总表）。
2. **查表**：`xhs-anchors.md` 有无现成点 → 有则 grep 锚行验证（产物在 `.buildtmp/xhs950-partial/`，版本不匹配先定向反编译）。
3. **缺口才逆向**：新点先想清楚要挂「收口点」还是「散点」（`docs/hook-craft.md`）；锚行规范 = 方法体识别性最高的串常量/调用形。
4. **落 findings**：`docs/reverse/findings-<日期>-<主题>.md`，含触发点候选排序。
5. **收口**：锚点入 `xhs-anchors.md` 两处（表 + 验证版本列）；业务行为更新 xhs.md §九；代码注释只留 file:line 指针。
6. **真机验证**：`dev.sh xhs-judge` 判据 + 现象双确认后才算完。

## 已踩坑速查（防重蹈）

| 坑 | 教训 |
|---|---|
| `d49.q` 误绑假阳性 | 无串锚的方法，DexHint 必须给字段类型锚；宁 NOT_RESOLVED 不猜 |
| jadx 合成字段名 | fNNNNn 非 dex 真名，反射前查 apktool smali |
| MethodResolver 无锚点兜底 | pkg 非空即绕过闸门会误绑（2026-10-19 拍板：不改码，配锚点规避） |
| 日志文件轮转命名 | 查当天必须 ls -t（modlog-grep 已内置） |
| 宿主绕网路径 | 保存有三路（网络/缓存直拷/已存短路），挂响应的触发器对后两路全盲——挂点击/收口 |
| 假成功 toast | 宿主「已保存 N/N」不代表落盘；判据只认 MediaStore/文件 |
