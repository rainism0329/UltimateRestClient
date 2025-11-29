plugins {
    id("java")
    // Kotlin 版本保持新版即可
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    // IntelliJ Platform Gradle Plugin 2.x 是最新标准
    id("org.jetbrains.intellij.platform") version "2.2.0"
}

// 1. 设置正确的 Group ID
group = "com.phil.ultimate.rest.client"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    // 添加 IntelliJ 插件仓库
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // 2. 引入 Jackson，用于后续解析 Postman 的 JSON 导出文件
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    intellijPlatform {
        // 3. 将开发环境指定为 2024.2.4 (这是一个非常稳定的版本)
        // 不要使用 2025.1，那是 EAP 测试版，很不稳定且受众少
        create("IC", "2024.2.4")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // 4. *** 核心依赖 *** // 必须引入 Java 插件，否则无法解析代码（PSI）
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // 5. 调整兼容性范围
            // "242" 代表兼容 2024.2 及以上的所有版本
            // 这样既满足 JDK 21 的要求，又能让绝大多数用户用上
            sinceBuild = "242"

            // untilBuild 留空或设为通配符，表示兼容未来版本
            untilBuild = "251.*"
        }

        changeNotes = """
            <ul>
                <li>Initial release of Ultimate REST Client.</li>
                <li>Support Postman import and Spring Boot scanning.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // 6. 确保编译目标是 Java 21
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }
}