import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// ── 版本管理：git tag 是唯一版本源 ─────────────────────────────────────────
// 发版 = 打 tag `mobile_6.0.2`（scripts/release.ps1 负责校验与打 tag）。
// versionName / versionCode 在构建时由 git 推导，代码里不再手写版本号：
//   - HEAD 恰好打在 mobile_* tag 上 → 正式版：versionName = 6.0.2
//   - HEAD 在 tag 之后       → 开发版：versionName = 6.0.2-dev[-dirty]
//   - 仓库无匹配 tag / 无 git → 用 fallbackVersion 兜底
// versionCode = 主×1000 + 次×100 + 补丁（三位以内无碰撞，单调递增）。
// 注意：旧版本 6.0.1 装机时 versionCode 是 601，新公式 6.0.1 = 6001 > 601，升级不受影响。
// 手机 / 手表两个 APK 用同一版本；若将来上传 Google Play，手表 versionCode 需小于手机。

val fallbackVersion = listOf(6, 0, 1)

// 顶层函数/匿名函数没有 Gradle 的 Project 接收器，这里用纯 JDK 的 ProcessBuilder 跑 git。
val runGit = fun(args: List<String>): String? = try {
    val stdout = ByteArrayOutputStream()
    val proc = ProcessBuilder(listOf("git") + args)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    proc.inputStream.copyTo(stdout)
    if (proc.waitFor() == 0) stdout.toString().trim() else null
} catch (_: Exception) {
    null
}

// 发版 tag 认 mobile_*（新约定）与 PUKEKO_*（历史遗留，仅作兜底）；桌面版用 v*，二者互不干扰。
// 前缀式解析，兼容 describe 输出的 "mobile_6.0.1-24-g7fa3f81" 这类后缀。
val semverRegex = Regex("""^(?:[A-Za-z]+[_-])?v?(\d+)\.(\d+)\.(\d+)""")

fun parseSemver(text: String?): Triple<Int, Int, Int>? {
    if (text.isNullOrBlank()) return null
    val m = semverRegex.find(text.trim()) ?: return null
    return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
}

val exactTag = runGit(listOf("describe", "--tags", "--match", "mobile_*", "--match", "PUKEKO_*", "--exact-match"))
val nearestTag = runGit(listOf("describe", "--tags", "--match", "mobile_*", "--match", "PUKEKO_*", "--always"))
val dirtyTree = runGit(listOf("status", "--porcelain")).orEmpty().isNotBlank()

val exactVersion = parseSemver(exactTag)
val nearestVersion = parseSemver(nearestTag)
val (verMajor, verMinor, verPatch) = exactVersion ?: nearestVersion
    ?: Triple(fallbackVersion[0], fallbackVersion[1], fallbackVersion[2])

val appVersionCode = verMajor * 1000 + verMinor * 100 + verPatch
val appVersionName = when {
    exactVersion != null -> "$verMajor.$verMinor.$verPatch"
    nearestVersion != null -> "$verMajor.$verMinor.$verPatch-dev" + (if (dirtyTree) "-dirty" else "")
    else -> "$verMajor.$verMinor.$verPatch-dev"
}

// 供 :app / :wear 的 defaultConfig 读取（根工程先于子工程求值，顺序有保证）
extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode

tasks.register("printVersion") {
    group = "help"
    description = "打印由 git tag 推导出的 versionName / versionCode"
    doLast {
        println("versionName = $appVersionName")
        println("versionCode = $appVersionCode")
    }
}
