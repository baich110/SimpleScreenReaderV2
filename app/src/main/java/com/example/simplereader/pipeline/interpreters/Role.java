package com.example.simplereader.pipeline.interpreters;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

public class Role {
    public static final int ROLE_UNDEFINED = 0;
    public static final int ROLE_LIST = 1;
    public static final int ROLE_GRID = 2;
    public static final int ROLE_LIST_ITEM = 3;
    public static final int ROLE_GRID_ITEM = 4;
    public static final int ROLE_EDIT_TEXT = 5;
    public static final int ROLE_BUTTON = 6;
    public static final int ROLE_CHECKBOX = 7;
    public static final int ROLE_RADIO_BUTTON = 8;
    public static final int ROLE_SWITCH = 9;

    public static int getRole(AccessibilityNodeInfoCompat node) {
        if (node == null) return ROLE_UNDEFINED;
        if (node.isEditable()) return ROLE_EDIT_TEXT;
        if (node.isCheckable()) {
            String className = node.getClassName() != null ? node.getClassName().toString().toLowerCase() : "";
            if (className.contains("switch")) return ROLE_SWITCH;
            if (className.contains("radio")) return ROLE_RADIO_BUTTON;
            return ROLE_CHECKBOX;
        }
        if (node.isClickable()) return ROLE_BUTTON;
        return ROLE_UNDEFINED;
    }
}
