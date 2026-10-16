# 极视 (Sophon) 开发者贡献指南

感谢您对极视 (Sophon) 项目的关注与支持！我们欢迎各类有助于提升系统稳定性、丰富功能特性或优化车规交互的贡献。

在向本项目提交代码或参与讨论前，请通读本指南。

---

## 🛑 最高准则：三大车载系统安全红线

车载嵌入式环境具有极高的安全性要求。任何 PR 在被合并前，必须严格通过以下三项铁律的审查（详见 [《车载系统安全与稳定性最高准则》](docs/SYSTEM_SAFETY_PRINCIPLES.md)）：

1. **零影响原厂车机 (Zero Impact on IVI)**：
   - 驾驶安全第一。原厂倒车影像与 360° AVM 环视具有最高抢占权；
   - 严禁任何可能阻塞 UI 线程或抢夺图形渲染管线的逻辑；
   - 异常必须在内部沙箱捕获并平滑降级，严禁导致车机系统服务连锁崩溃。
2. **零写内部闪存 (No USB, No Write to UFS/eMMC)**：
   - **绝对禁止**向车机内部闪存（如 `/data/data/...`、`/sdcard/`、`/storage/emulated/0/`）写入音视频缓存或分片；
   - 未检测到外置 USB 存储设备时，录制管线必须保持休眠；
   - 数据必须通过内存环形缓冲直接流式落盘到外置 U 盘。
3. **零篡改车身总线 (Zero Tampering Read-Only VHAL)**：
   - 车身总线访问仅限于只读订阅（Read-Only Observer）；
   - **绝对禁止**调用任何向下发指令或修改车辆电子电气状态的接口。

---

## 🛠️ 本地开发与构建

### 1. 开发环境要求
- **JDK**：OpenJDK 17 或以上版本（推荐使用 Android Studio 内置 JBR 17）
- **Android SDK**：API Level 35 (compileSdk 35, minSdk 28)
- **代码规范**：Kotlin 官方风格规范，推荐在 Android Studio 中启用 EditorConfig

### 2. 常用构建命令

```bash
# 检查与语法分析
./gradlew check

# 编译 Debug 测试包
./gradlew assembleDebug

# 编译 Release 发布包
./gradlew assembleRelease
```

---

## 📋 提交与分支规范

### 1. 分支策略
- `master`：稳定发布主分支，保证随时处于可交付与可编译状态；
- `feature/*`：新功能特性开发分支；
- `fix/*`：问题修复与缺陷排查分支。

### 2. Commit 规范 (Conventional Commits)
提交信息应遵循规范格式：`<type>(<scope>): <subject>`

- `feat`: 新增功能特性（如：`feat(codec): implement cabin audio recording`）
- `fix`: 修复缺陷或崩溃（如：`fix(overlay): ensure 50ms AVM yield timeout`）
- `refactor`: 代码重构（不改变功能与外部行为）
- `perf`: 性能调优（如降低内存占用或提升编码效率）
- `docs`: 文档变更或更新
- `style`: 代码格式或界面视觉样式调整
- `test`: 单元测试或集成测试变动

---

## 🔍 Pull Request 检查清单

在提交 PR 时，请在描述中确认以下内容：

- [ ] 代码能否在干净环境中通过 `./gradlew check` 与 `./gradlew assembleDebug`？
- [ ] 是否严守三大车载系统安全红线（无内部闪存写、无 VHAL 控制帧、无原厂 AVM 抢占）？
- [ ] 是否有充分的异常捕获与容灾兜底逻辑？
- [ ] 是否新增或同步更新了相关模块的文档与注释？
