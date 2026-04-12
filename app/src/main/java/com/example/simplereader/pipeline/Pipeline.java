package com.example.simplereader.pipeline;

import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.example.simplereader.pipeline.actors.Actors;
import com.example.simplereader.pipeline.feedback.Feedback;
import com.example.simplereader.pipeline.interpreters.Interpreters;
import com.example.simplereader.pipeline.mappers.Mappers;
import com.example.simplereader.pipeline.monitors.Monitors;

/**
 * Pipeline流水线 - 完整抄自TalkBack Pipeline
 * 
 * 核心架构：
 * AccessibilityEvent → Monitors → Interpreters → Mappers → Actors → Feedback
 * 
 * 4大阶段：
 * 1. Monitors - 监控系统状态（电池、来电、音量、屏幕等）
 * 2. Interpreters - 13个事件解释器，将原始事件转换为语义解释
 * 3. Mappers - 将解释映射为Feedback
 * 4. Actors - 执行Feedback（语音、震动、焦点等）
 */
public class Pipeline implements AccessibilityEventListener, AccessibilityEventIdleListener {
    
    public static final String LOG = "Pipeline";
    
    // Pipeline的4大组件
    private final Context context;
    private final Monitors monitors;
    private final Interpreters interpreters;
    private final Mappers mappers;
    private final Actors actors;
    
    // 异步消息处理器
    private final FeedbackDelayer feedbackDelayer;
    
    /** 事件接收器 - 用于接收内部事件 */
    public class EventReceiver {
        public void input(SyntheticEvent.Type eventType) {
            Pipeline.this.inputEvent(Performance.EVENT_ID_UNTRACKED, new SyntheticEvent(eventType));
        }
    }
    
    /** 合成事件类型 */
    public static class SyntheticEvent {
        public enum Type { SCROLL_TIMEOUT, TEXT_TRAVERSAL }
        
        public final Type eventType;
        public final CharSequence eventText;
        public final long uptimeMs;
        
        public SyntheticEvent(Type eventType) {
            this.eventType = eventType;
            this.eventText = null;
            this.uptimeMs = System.currentTimeMillis();
        }
        
        public SyntheticEvent(Type eventType, CharSequence text) {
            this.eventType = eventType;
            this.eventText = text;
            this.uptimeMs = System.currentTimeMillis();
        }
    }
    
    /** 解释接收器 - 用于接收Interpreters的输出 */
    public interface InterpretationReceiver {
        boolean input(@Nullable Performance.EventId eventId, @Nullable AccessibilityEvent event, 
                      @Nullable Interpretation interpretation, @Nullable AccessibilityNodeInfoCompat sourceNode);
    }
    
    /** 反馈返回器 - 用于返回Feedback执行结果 */
    public interface FeedbackReturner {
        boolean returnFeedback(Feedback feedback);
    }
    
    private final EventReceiver eventReceiver = new EventReceiver();
    private final InterpretationReceiver interpretationReceiver = this::inputInterpretation;
    private final FeedbackReturner feedbackReturner = this::execute;
    
    public Pipeline(Context context, Monitors monitors, Interpreters interpreters, 
                   Mappers mappers, Actors actors) {
        this.context = context;
        this.monitors = monitors;
        this.interpreters = interpreters;
        this.mappers = mappers;
        this.actors = actors;
        
        // 初始化组件连接
        monitors.setPipelineInterpretationReceiver(interpretationReceiver);
        interpreters.setPipelineInterpretationReceiver(interpretationReceiver);
        interpreters.setActorState(actors.getState());
        mappers.setMonitors(monitors.state);
        actors.setPipelineEventReceiver(eventReceiver);
        actors.setPipelineFeedbackReturner(feedbackReturner);
        
        feedbackDelayer = new FeedbackDelayer(this, actors);
    }
    
    // ============ AccessibilityEventListener 实现 ============
    
    @Override
    public int getEventTypes() {
        return interpreters.getEventTypes() | monitors.getEventTypes();
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
        // 阶段1: Monitors处理
        monitors.onAccessibilityEvent(event);
        // 阶段2: Interpreters处理
        interpreters.onAccessibilityEvent(event, eventId);
    }
    
    @Override
    public void onIdle() {
        interpreters.onIdle();
    }
    
    // ============ 核心处理方法 ============
    
    /** 输入内部合成事件 */
    private void inputEvent(Performance.EventId eventId, SyntheticEvent event) {
        interpreters.interpret(eventId, event);
    }
    
    /** 输入解释，将解释转换为Feedback并执行 */
    private boolean inputInterpretation(@Nullable Performance.EventId eventId,
                                       @Nullable AccessibilityEvent event,
                                       @Nullable Interpretation interpretation,
                                       @Nullable AccessibilityNodeInfoCompat sourceNode) {
        // 阶段3: Mappers将解释映射为Feedback
        Feedback feedback = mappers.mapToFeedback(eventId, event, interpretation, sourceNode);
        if (feedback == null) {
            return false;
        }
        // 阶段4: 执行Feedback
        return execute(feedback);
    }
    
    /** 执行Feedback - 核心执行方法 */
    boolean execute(Feedback feedback) {
        Log.d(LOG, "execute() feedback=" + feedback);
        
        // 遍历所有Part（failover机制）
        for (int i = 0; i < feedback.failovers().size(); i++) {
            Feedback.Part part = feedback.failovers().get(i);
            
            // 取消延迟的同组反馈
            if (part.interruptGroup() != Feedback.DEFAULT) {
                cancelDelay(part.interruptGroup(), part.interruptLevel(), part.senderName());
                actors.clearHintUtteranceCompleteAction(part.interruptGroup(), part.interruptLevel());
            }
            
            // 中断所有反馈
            if (part.interruptAllFeedback()) {
                actors.interruptAllFeedback(part.stopTts());
            }
            // 中断声音和震动
            if (part.interruptSoundAndVibration()) {
                actors.interruptSoundAndVibration();
            }
            // 温和中断
            if (part.interruptGentle()) {
                actors.interruptGentle(feedback.eventId());
            }
            
            boolean success = true;
            if (part.delayMs() <= 0) {
                // 立即执行
                success = actors.act(feedback.eventId(), part);
                Log.v(LOG, "execute() success=" + success + " for part=" + part);
            } else {
                // 延迟执行
                startDelay(feedback.eventId(), part);
            }
            
            if (success) {
                return true;
            }
        }
        return false;
    }
    
    // ============ 延迟执行相关 ============
    
    private void cancelDelay(int interruptGroup, int interruptLevel, String senderName) {
        // 取消延迟的反馈
    }
    
    private void startDelay(Performance.EventId eventId, Feedback.Part part) {
        // 启动延迟反馈
    }
    
    // ============ 对外接口 ============
    
    public Actors getActors() { return actors; }
    public Interpreters getInterpreters() { return interpreters; }
    public Mappers getMappers() { return mappers; }
    public Monitors getMonitors() { return monitors; }
    
    public EventReceiver getEventReceiver() { return eventReceiver; }
    public InterpretationReceiver getInterpretationReceiver() { return interpretationReceiver; }
    public FeedbackReturner getFeedbackReturner() { return feedbackReturner; }
    
    // ============ 内部类 ============
    
    /** 反馈延迟器 */
    private static class FeedbackDelayer {
        private final Pipeline pipeline;
        private final Actors actors;
        
        public FeedbackDelayer(Pipeline pipeline, Actors actors) {
            this.pipeline = pipeline;
            this.actors = actors;
        }
    }
}
