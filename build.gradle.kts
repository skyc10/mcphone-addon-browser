import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipFile

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm:9.8")
        classpath("org.ow2.asm:asm-commons:9.8")
    }
}

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// ===========================================================================
// [T13] GLFW stub 双名打包（org/lwjgl/glfw/GLFW + org/lwjglx/glfw/GLFW）
// ---------------------------------------------------------------------------
// 取证依据 roadmap-2026-09/41-glfw-namespace-forensics.md：
//   * CP natives 的 ScopedJNIClass 只按字面量 FindClass("org/lwjgl/glfw/GLFW")
//     —— 原 entry 必须保留；
//   * lwjgl3ify LwjglRedirectTransformer 在 LaunchClassLoader 类加载期把
//     org.cef.browser.CefBrowserOsr#keyEvent:511 的唯一运行期引用
//     invokestatic org/lwjgl/glfw/GLFW.glfwGetKeyScancode 重映射为
//     org/lwjglx/glfw/GLFW（org.lwjglx. 不在 LCL classLoaderExclusions 里），
//     全实例此前无任何 jar 提供该名 → LaunchClassLoader.findClass:326 抛
//     "Class bytes are null"，键盘（ByCode/字符）注入全灭。
//   * GLFW_PRESS/RELEASE、GLFW_KEY_*、GLFW_MOD_* 是编译期常量内联（javap 零
//     getstatic），不受本度量影响；本改动不触碰点击/滚轮逻辑。
// 实现：源码仍只有 src/main/java/org/lwjgl/glfw/GLFW.java 一份；对同一编译
// 产物用 ASM ClassRemapper 做 FQN 重映射（等价 shade relocate），以第二 entry
// 写进同一 jar——不是源文件复制，也不是裸字节拷贝（裸拷贝 this_class 仍指向
// org/lwjgl/glfw/GLFW，LCL defineClass 会抛 wrong name，不可行）。
// 防回归断言：reobfJar（发布 jar 的产出任务）结束前校验两个 entry 同时存在，
// 缺任一令 build 失败。
// 负控命令：./gradlew reobfJar -Pt13NegCtlDisableGlfwX=true（跳过第二 entry，
// 断言必须失败，exit != 0）。
//
// --- 本块由 t13 落盘（impl-glfw-stub-omen），勿无依据删除/禁用断言。 ---
// ===========================================================================

val t13NegCtlDisableGlfwX: Boolean = providers.gradleProperty("t13NegCtlDisableGlfwX").isPresent()

// 同一编译产物 → FQN 重映射副本（jar 打包第二名路径）

tasks.register<org.gradle.api.DefaultTask>("t13RemapGlfwStub") {
    dependsOn("compileJava")
    // 配置期就捕获全部 provider；执行期不回查任务容器/脚本对象
    val t13StubCompiled = layout.buildDirectory.file("classes/java/main/org/lwjgl/glfw/GLFW.class")
    val t13OutDir = layout.buildDirectory.dir("t13-glfwx-classes")
    inputs.file(t13StubCompiled)
    outputs.dir(t13OutDir)
    doLast {
        val compiled = t13StubCompiled.get().asFile
        check(compiled.isFile) {
            "[T13] stub 编译产物缺失：$compiled（compileJava 未产出 org/lwjgl/glfw/GLFW.class）"
        }
        val original = compiled.readBytes()
        val reader = ClassReader(original)
        val writer = ClassWriter(0)
        reader.accept(
            ClassRemapper(
                writer,
                object : Remapper() {
                    override fun map(internalName: String): String =
                        if (internalName == "org/lwjgl/glfw/GLFW") "org/lwjglx/glfw/GLFW"
                        else internalName
                },
            ),
            0,
        )
        val out = t13OutDir.get().file("org/lwjglx/glfw/GLFW.class").asFile
        out.parentFile.mkdirs()
        out.writeBytes(writer.toByteArray())
    }
}

val t13GlfwXClasses: org.gradle.api.provider.Provider<org.gradle.api.file.Directory> =
    layout.buildDirectory.dir("t13-glfwx-classes")

val t13RemapTask = tasks.named("t13RemapGlfwStub")

tasks.jar {
    if (!t13NegCtlDisableGlfwX) {
        dependsOn(t13RemapTask)
        from(t13GlfwXClasses)
    }
}

tasks.named("reobfJar") {
    doLast {
        val outJar = outputs.files.files.first {
            it.name.endsWith(".jar") && !it.name.endsWith("-dev.jar")
        }
        // 断言打开的路径以日志落明（不硬编码文件名，跟随任务实际输出）
        println("[T13] assertion opens: " + outJar.absolutePath)
        val need = listOf("org/lwjgl/glfw/GLFW.class", "org/lwjglx/glfw/GLFW.class")
        // 断言（无条件执行，负控通道也不禁用——负控会因缺第二 entry 在此失败）
        ZipFile(outJar).use { zf ->
            val present = zf.entries().toList().map { it.name }.toSet()
            val missing = need.filterNot { it in present }
            check(missing.isEmpty()) {
                "[T13] GLFW stub 双名打包断言失败：${outJar.name} 缺少 $missing ---- " +
                    "lwjgl3ify LwjglRedirectTransformer 重映射后的 org.lwjglx.glfw.GLFW 引用将无法解析，" +
                    "键盘注入全灭（见 roadmap-2026-09/41-glfw-namespace-forensics.md）"
            }
            println("[T13] ok: ${outJar.name} 双名 entry 齐备 " + need.joinToString())
        }
    }
}

