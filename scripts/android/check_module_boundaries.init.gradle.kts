import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/** 只消费配置阶段的普通值；执行期不访问 Project、Configuration 或解析外部制品。 */
abstract class CheckModuleBoundaries : DefaultTask() {
    @get:Input
    abstract val modulePaths: SetProperty<String>

    @get:Input
    abstract val directDependencies: SetProperty<String>

    @get:Input
    abstract val platformViolations: ListProperty<String>

    @TaskAction
    fun verify() {
        val expectedModules = setOf(":app", ":contracts", ":core-bridge", ":vpn")
        val expectedDependencies = setOf(
            ":app|implementation|:contracts",
            ":app|liveImplementation|:vpn",
            ":app|androidTestLiveImplementation|:core-bridge",
            ":core-bridge|api|:contracts",
            ":vpn|api|:contracts",
            ":vpn|implementation|:core-bridge",
        )
        val modules = modulePaths.get()
        val dependencies = directDependencies.get()
        val errors = mutableListOf<String>()
        (expectedModules - modules).sorted().forEach { errors += "$it: 缺少实际模块" }
        (modules - expectedModules).sorted().forEach { errors += "$it: 模块尚未声明依赖边界" }
        (dependencies - expectedDependencies).sorted().forEach {
            errors += "${it.replace("|", " → ")}: 不允许的直接项目依赖或配置"
        }
        (expectedDependencies - dependencies).sorted().forEach {
            errors += "${it.replace("|", " → ")}: 缺少必要依赖"
        }
        errors += platformViolations.get()
        if (errors.isNotEmpty()) {
            throw GradleException("MODULE_BOUNDARY_FAILED:\n${errors.joinToString("\n")}")
        }
        logger.lifecycle("MODULE_BOUNDARY_OK: 直接项目依赖、配置归属与纯 JVM 边界通过")
    }
}

// init script 不引入 AGP 类路径；通过公开组件属性取得真正的测试 classpath。
val testedClasspaths = mutableSetOf<Configuration>()
gradle.beforeProject {
    val module = this
    listOf("com.android.application", "com.android.library").forEach { id ->
        module.pluginManager.withPlugin(id) {
            module.extensions.findByName("androidComponents")?.withGroovyBuilder {
                "onVariants"("selector"(), object : Action<Any> {
                    override fun execute(variant: Any) {
                        listOf("hostTests", "deviceTests").forEach { property ->
                            val tests = variant.withGroovyBuilder { getProperty(property) } as Map<*, *>
                            tests.values.filterNotNull().forEach { component ->
                                component.withGroovyBuilder {
                                    testedClasspaths += getProperty("compileConfiguration") as Configuration
                                    testedClasspaths += getProperty("runtimeConfiguration") as Configuration
                                }
                            }
                        }
                    }
                })
            }
        }
    }
}

// 先执行全部配置的延迟声明，再读取直接依赖，防止回调修改已经采集过的其他配置。
gradle.projectsEvaluated {
    val modules = rootProject.allprojects.filter { it != rootProject }
    modules.forEach { module ->
        module.configurations.forEach { configuration ->
            // 此视图执行 withDependencies/defaultDependencies，不解析依赖图或下载制品。
            configuration.incoming.dependencies.size
        }
    }
    val dependencies = mutableSetOf<String>()
    val platformErrors = mutableListOf<String>()
    val androidPlugins = listOf(
        "com.android.application", "com.android.library", "com.android.test", "com.android.dynamic-feature",
    )
    modules.forEach { module ->
        val pureJvm = module.path in setOf(":contracts", ":core-bridge")
        val androidModule = androidPlugins.any { module.pluginManager.hasPlugin(it) }
        if (pureJvm && !module.pluginManager.hasPlugin("java-library")) {
            platformErrors += "${module.path}: 必须使用 JVM library 插件"
        }
        if (pureJvm && androidModule) {
            platformErrors += "${module.path}: 纯 JVM 模块不能使用 Android 插件"
        }
        module.configurations.forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                if (dependency is ProjectDependency) {
                    // 只豁免 AGP 模型中的测试接线，手工可解析配置或仿测试名称均不获得豁免。
                    val testedComponent = configuration in testedClasspaths && dependency.path == module.path
                    if (!testedComponent) {
                        dependencies += "${module.path}|${configuration.name}|${dependency.path}"
                    }
                }
                if (pureJvm && dependency is ExternalModuleDependency) {
                    val group = dependency.group.orEmpty()
                    if (listOf("android", "androidx", "com.android", "com.google.android").any {
                        group == it || group.startsWith("$it.")
                    }) {
                        platformErrors += "${module.path} → ${configuration.name} → $group:${dependency.name}: 纯 JVM 模块不能依赖 Android 平台库"
                    }
                }
            }
        }
    }
    rootProject.tasks.register<CheckModuleBoundaries>("checkModuleBoundaries") {
        group = "verification"
        description = "从 Gradle 声明模型检查实际模块边界，不构建 native 或解析依赖制品"
        modulePaths.set(modules.map { it.path }.toSet())
        directDependencies.set(dependencies)
        platformViolations.set(platformErrors.sorted())
    }
}
