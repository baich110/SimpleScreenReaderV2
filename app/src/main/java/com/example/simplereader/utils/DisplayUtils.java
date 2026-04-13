package com.example.simplereader.utils;

import android.content.Context;
import android.graphics.Point;
import android.view.WindowManager;

public class DisplayUtils {
    public static Point getScreenPixelSizeWithoutWindowDecor(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        if (wm != null) {
            wm.getDefaultDisplay().getSize(size);
        }
        return size;
    }
}
