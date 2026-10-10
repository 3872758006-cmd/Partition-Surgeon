# 还原点元数据用 org.json 反射字段，保持模型类不被裁掉
-keepclassmembers class dev.surgeon.partition.model.** { *; }

# Shell 输出解析依赖 token 字符串，别被混淆进常量池优化里
-keep class dev.surgeon.partition.core.shell.** { *; }

-keepattributes Signature
-keepattributes *Annotation*
