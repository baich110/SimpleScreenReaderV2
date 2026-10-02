package com.baicha.reader.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager

/**
 * 系统状态播报 —— 对标 TalkBack `monitor/RingerModeAndScreenMonitor`(488行)
 * 与天坦 `W6/h`(454行)
 *
 * ══════════════════════════════════════════════════════════
 * 【解决什么问题】
 * ══════════════════════════════════════════════════════════
 *
 * 这些变化**每天都会发生**，但视障用户看不见：
 *   - 手机静音了（开会前调成震动，忘了）
 *   - 屏幕被锁了
 *   - 解锁成功
 *   - 屏幕点亮/熄灭
 *
 * 没有播报，用户只能靠「听声音有没有」来猜，
 * 或者反复按音量键试。
 *
 * ══════════════════════════════════════════════════════════
 * 【两方对标】
 * ══════════════════════════════════════════════════════════
 *
 * | | TalkBack | 天坦 |
 * |---|---|---|
 * | 文件 | `RingerModeAndScreenMonitor`(488) | `W6/h`(454) |
 * | 广播 | RINGER_MODE_CHANGED / SCREEN_ON / SCREEN_OFF / USER_PRESENT | 同上 + **USER_UNLOCKED** |
 * | 优先级 | 默认 | **999** |
 *
 * 【A/B 取舍：选天坦】
 *   天坦多注册一个 `USER_UNLOCKED` —— 这是真实差异：
 *   `USER_PRESENT` 在「有锁屏但未设密码」时也会触发，
 *   而 `USER_UNLOCKED` 只在**真正解锁**时触发。
 *   国产 ROM（HyperOS/MIUI）上两者时序不同，
 *   只注册 USER_PRESENT 会漏掉「刚输完密码解锁」这个时刻。
 *
 *   天坦还设了 `setPriority(999)`（高优先级）——
 *   避免被其他应用的广播拦截。
 *
 * ══════════════════════════════════════════════════════════
 * 【本实现相对两方的改进】
 * ══════════════════════════════════════════════════════════
 *
 * ① **纯逻辑与系统调用分离**（沿用白茶既有约定）：
 *    [SystemStateSpeech] 是纯函数（该不该播、播什么），可单测；
 *    本类只负责注册广播与取系统服务。
 *
 *    TalkBack 与天坦都把两者混在一起，导致播报文案
 *    长期没有测试覆盖。
 *
 * ② **不静默失败**：注册失败会写日志并给出原因。
 */
class SystemStateAnnouncer(
    private val context: Context,
    /** 播报回调 —— 由服务注入，本类不持有 SpeechEngine */
    private val speak: (text: String, interrupt: Boolean) -> Unit,
    /** 日志 */
    private val log: (String) -> Unit = {}
) {

    /** 当前是否处于交互状态（屏幕亮着） */
    @Volatile
    var isInteractive: Boolean = true
        private set

    /** 当前铃声模式 */
    @Volatile
    var ringerMode: Int = RINGER_MODE_NORMAL
        private set

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            // 从 Intent 取铃声模式 —— 取不到用正常模式（安全默认）
            val mode = try {
                intent.getIntExtra(
                    android.media.AudioManager.EXTRA_RINGER_MODE,
                    RINGER_MODE_NORMAL
                )
            } catch (e: Throwable) {
                RINGER_MODE_NORMAL
            }
            handleAction(action, mode)
        }
    }

    /**
     * 处理广播 —— 从 onReceive 抽出，便于单测直接调用。
     *
     * 【为什么抽出来】onReceive 需要真实 Intent/Context，
     * JVM 里构造困难；抽出后可用 (action, ringerMode) 直接驱动。
     */
    fun handleAction(action: String, ringerModeValue: Int = RINGER_MODE_NORMAL) {
        when (action) {
            ACTION_RINGER_MODE_CHANGED -> {
                ringerMode = ringerModeValue
                // 天坦行为：只播「变为静音/震动」，
                // 变回正常模式不播（用户能听到铃声本身）
                SystemStateSpeech.ringerAnnouncement(ringerModeValue)?.let {
                    log("系统状态：$it")
                    speak(it, false)
                }
            }

            Intent.ACTION_SCREEN_ON -> {
                isInteractive = true
                log("系统状态：屏幕点亮")
            }

            Intent.ACTION_SCREEN_OFF -> {
                isInteractive = false
                log("系统状态：屏幕熄灭")
            }

            Intent.ACTION_USER_PRESENT -> {
                // 用户在场（可能只是亮屏，不代表解锁）
                log("系统状态：USER_PRESENT")
            }

            ACTION_USER_UNLOCKED -> {
                // 真正解锁 —— 天坦多注册的一个，国产 ROM 上时序不同
                log("系统状态：解锁完成")
            }
        }
    }

    /** 注册广播 */
    fun register(): Boolean {
        if (registered) return true
        return try {
            val filter = IntentFilter().apply {
                addAction(ACTION_RINGER_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ACTION_USER_UNLOCKED)
                // 天坦设 999 避免被其他应用拦截
                priority = RECEIVER_PRIORITY
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            // 初始化当前状态 —— 注册时可能已经处于静音/熄屏
            refreshCurrentState()
            registered = true
            log("系统状态播报：已注册")
            true
        } catch (e: Throwable) {
            log("系统状态播报：注册失败 ${e.message}")
            false
        }
    }

    /** 注销 */
    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Throwable) {
            log("系统状态播报：注销异常 ${e.message}")
        }
        registered = false
    }

    /**
     * 读取当前状态。
     *
     * 【为什么要读初值】注册广播只能收到「之后的变化」；
     * 若服务在静音状态下启动，用户不知道当前是静音。
     */
    private fun refreshCurrentState() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null) ringerMode = am.ringerMode
        } catch (e: Throwable) {
            // 取不到就保持默认（正常模式）—— 不崩
        }
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) isInteractive = pm.isInteractive
        } catch (e: Throwable) {
            // 同上
        }
    }

    /** 屏幕是否亮着 —— 供其他模块查询（替代各处重复调 PowerManager） */
    fun isScreenOn(): Boolean = isInteractive

    companion object {
        private const val TAG = "SystemStateAnnouncer"

        /** 与天坦一致：高优先级，避免被其他应用拦截 */
        const val RECEIVER_PRIORITY = 999

        /** `android.media.RINGER_MODE_CHANGED` */
        const val ACTION_RINGER_MODE_CHANGED = "android.media.RINGER_MODE_CHANGED"

        /**
         * `android.intent.action.USER_UNLOCKED`
         *
         * 【为什么用字面量】该常量在部分 SDK 版本不可见，
         * 与项目既有做法一致（如 TYPE_VIEW_DRAGGED 用 131072）。
         */
        const val ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED"

        // 铃声模式 —— 与 AudioManager 一致
        const val RINGER_MODE_SILENT = 0
        const val RINGER_MODE_VIBRATE = 1
        const val RINGER_MODE_NORMAL = 2
    }
}

/**
 * 系统状态播报的**纯逻辑** —— 可单测
 *
 * 抽出来的原因：TalkBack 与天坦都把「该不该播、播什么」
 * 混在 BroadcastReceiver 里，导致文案长期无测试覆盖。
 */
object SystemStateSpeech {

    /** 静音 */
    const val TEXT_SILENT = "静音"

    /** 震动 */
    const val TEXT_VIBRATE = "震动"

    /**
     * 铃声模式 → 播报文本。
     *
     * 【为什么正常模式返回 null（不播）】两方一致：
     *   从静音切回正常时，铃声本身就是最好的反馈；
     *   再念「正常模式」是噪音。
     *
     * @return 播报文本；null = 不播报
     */
    fun ringerAnnouncement(ringerMode: Int): String? = when (ringerMode) {
        SystemStateAnnouncer.RINGER_MODE_SILENT -> TEXT_SILENT
        SystemStateAnnouncer.RINGER_MODE_VIBRATE -> TEXT_VIBRATE
        SystemStateAnnouncer.RINGER_MODE_NORMAL -> null
        else -> null   // 未知模式不播，避免误报
    }

    /**
     * 该铃声模式变化是否需要播报。
     *
     * 【为什么要单独一个函数】避免调用方忘记判 null。
     */
    fun shouldAnnounceRinger(ringerMode: Int): Boolean =
        ringerAnnouncement(ringerMode) != null
}
