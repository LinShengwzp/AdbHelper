任务：AdbHelper ADB 生命周期与 adbkey 诊断

仓库：LinShengwzp/AdbHelper

目标：
只增加最小诊断信息，用于确认旧项目中 ADB server 是否被重复启动/停止，
以及 ~/.android/adbkey 是否在运行过程中发生变化。

禁止：
1. 不重构 ADB、AdbViewModel、Compose 页面。
2. 不修复 startADBServer 重复调用。
3. 不修改 adb devices 轮询逻辑。
4. 不删除 AdbManager 或旧实验代码。
5. 不修改现有业务行为。
6. 不增加新依赖。
7. 不提交 Git commit。

实现要求：

1. 在 commons/AdbManager.kt 中的 class ADB 增加仅用于诊断的日志方法。

2. 每次进入以下方法时记录唯一递增 invocationId：
   - initServer()
   - adb()
   - waitForDeathAndReset()

3. initServer 日志至少包含：
   - invocationId
   - 当前线程名
   - _started.value
   - tryingToPair
   - shellProcess 是否为 null
   - autoShell

4. adb() 每次执行时记录：
   - invocationId
   - 完整命令
   - Process PID（API 支持时）
   - HOME
   - adbkey 路径

5. adbkey 固定检查：
   ${context.filesDir}/.android/adbkey
   ${context.filesDir}/.android/adbkey.pub

   每次 adb() 前后分别记录：
   - exists
   - size
   - lastModified
   - SHA-256
   如果不存在明确记录 MISSING。

6. adb() 的“after”诊断不要改变原有异步行为。
   不允许为了诊断给所有调用添加 waitFor()。
   可以在独立后台线程/协程中等待该 Process 结束后记录结果。

7. waitForDeathAndReset() 每次准备执行：
   adb kill-server
   之前打印明显日志：
   ADB_SERVER_KILL

   并包含：
   - watcherId
   - 当前线程
   - shellProcess 是否为空
   - _started
   - tryingToPair

8. AdbViewModel.startADBServer() 每次被调用时打印：
   START_ADB_SERVER_REQUEST
   并附带唯一 requestId。

9. 不修改 UI。
10. 确保项目能够正常编译。

完成后只汇报：
- 修改文件
- 新增诊断点
- 编译结果
- 未修改哪些运行逻辑
