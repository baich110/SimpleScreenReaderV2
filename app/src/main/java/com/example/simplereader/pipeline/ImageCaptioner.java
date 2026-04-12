/*
 * ImageCaptioner - 图像描述器
 */
package com.example.simplereader.pipeline;

import android.content.Context;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class ImageCaptioner {
    public static boolean supportsImageCaption(Context context) {
        return false;
    }
    
    public static boolean needAutomaticCaptioning(Context context, AccessibilityNodeInfoCompat node) {
        return false;
    }
}
