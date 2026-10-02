# 白茶读屏 ProGuard 规则

# 无障碍服务不能被混淆（系统通过反射实例化）
-keep class com.baicha.reader.core.ReaderAccessibilityService { *; }

# 保留 Kotlin 元数据
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
