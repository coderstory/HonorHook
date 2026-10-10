# 怎么逆向分析（本项目实战流程）

> 只写"代码里读不到"的东西：流程判据、证据标准、工具调用入口、坑。
> 命令一律走 `tools/dev.sh`（**禁手拼 gradle/adb/apktool/jadx**）：`decompile <apk> [名]` / `jadx <apk> [名]` / `apktool` / `aapt2` / `apkinfo` / `ui` / `viewdump`；路径先跑 `dev.sh env`。
> smali 语法、jadx 读法、脚本细节见 skill `reverse-decompile` / `static-analysis`；hook 侧对应文档 [`hook-craft.md`](hook-craft.md)。

## 一、证据标准（本项目对"逆向结论"的要求）

| 要求 | 说明 |
|---|---|
| **file:line 实证** | 每条结论标 `类.java:行`（相对 jadx 产物根）；"讲得通"不算结论 |
| **消费点数** | 挂点前数调用方数量：0 = 死挂载；1 = 可用但要确认可达；多 = 优先（一处改全局生效） |
| **可达性** | 静态存在 ≠ 运行时被调用 —— 休眠链（定义了但零调用方）是本项目最常见的假点 |
| **版本对齐** | 结论必须标注逆向对象的确切版本（如 20.1.63.305）；版本漂移则整篇作废重做 |
| **不采信 README/宣传** | 同类插件 README 声称的能力可能已删；以基线 apk 实码为准（实证：某插件 README 的"降级安装"hook 在实码里不存在） |
| **设备实机优先** | 方法存在性以 `adb shell pm path` 拉的设备包 dex 为准（jadx 产物可能与设备包不一致） |

## 二、流程

1. **拿包**：`adb pull` / 已有 apk。加固壳需先脱壳（frida-dexdump / BlackDex / 内存 dump）；**DEX 虚拟化双壳**（`libDexHelper.so` + `libdexvmp.so`、classes.dex 整体加密、运行时纯内存解密无落盘）⇒ 静态 + 落盘双死路，只能运行时 dump。
2. **先看明文（零成本，最容易漏）**：`AndroidManifest.xml`（组件/权限/包名）+ `META-INF/xposed/`（`module.prop` / `java_init.list` / `scope.list` = 入口类 + 作用域）+ `res/values/strings.xml`（功能线索、URL 模板）。免反编译快速读：`unzip -p <apk> META-INF/xposed/module.prop`。
3. **规模与混淆评估**：类/方法/指令数（jadx 加载日志给）+ 混淆形态，决定后面用哪条路。
4. **定位 hook 点**（见 §三）。
5. **写进记录**：按 [`reverse/`](reverse/) 一份 app 一文的格式落盘（身份/基线/混淆性质/判定链与实证/边界/未定项）。

## 三、定位手段（按性价比排序）

| 手段 | 用法 | 备注 |
|---|---|---|
| 字符串 / 资源名反查 | 已知文案/URL/字段名 → 反查引用类与方法 | 文案是最稳的入口之一（混淆不改字符串） |
| 资源 id 按名查 | `getIdentifier(name,type,pkg)`；`aapt2 dump resources` 比对版本间平移 | **不要硬编码 id 数值**（逐版变） |
| 方法签名定位重载 | 打印类的全部方法签名，决定挂哪个重载 | 只短路 void 播放/执行类，**不碰 boolean/String 策略查询**（会改状态机） |
| 继承链 / 收口点 | 顺继承链找唯一公共出口 | `Dialog.show()` 覆盖全部原生弹窗 |
| 服务端载荷出口 | 找"响应 → 域模型"的唯一出口类方法 | 本项目的槽位/底 tab 裁剪都挂这里 |
| DexKit 特征查找 | 按参数类型序列 / 字符串引用 / 调用关系找被混淆的方法 | 需要**内容性锚点**；无锚点不硬兜 |
| 真机取证 | `dev.sh ui`（View 树含 text/content-desc）、`dev.sh viewdump`、`dev.sh log-hook` | 静态枚举不到服务端下发内容（H5/自绘/Lynx），只能运行时按结构+文案兜底 |

## 四、读法与兜底

- **smali 是根本能力，jadx 是加速器**：jadx 报 `finished with errors, count: N` 的失败类只有空壳 ⇒ 回 smali 读指令序列。控制流混淆（goto/switch 打散）时尤其如此。
- **libxposed 模块的 jadx 必不完整**：API 是 `compileOnly`（运行时由框架提供）⇒ 基类无方法体可读；以 smali 为准。
- 混淆类名（单字母 / 阿拉伯文）**不影响结论**：靠字符串常量 + 调用关系 + 入口类（入口类通常不混淆）顺藤摸瓜，建立重命名映射表另存。
- 字符串加密：先定位集中解密方法；简单 XOR 体系可离线批量还原（Rain 的实证与 43 处清单见 skill `reverse-decompile/references/rain-decrypted-strings.md`）。

## 五、版本适配

- **基线对齐**：真机 `dumpsys package <pkg> | grep versionName` vs 逆向基线 apk（`dev.sh apkinfo <apk>`）；不同 = 分析作废重做，不是调 hook 能救的。
- **特征检测优于版本号**：按方法签名/类存在性判断，不硬编码 SDK_INT —— 厂商 ROM 尤其如此。
- 适配回归：`python3 tools/adapt_new_version.py`（对基线反编译产物校验挂载点存活）。

## 六、坑清单

| 坑 | 表现 | 处置 |
|---|---|---|
| jadx 用 `java -jar` | 进 GUI、不产出 | 用 `dev.sh jadx`（CLI 入口） |
| 大包没加参数 | 慢/超时 | `JADX_OPTS="-j 4 --no-res"`；exit 1 + N 个 error 属正常 |
| `--no-res` 缺字符串 | 拿不到 `strings.xml` 里的 URL 模板 | 另跑 `dev.sh decompile`（资源） |
| 反编译产物当设备包 | 挂载点真机失败 | `pm path` 拉实机包，dex 检索确认 |
| 产物目录 | 已清理，需现场重建 | `dev.sh decompile/jadx <apk>` → `system-files/<名>-{apktool,jadx}` |
