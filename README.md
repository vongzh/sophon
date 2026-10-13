# 智子 (Sophon) - 车载四向行车记录与全维哨兵系统

> **“主不在乎，但智子在注视。”**  
> 面向智能座舱定制开发的新一代**四向全维行车记录、盲区辅助感知与智能哨兵系统**。

---

## 🌌 项目缘起与愿景

本项目吸取了前代开源行车记录仪项目（EVCam 等）在车载硬件环境下的宝贵实战踩坑经验，彻底重构了底层架构：

- **拒绝巨石架构**：摒弃上万行单一 Activity / Manager 的耦合设计，采用严格的**响应式分层架构（Clean Architecture）**。
- **全维无死角记录**：突破车机软硬件限制，支持前、后、左、右 **4 路摄像头并行硬件/软件编码录制**。
- **断电绝对容灾**：解决车载熄火突然断电造成的 MP4 文件损坏问题，引入安全切片与故障自愈机制。
- **深度车身融合**：打通车机 VHAL 信号总线，实现转向灯智能盲区补盲、开门侧后预警与 AVM 全景避让。
- **低功耗哨兵守护**：多级低功耗保活与唤醒管理，实现全天候驻车监控与远程千里眼告警。

---

## 🛠️ 核心架构演进 (Vs EVCam)

| 核心维度 | 前代项目痛点 (EVCam) | 智子 (Sophon) 架构演进 |
| :--- | :--- | :--- |
| **代码架构** | `MainActivity` 达 5600 行，业务逻辑与 UI 极度耦合 | **服务与 UI 彻底解耦**：核心录制引擎驻留独立 Service，UI 仅为展示客户端 |
| **相机管线** | 多路动态 Surface 频繁增减导致 HAL 死锁 | **统一 GPU 渲染管线**：每路 Camera 仅输出到固定的 SurfaceTexture，由 EGL 按需分发 |
| **断电保护** | 传统 MP4 容器在异常断电时 `moov` 未写入导致文件损坏 | **容灾容器与快刷机制**：支持 fMP4 / TS 流式切片，确保断电前最后一秒数据不丢失 |
| **时间对齐** | 各路线程独立时间戳导致文件时间差 1 秒 | **集中式时间戳分发器**：四路音视频帧与文件名严格在毫秒级纳秒级同步对齐 |
| **存储写入** | 直接写入外置 U 盘偶发 I/O 阻塞掉帧 | **环形缓冲 + 中转写入 (Relay Write)**：内存/内部高速存储作为缓冲，后台异步写入 U 盘 |
| **车身信号** | 信号捕获逻辑分散在多个 Service | **抽象车身信号总线 (IVehicleSignalBus)**：驱动适配器模式兼容 gRPC、ECARX、Logcat 等 |

---

## 🏗️ 整体分层设计

```mermaid
graph TD
    subgraph 车机硬件与系统层
        CAM[Camera2 API / HAL3]
        CAN[VHAL / gRPC / ECARX 信号总线]
        USB[外置 U 盘 / 车载存储]
    end

    subgraph 核心引擎层 (Sophon Core Service)
        HAL_MGR[多路摄像头采集管理器]
        GPU_PIPE[EGL / OpenGL 多路渲染分发管线]
        CODEC[MediaCodec 硬件编码引擎]
        MUXER[容灾复用封装引擎]
        STORAGE[环形缓冲与中转存储管理器]
        SIGNAL[车身信号事件总线]
    end

    subgraph 业务守护层
        SENTRY[哨兵与息屏监控守护]
        BLIND[转向灯/开门盲区悬浮服务]
        KEEPALIVE[车机多级防杀保活系统]
    end

    subgraph 交互与呈现层 (UI Client)
        MAIN_UI[主控与四路实时监看]
        PLAYBACK[四路同屏画中画同步回放]
        SETTINGS[车载参数与车型微调]
        REMOTE[远程千里眼与扫码快传]
    end

    CAM --> HAL_MGR
    HAL_MGR --> GPU_PIPE
    GPU_PIPE --> CODEC
    CODEC --> MUXER
    MUXER --> STORAGE
    STORAGE --> USB

    CAN --> SIGNAL
    SIGNAL --> BLIND
    SIGNAL --> SENTRY

    KEEPALIVE -.保活守护.-> 核心引擎层
    核心引擎层 <== IPC / Binder / Flow ==> 交互与呈现层
```

---

## 🗺️ 研发路线图 (Roadmap)

- [ ] **Phase 1: 基础设施与架构骨架**
  - [x] 项目脚手架与命名确立（Sophon / 智子）
  - [ ] Gradle 模块化配置与环境搭建
  - [ ] 核心架构设计文档与接口定义 (`IVehicleSignalBus`, `IRecordingEngine`)
- [ ] **Phase 2: 采集与编码流水线**
  - [ ] Camera2 多摄像头并发调度与栅栏状态机
  - [ ] OpenGL ES 2.0 / 3.0 多路帧分发与鱼眼矫正着色器
  - [ ] MediaCodec H.264/H.265 自适应编码与容灾封装
- [ ] **Phase 3: 车载信号与存储系统**
  - [ ] 环形缓存 + 异步 U 盘中转写入
  - [ ] 车身信号集成（转向灯、车门、车速、原厂全景避让）
  - [ ] 盲区浮窗与副屏投屏渲染
- [ ] **Phase 4: 交互与回放**
  - [ ] 四路毫秒级同步回放播放器
  - [ ] 智能座舱沉浸式 UI
  - [ ] 本地 Wi-Fi 扫码高速传输

---

## 📜 许可证

本项目遵循 [GPL-3.0](LICENSE) 开源协议。
