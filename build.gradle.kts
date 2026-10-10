// 顶层构建脚本：只声明插件，不在根项目应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
