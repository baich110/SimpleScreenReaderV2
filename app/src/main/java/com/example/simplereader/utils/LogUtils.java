/*
 * LogUtils - 日志工具
 */
package com.example.simplereader.utils;

public class LogUtils {
    public static void d(String tag, String msg, Object... args) {
        android.util.Log.d(tag, String.format(msg, args));
    }
    
    public static void v(String tag, String msg, Object... args) {
        android.util.Log.v(tag, String.format(msg, args));
    }
    
    public static void w(String tag, String msg, Object... args) {
        android.util.Log.w(tag, String.format(msg, args));
    }
    
    public static void e(String tag, String msg, Object... args) {
        android.util.Log.e(tag, String.format(msg, args));
    }
}
