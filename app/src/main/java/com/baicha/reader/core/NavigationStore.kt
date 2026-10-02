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

    /** 默认导航码 —— NavigationMode.DEFAULT.typeCode */
    private const val DEFAULT_CODE = 0

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
        return resolve(code)
    }

    /**
     * 导航码 → 导航模式（**纯函数，可测**）
     *
     * 【为什么抽出来】
     *   `load()` 需要真实 `Context`（SharedPreferences），
     *   JVM 单测构造不出来 —— 于是「存的码非法时怎么回退」
     *   这条**最需要防御逻辑**的分支反而没有覆盖。
     *
     *   变异测试证实：把 DEFAULT_CODE 改成 99，所有测试仍通过。
     *
     * 回退链（三层，任一环坏掉都要能兜住）：
     *   ① 码在枚举里 → 用它
     *   ② 码查不到（降级安装/改库/枚举项被删）→ DEFAULT
     *   ③ 连 DEFAULT 都取不到 → 返回枚举首个（理论上不会发生，
     *      但读屏常驻，任何路径都不允许抛异常）
     */
    fun resolve(code: Int): NavigationMode {
        NavigationMode.fromTypeCode(code)?.let { return it }
        ReaderLog.d(TAG, "导航码 $code 无对应模式 → 回退默认")
        return NavigationMode.fromTypeCode(DEFAULT_CODE)
            ?: NavigationMode.values().firstOrNull()
            ?: NavigationMode.DEFAULT
    }

    /** 默认导航码（供测试核对） */
    fun defaultCode(): Int = DEFAULT_CODE

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
