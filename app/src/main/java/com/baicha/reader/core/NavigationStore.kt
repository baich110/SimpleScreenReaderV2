package com.baicha.reader.core

import android.content.Context

/**
 * 导航模式持久化 —— 对应天坦 `GlobalConfig` 的导航选择存取
 *
 * 【解决什么问题】
 *   用户把导航切到「标题」，退出 App / 服务重启后又回到「默认」。
 *   视障用户每次重新切一遍是纯浪费。
 *
 * 【为什么存 typeCode 而不是枚举名字】
 *   枚举名（`HEADINGS`）会随重构改名 —— 重构一次，用户配置全丢。
 *   `typeCode` 是天坦定义的稳定整数（导航码），跨版本稳定。
 *   `NavigationMode.fromTypeCode()` 已备好映射（此前一直无调用方）。
 *
 * 【存储位置】
 *   独立 SharedPreferences（`baicha_navigation`），与朗读替换分开 ——
 *   两者的备份/重置语义不同，混在一个文件里会互相牵连。
 *
 * 【性能】
 *   SharedPreferences 首次 getSharedPreferences 会读盘，
 *   之后是内存 Map。这里只在「服务启动」与「用户切换导航」两处调用，
 *   不在导航热路径上。
 */
object NavigationStore {

    private const val PREFS_NAME = "baicha_navigation"
    private const val KEY_MODE = "navigation_mode_code"

    /** 未存储过时的默认值 */
    private const val DEFAULT_CODE = 0   // NavigationMode.DEFAULT.typeCode

    /**
     * 读取上次使用的导航。
     *
     * 【容错】存的值若在枚举里找不到（降级安装 / 手动改库 / 未来删除某导航），
     * 返回 DEFAULT 而不是崩溃 —— 读屏是常驻服务，崩溃会让用户彻底失去辅助。
     */
    fun load(context: Context): NavigationMode {
        val code = try {
            prefs(context).getInt(KEY_MODE, DEFAULT_CODE)
        } catch (e: Throwable) {
            return NavigationMode.DEFAULT
        }
        return NavigationMode.fromTypeCode(code) ?: NavigationMode.DEFAULT
    }

    /** 保存导航选择 */
    fun save(context: Context, mode: NavigationMode) {
        try {
            prefs(context).edit().putInt(KEY_MODE, mode.typeCode).apply()
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "导航持久化失败：${e.message}")
        }
    }

    /** 清除（恢复默认） */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_MODE).apply()
        } catch (e: Throwable) {
            ReaderLog.w(TAG, "清除导航持久化失败：${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val TAG = "NavigationStore"
}
