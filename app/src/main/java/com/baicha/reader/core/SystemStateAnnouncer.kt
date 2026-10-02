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

    /**
     * 是否正在通话中。
     *
     * 【为什么要它】TalkBack `handleScreenOffInBackgroundThread()`：
     *   "Only announce ringer state if we're not in a call."
     *   通话中熄屏是正常行为，念铃声状态是噪音。
     *
     * 由 CallStateMonitor 注入（当前简化为可设置字段，
     * 因为白茶尚未做通话状态监听 —— 见待办 A5）。
     */
    @Volatile
    var inCall: Boolean = false

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
                // 【核实修正】两方都**只存字段，不播报**：
                //   TalkBack `handleRingerModeChanged()`: `this.ringerMode = ringerMode;`
                //   （其 javadoc 写 "Announces the current ringer state"，
                //     但方法体只赋值 —— 注释与实现不符）
                //   天坦 `l(int)`: `this.f10351l = i9;`
                // 铃声状态只在**屏幕关闭时顺带播一次**（见下）。
                ringerMode = ringerModeValue
                log("系统状态：铃声模式 → ${SystemStateSpeech.ringerName(ringerModeValue)}")
            }

            Intent.ACTION_SCREEN_ON -> {
                isInteractive = true
                // 【核实修正】TalkBack `speakForDisplayOn()` 播的是**当前时间**
                //（`appendCurrentTimeAnnouncementIfNeeded`），不是「屏幕点亮」。
                // 天坦 `n()` 播的是**锁屏内容**（时间/日期/电量拼装）。
                // 两者都是「亮屏时告诉用户现在几点」，故白茶也播时间。
                val now = SystemStateSpeech.formatTime(System.currentTimeMillis())
                if (now != null) {
                    log("系统状态：亮屏 → 播报时间 $now")
                    speak(now, false)
                } else {
                    log("系统状态：亮屏（时间播报已关闭）")
                }
            }

            Intent.ACTION_SCREEN_OFF -> {
                isInteractive = false
                // 【核实修正】TalkBack `handleScreenOffInBackgroundThread()`：
                //   播「屏幕关闭」+ **顺带念一次铃声状态**（通话中不念）。
                // 天坦 `m()` 不播报 —— A/B 取舍选天坦的话可以不播。
                //
                // 【白茶的选择：播】理由见 SystemStateSpeech 的注释。
                val text = SystemStateSpeech.screenOffAnnouncement(
                    inCall = inCall,
                    ringerMode = ringerMode
                )
                log("系统状态：熄屏 → $text")
                speak(text, false)
            }

            Intent.ACTION_USER_PRESENT -> {
                // 【核实修正】TalkBack `handleDeviceUnlocked()` 播「设备已解锁」；
                // 天坦 `k()` 只恢复服务状态，不播报。
                log("系统状态：USER_PRESENT")
            }

            ACTION_USER_UNLOCKED -> {
                // 天坦多注册的一个（国产 ROM 时序不同）
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
 * ══════════════════════════════════════════════════════════
 * 【重要：本文件的规则已按两方**源码**重新核实过】
 * ══════════════════════════════════════════════════════════
 *
 * 初版实现是「凭印象」写的，三处与真实行为不符，已全部修正：
 *
 * | 事件 | 初版（错） | 真实行为 |
 * |---|---|---|
 * | 铃声变化 | 主动播「静音」/「震动」 | **两方都只存字段，不播报** |
 * | 屏幕点亮 | 日志「屏幕点亮」 | TalkBack 播**当前时间**；天坦播**锁屏内容** |
 * | 屏幕关闭 | 日志「屏幕熄灭」 | TalkBack 播「屏幕关闭」+ 顺带念铃声状态 |
 *
 * 教训：对标必须读源码，不能凭印象 ——
 * 印象里的「合理行为」未必是两方的真实行为。
 */
object SystemStateSpeech {

    /** 静音 */
    const val TEXT_SILENT = "静音"

    /** 震动 */
    const val TEXT_VIBRATE = "震动"

    /** 正常 */
    const val TEXT_NORMAL = "响铃"

    /** 屏幕关闭 —— TalkBack `R.string.value_screen_off` */
    const val TEXT_SCREEN_OFF = "屏幕已关闭"

    /**
     * 铃声模式 → 名称。
     *
     * 【注意：仅用于日志与「熄屏顺带播报」，不用于铃声变化时主动播报】
     *   —— 两方的 RINGER_MODE_CHANGED 处理都只存字段。
     */
    fun ringerName(ringerMode: Int): String = when (ringerMode) {
        SystemStateAnnouncer.RINGER_MODE_SILENT -> TEXT_SILENT
        SystemStateAnnouncer.RINGER_MODE_VIBRATE -> TEXT_VIBRATE
        SystemStateAnnouncer.RINGER_MODE_NORMAL -> TEXT_NORMAL
        else -> "未知"
    }

    /**
     * 熄屏播报文本 —— 对标 TalkBack `handleScreenOffInBackgroundThread()`
     *
     * ```java
     * final SpannableStringBuilder ttsText =
     *     new SpannableStringBuilder(service.getString(R.string.value_screen_off));
     * // Only announce ringer state if we're not in a call.
     * if (isIdle()) {
     *     appendRingerStateAnnouncement(ttsText);
     * }
     * ```
     *
     * 【为什么熄屏要念铃声状态】
     *   用户合上手机前最关心的是「铃声关了没」——
     *   这是**唯一**能顺带告知的时机（铃声变化本身不播报）。
     *
     * 【为什么通话中不念】
     *   TalkBack 明确判定 `isIdle()`。通话中熄屏是正常操作，
     *   念「静音」会打断通话听感。
     *
     * 【A/B 说明】天坦熄屏不播报。白茶选择**播**，理由：
     *   这是两方唯一的分歧点，而 TalkBack 的做法有明确价值
     *   （顺带告知铃声状态是刚需）。此处属于「取舍时选更优」
     *   而非「平局偏好」，故与目标不冲突。
     */
    fun screenOffAnnouncement(inCall: Boolean, ringerMode: Int): String {
        if (inCall) return TEXT_SCREEN_OFF
        val ringer = ringerName(ringerMode)
        // 响铃是默认状态，不必念（对齐 TalkBack 的 appendRingerStateAnnouncement：
        // RINGER_MODE_NORMAL 直接 return，不加内容）
        return if (ringerMode == SystemStateAnnouncer.RINGER_MODE_NORMAL) {
            TEXT_SCREEN_OFF
        } else {
            "$TEXT_SCREEN_OFF，$ringer"
        }
    }

    /**
     * 亮屏时是否该播报时间。
     *
     * 对标 TalkBack `speakForDisplayOn()`：
     *   ```java
     *   if (isIdle()) {
     *       if (DEVICE_PROVISIONED != 0) appendCurrentTimeAnnouncementIfNeeded(ttsText);
     *       else ttsText.append(value_screen_on);   // 设备未就绪 → 播「屏幕已打开」
     *   }
     *   ```
     *
     * @param enabledTellingTime 用户是否开启「报时」（`GlobalConfig` 开关）
     * @param deviceProvisioned  设备是否已完成初始设置
     */
    fun shouldAnnounceTimeOnScreenOn(
        enabledTellingTime: Boolean,
        deviceProvisioned: Boolean
    ): Boolean = enabledTellingTime && deviceProvisioned

    /**
     * 格式化时间 —— 对标 TalkBack `DateTimeUtils.getCurrentTime()`
     *
     * 用 24 小时制的「HH:mm」，与天坦锁屏内容的时间格式一致。
     *
     * @return 时间文本；不需要播报时返回 null
     */
    fun formatTime(
        epochMillis: Long,
        enabledTellingTime: Boolean = true
    ): String? {
        if (!enabledTellingTime) return null
        return try {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(epochMillis))
        } catch (e: Throwable) {
            null
        }
    }
}
