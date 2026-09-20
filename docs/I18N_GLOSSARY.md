# OverDrive 简体中文术语表

本文件是简体中文（zh-CN）翻译的统一依据。翻译与审校时请以此为准，避免出现同一英文术语多种中文译法。

配套的自动校验：`scripts/i18n_check.py`（由 `.github/workflows/i18n-check.yml` 在每次 push / PR 时运行）。

---

## 1. 不译（保持原样）

品牌、协议、产品与文件名一律保留英文：

OverDrive、BYD、DiLink、MQTT、Tailscale、Tailnet、MagicDNS、Cloudflare、Cloudflared、Zrok、
Home Assistant、ABRP、Telegram、Discord、Ko-fi、GitHub、Docker、HiveMQ、AWS IoT、
RoadSense、RoadSense 相关功能名、SD 卡、USB、Wi‑Fi、ADB、APK、PWA、HAL、AVM、CAN、GPS、
SOH、SOC、VTOL、LFP、ESP、BSD、OEM、PIN、VIN、PIN 码中的 "PIN" 保留大写。

## 2. 汽车术语

| English | 简体中文 | 说明 |
| --- | --- | --- |
| head unit | 车机 | 不用「主机」「头部单位」 |
| dashcam / DVR | 行车记录仪 | 不用「行车记录器」「记录器」 |
| camera | 摄像头 | 不用「相机」「摄影机」「摄影機」 |
| sentry (mode) | 驻车哨兵 | 不用「哨兵监控」「守卫」 |
| surveillance | 监控 | 与「驻车哨兵」区分：监控是泛称 |
| recording (名词) | 录像 | 动词场景用「录制」 |
| clip | 视频片段 | 不用「短片」「剪辑」 |
| trip | 行程 | 不用「旅程」「旅途」 |
| routes | 行车路线 | 不用「行驶路线」 |
| gear | 挡位 | 不用「齿轮」「变速」 |
| calibration | 标定 | 汽车行业术语，不用「校准」 |
| hazard lights | 双闪 | 不用「危险灯」 |
| blind spot | 盲区 | |
| VTOL | 对外放电 | |
| regen / regenerative braking | 动能回收 | |
| state of health | 电池健康度 (SOH) | 首次出现时括注缩写 |
| ACC off / power down | 熄火下电 | |
| firmware | 固件 | 不用「韧体」 |
| odometer | 总里程 | |

## 3. 技术与网络术语

| English | 简体中文 | 说明 |
| --- | --- | --- |
| daemon | 守护进程 | 不用「后台服务」「常驻程序」「戴蒙」 |
| broker | 代理服务器 | 短标签可简化为「代理」 |
| tunnel | 隧道 | 内网穿透场景可用「内网穿透」 |
| tailnet | Tailnet | 保留英文 |
| heartbeat | 心跳 | |
| debounce | 防抖 | |
| fallback | 兜底 / 降级 | 按语境二选一，同一界面内保持一致 |
| cadence | 频率 | 不用「节奏」 |
| capture | 采集 | 不用「撷取」 |
| threshold | 阈值 | 不用「门槛」「临界值」 |
| field | 字段 | 不用「栏位」 |
| apply | 应用 | 不用「套用」 |
| restore | 恢复 | 不用「还原」 |
| default | 默认 | 不用「预设」 |
| settings | 设置 | 不用「设定」 |
| configuration | 配置 | 与 settings 区分 |
| device | 设备 | 不用「装置」 |
| storage | 存储 | 不用「储存」 |
| detection / detect | 检测 | 不用「侦测」「探测」 |
| log | 日志 | 不用「记录」「纪录」 |
| endpoint | 端点 | |
| channel (camera) | 通道 | 与网络 tunnel 区分，不要互相替换 |

## 4. 港台用词对照（必须使用大陆简体写法）

| 港台写法 | 大陆简体 |
| --- | --- |
| 侦测 | 检测 |
| 储存 | 存储 |
| 预设 | 默认 |
| 设定 / 设定档 | 设置 / 配置文件 |
| 萤幕、視窗 | 屏幕、窗口 |
| 装置 | 设备 |
| 传送 | 发送 |
| 复原、还原 | 恢复 |
| 资料 | 数据 |
| 應用程（應用程式） | 应用 |
| 资讯 | 信息 |
| 档案 | 文件 |
| 支援 | 支持 |
| 运作 | 运行 |
| 摄影机 | 摄像头 |
| 录影 | 录像 |
| 程式 | 程序 |
| 撷取 | 采集 |
| 门槛 | 阈值 |
| 栏位 | 字段 |
| 套用 | 应用 |
| 连结、網路 | 链接、网络 |
| 公尺、公升 | 米、升 |
| 「」引号 | “” |

繁体字（級、刪、節、係…）一律转简体。

## 5. 语气与排版

- 第二人称统一用 **你**，不用「您」。
- 中文标点用全角：：，。；？！（）、——
- 中英文之间加空格：`启用 Tailscale 代理`，不加空格：`守护进程重启`
- 占位符必须与英文完全一致，不得翻译或改写：
  - Android：`%1$s`、`%2$d`
  - Web：`{name}`、`{total}`
  - 自动化变量：`${var:NAME}`、`${signal:TYPE}`
  - 命令示例参数：`<name>`、`<PIN>`（不要写成 `<名称>`、`<PIN码>`）
- HTML / Markdown 标签（`<b>`、`*加粗*`）必须与英文成对出现。
- 复数资源（plurals）必须补齐全部 quantity，中文缺项会静默回退英文。

## 6. 校验

```bash
python3 scripts/i18n_check.py            # 结构性问题 → 非零退出
python3 scripts/i18n_check.py --strict   # 警告也视为失败
```

- 错误（阻断）：缺译、plurals quantity 不一致、占位符不一致或被翻译。
- 警告（不阻断）：陈旧键、繁体/港台用词残留、与英文完全相同但可能需本地化的条目、values-zh 与 values-zh-rCN 不同步。
