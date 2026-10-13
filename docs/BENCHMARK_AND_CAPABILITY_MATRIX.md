# 极视 (Sophon) 对标 EVCC 与 盯盯车 全能力矩阵与实施蓝图

> **基准竞品**：  
> 1. **EVCC (`com.kooo.evcc`)**：吉利/领克/极氪车机中枢信号中间件 (提供 `IEvccApi` AIDL 服务，涵盖车身总线全量 Property 订阅与状态事件)  
> 2. **盯盯车 (`com.sunslin.surcamx` v1.4.6)**：FlymeAuto 头部商业四向行车记录与智能盲区应用  
> **核心目标**：**功能完全对标，体验无缝兼容，架构全面超越，严守三条安全红线**。

---

## 一、 核心能力对标与演进总表

| 功能维度 | 竞品 1：EVCC (`com.kooo.evcc`) | 竞品 2：盯盯车 (`surcamx`) | 极视 (Sophon) 全面对标与超越方案 |
| :--- | :--- | :--- | :--- |
| **车身信号总线接入** | 作为车端 AIDL Server，暴露 `IEvccApi`，提供 256+ 属性订阅 | 依赖 `EvccApiClient` 连接 EVCC，无 EVCC 时信号受限 | **三合一多源自适应适配器 (`VehicleSignalBus`)**：<br>1. 优先直连 `com.kooo.evcc` AIDL 服务<br>2. 缺省时自动回退至 Android 原生 `CarPropertyManager`<br>3. 兜底挂接 `ECARX gRPC` 与只读广播，**零依赖独立运行** |
| **四路视觉合流录制** | 无（仅负责信号中枢） | C++ Native (`libsurcam_recorder.so`) 合成单流，切片时可能轻微丢帧 | **OpenGL ES 3.0 统一渲染管线 + 0ms IDR 热切片**：<br>单实例 MediaCodec 硬件编码，连续运转永不停机，切片交接严格 0 毫秒丢帧 |
| **打灯盲区辅助与悬浮窗** | 广播转向灯、方向盘转角、车速信号 | 左右打灯悬浮窗、灵动岛拖动位置记忆、雨夜增强模式 | **全场景智能盲区感知微件**：<br>1. 转向灯触发浮窗（带 Brown-Conrady 广角矫正）<br>2. 雨夜绿波防眩光滤波增强<br>3. 灵动岛拖拽与位置记忆<br>4. **50ms 原厂 AVM 极速避让**（超越竞品防卡顿） |
| **智能哨兵模式** | 提供车辆电源、休眠唤醒数据 | 自制驻车监听，长持 WakeLock 增加小电瓶亏电风险 | **原厂协同，拒绝冗余**：<br>领克08 已深度集成硬件级原厂哨兵模式；Sophon 不做冗余驻车轮询，专注行车记录，避免 12V 电瓶亏电 |
| **远程千里眼与状态查询** | 接收远程控制指令 | 钉钉 Stream / 飞书 WebSocket / Telegram 远程控制 | **远程即时抓拍与健康看板 (`RemoteDispatcher`)**：<br>车主通过钉钉/飞书/Telegram 远程一键即时抓拍四路照片回传，并实时查询车速与 U 盘容量健康度 |
| **车外扬声器广播** | 暴露车外喊话器底层控制 API | 支持车外扬声器悬浮窗与实时麦克风喊话 | **车外安全广播喊话器**：<br>预置安全警示音播放 + 实时麦克风车外扩音 |
| **代码工程与稳定性** | AIDL 接口规范但缺少沙箱熔断保护 | 单体 `MainActivity` 接近 10,000 行，UI 崩溃拖累录制 | **双进程架构 + 纯 Kotlin Clean Architecture**：<br>UI 与录制核心物理进程隔离，沙箱级崩溃防扩散 |

---

## 二、 关键能力对标实施规范

### 1. 深度对标 EVCC 协议 (`EvccApiClient` 兼容接入)
EVCC 是车机环境中最强大的信号中枢。极视 (Sophon) 在 `com.vongz.sophon.core.vhal.evcc` 模块实现完整的 **只读客户端兼容层**：
- **服务绑定**：监听 `com.kooo.evcc.evccapi.BIND` 意图，建立 `IEvccApiSession`；
- **订阅属性清单**：
  * `TURN_SIGNAL`（转向灯）：驱动盲区补盲悬浮窗；
  * `GEAR_POSITION`（档位）：监控 R 挡触发 AVM 避让；
  * `VEHICLE_SPEED`（车速）：压制 OSD 水印与行程记录；
  * `STEERING_ANGLE`（方向盘转角）：辅助补盲大角度弯道触发；
  * `SEATBELT_STATE`（安全带）：未系提醒与辅助判断；
  * `DOOR_AJAR`（车门/后备箱）：开门防碰撞侧视画面唤醒；
  * `TIRE_PRESSURE_TEMP`（胎压胎温）：异常预警看板；
  * `CHARGING_STATUS`（充电状态）：哨兵低功耗策略切换；
  * `HARD_KEY_EVENT`（方向盘硬件按键）：长按抓拍/锁存；
- **合规边界**：**仅调用 `replaceSubscriptions` 与 `readProperty`，绝不调用 `writeProperty`！**

```mermaid
graph TD
    EVCC_SVC[系统已安装 EVCC 进程<br/>com.kooo.evcc]
    NATIVE_VHAL[Android 原生 CarPropertyManager]
    ECARX_GRPC[ECARX 本地 gRPC / Socket]
    
    subgraph SOPHON_BUS["Sophon 动态多源信号决策总线"]
        DISPATCHER[AdaptiveSignalSourceResolver]
        CACHE[VehicleSignalStateCache]
    end
    
    EVCC_SVC -->|1. 优先连接 (AIDL)| DISPATCHER
    NATIVE_VHAL -->|2. 无 EVCC 时无缝接管| DISPATCHER
    ECARX_GRPC -->|3. 专用车型底层补充| DISPATCHER
    
    DISPATCHER --> CACHE
    CACHE -->|只读 StateFlow| BLIND_OVERLAY[盲区补盲悬浮窗]
    CACHE -->|只读 StateFlow| SENTRY_MODE[智能哨兵守护]
    CACHE -->|只读 StateFlow| TRIP_TRACK[行程记录引擎]
```

---

### 2. 深度对标 盯盯车 交互与视觉管线

#### 2.1 灵动岛盲区悬浮窗 (Floating Blind Island)
- **自由拖拽与记忆**：悬浮窗可在领克 08 巨幅中控屏上任意拖动停靠，保存并记忆坐标（`IslandDragPosition`）；
- **雨夜增强模式 (Rainy Night Mode)**：
  针对雨雪夜间后视镜视线不清，在 OpenGL 片元着色器中加入自适应对比度增强与绿波降噪算法：
  $$\text{Color}_{\text{enhanced}} = \text{clamp}((\text{Color} - 0.5) \times \gamma + 0.5 + \text{GreenBoost}, 0.0, 1.0)$$
- **多条件触发**：
  * 左/右转向灯拨动；
  * 弯道方向盘转角超过 $45^\circ$（低速行车时自适应打开发卡弯补盲）；
  * 低速打双闪且挂 D 挡时窄道会车双开模式。

#### 2.2 速度染色行程轨迹地图 (Trip Review Map)
- 提取车速、经纬度与能耗，在本地 WebView 中渲染离线轨迹；
- 轨迹线根据行驶速度动态渐变染色：
  * $0 \sim 30\text{ km/h}$：绿色（畅通/慢速）；
  * $30 \sim 80\text{ km/h}$：青色/蓝色（城市道路）；
  * $80 \sim 120\text{ km/h}$：橙色/紫色（高速路段）。

#### 2.3 局域网免流量高速扫码互传 (LAN Share & Hotspot)
- 集成轻量化嵌入式 HTTP Server；
- 手机端无需安装 App，只需扫屏幕二维码连接车机 Wi-Fi（或车机热点），即可在手机浏览器中秒开播放或高速批量下载原画视频。

#### 2.4 车外扬声器广播 (Outside Speaker)
- 在中控界面提供快捷“喊话器”悬浮微件；
- 支持内置常用安全提醒语音（如“请注意倒车”、“请勿靠近车辆”），并支持麦克风实时直通推流。

---

## 三、 对标实现落地里程碑规划

1. **Sprint 1: EVCC AIDL 契约层与多源信号适配器集成**
   - 导入 `EvccApiContract`，编写纯只读 `EvccServiceClient`；
   - 补齐方向盘转角、胎压、车速、按键长按的完整只读事件分发；
2. **Sprint 2: 灵动岛盲区悬浮窗与雨夜滤镜着色器**
   - 实现悬浮窗自由拖拽位置持久化；
   - 落地 Brown-Conrady 广角去畸变 + 雨夜对比度增强 GLSL Shader；
   - 严格压测 50ms AVM 极速避让；
3. **Sprint 3: 本地免流局域网快传与行程轨迹引擎**
   - 搭建嵌入式 HTTP 文件服务与手机端扫码免流下载 H5；
   - 接入 GPS 与车速记录器，生成速度渐变染色轨迹；
4. **Sprint 4: 远程千里眼全平台分发中枢**
   - 统一抽象远程控制指令（钉钉、飞书、Telegram）。
