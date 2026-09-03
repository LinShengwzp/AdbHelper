修复 Task 0.1 的构建错误，只修复编译问题，不改变现有诊断目标和运行逻辑。

当前代码基于 commit 1ce7522。

已确认两个根因：

1. AdbManager.kt 中 java.lang.Process 不存在可直接编译调用的 pid()。
2. Task 0.1 越界修改了实验类 AdbManager 的构造函数和 get() API。

严格执行以下修改：

一、恢复 AdbManager 原始 API

将：

class AdbManager private constructor(context: Context)

恢复为：

class AdbManager private constructor()

将：

fun get(context: Context): AdbManager

恢复为：

fun get(): AdbManager

并恢复：

instance = AdbManager()

删除本次错误增加且未使用的：

private val keyDir
private val privateKeyFile
private val publicKeyFile

删除因此变为无用的 import：

java.security.KeyFactory
java.security.spec.PKCS8EncodedKeySpec
java.security.spec.X509EncodedKeySpec
kotlin.io.encoding.Base64

不要修改 AdbView.kt 中现有的 AdbManager.get() 调用。

不要实现 AdbManager 密钥持久化。
该实验类目前不属于 Task 0.1。

二、修复 Process PID 诊断

禁止直接调用：

process.pid()

因为当前 Android 编译环境无法解析该 API。

增加仅用于诊断的 best-effort helper：

private fun processPidForDiag(process: Process): String {
    return runCatching {
        process.javaClass.methods
            .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
            ?.invoke(process)
            ?.toString()
            ?: "unsupported"
    }.getOrElse {
        "unsupported:${it.javaClass.simpleName}"
    }
}

原来两处 process.pid() 均改为调用 processPidForDiag(process)。

PID 获取失败不得影响 adb Process，也不得抛出异常。

三、严格禁止

- 不修改 Compose UI
- 不修改 adb devices 轮询
- 不修复重复 startADBServer
- 不修改 waitForDeathAndReset 行为
- 不实现 AdbManager identity 持久化
- 不修改 libadb.so
- 不增加依赖
- 不顺手重构
- 不创建额外示例测试文件

四、验证

依次执行：

.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug

如果失败，停止并完整汇报错误，不继续猜测修改。

完成后汇报：
1. 修改文件
2. 两个构建错误分别如何修复
3. compileDebugKotlin 结果
4. assembleDebug 结果
5. 确认没有修改 Task 0.1 之外的运行逻辑
