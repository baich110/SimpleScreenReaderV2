/*
 * TraversalStrategy - 遍历策略
 */
package com.example.simplereader.utils.traversal;

public class TraversalStrategy {
    public static final int SEARCH_FOCUS_FORWARD = 1;
    public static final int SEARCH_FOCUS_BACKWARD = 2;
    public static final int SEARCH_FOCUS_UNKNOWN = 0;
    
    public static final int MOVEMENT_GRANULARITY_CHARACTER = 1;
    public static final int MOVEMENT_GRANULARITY_WORD = 2;
    public static final int MOVEMENT_GRANULARITY_LINE = 4;
}
