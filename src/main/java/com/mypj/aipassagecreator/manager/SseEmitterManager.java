package com.mypj.aipassagecreator.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.mypj.aipassagecreator.constant.ArticleConstant.SSE_RECONNECT_TIME_MS;
import static com.mypj.aipassagecreator.constant.ArticleConstant.SSE_TIMEOUT_MS;

@Component
@Slf4j
public class SseEmitterManager {

    /**
     * 存储所有的 SseEmitter
     */
    private final Map<String, SseEmitter> emitterMap = new ConcurrentHashMap<>();

    /**
     * 创建 SseEmitter
     *
     * @param taskId 任务ID
     * @return SseEmitter
     */
    public SseEmitter createEmitter(String taskId) {
        // 同一任务重复建连（典型场景：用户刷新页面）时，先关闭旧连接。
        // 否则旧 emitter 会被新值覆盖、引用丢失，只能等 30 分钟超时才被回收。
        SseEmitter previous = emitterMap.remove(taskId);
        if (previous != null) {
            try {
                previous.complete();
                log.debug("已关闭同任务的旧 SSE 连接, taskId={}", taskId);
            } catch (Exception e) {
                log.debug("关闭旧 SSE 连接失败（可忽略）, taskId={}", taskId, e);
            }
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // 三个回调都用 remove(key, value) 而非 remove(key)：
        // 旧连接的回调可能在「新连接已 put 之后」才异步执行，
        // 若不带值比较就会把刚建好的新 emitter 一起删掉，导致后续推送全部丢失。
        // 设置超时回调
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时, taskId={}", taskId);
            emitterMap.remove(taskId, emitter);
        });

        // 设置完成回调
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成, taskId={}", taskId);
            emitterMap.remove(taskId, emitter);
        });

        // 设置错误回调
        emitter.onError((e) -> {
            log.error("SSE 连接错误, taskId={}", taskId, e);
            emitterMap.remove(taskId, emitter);
        });

        emitterMap.put(taskId, emitter);
        log.info("SSE 连接已创建, taskId={}", taskId);

        return emitter;
    }

    /**
     * 发送消息
     *
     * @param taskId  任务ID
     * @param message 消息内容
     */
    public void send(String taskId, String message) {
        SseEmitter emitter = emitterMap.get(taskId);
        if (emitter == null) {
            log.warn("SSE Emitter 不存在, taskId={}", taskId);
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .data(message)
                    .reconnectTime(SSE_RECONNECT_TIME_MS));
            log.debug("SSE 消息发送成功, taskId={}, message={}", taskId, message);
        } catch (IOException e) {
            log.error("SSE 消息发送失败, taskId={}", taskId, e);
            emitterMap.remove(taskId);
        }
    }

    /**
     * 完成连接
     *
     * @param taskId 任务ID
     */
    public void complete(String taskId) {
        SseEmitter emitter = emitterMap.get(taskId);
        if (emitter == null) {
            // 降为 debug：任务正常结束时统一出口会调用 complete，
            // 而前端可能压根没建过连接（如纯 API 调用、用户提前关页），
            // 这属于正常情况，用 warn 只会制造误导性的日志噪音。
            log.debug("SSE Emitter 不存在（未建连或已关闭）, taskId={}", taskId);
            return;
        }

        try {
            emitter.complete();
            log.info("SSE 连接已完成, taskId={}", taskId);
        } catch (Exception e) {
            log.error("SSE 连接完成失败, taskId={}", taskId, e);
        } finally {
            emitterMap.remove(taskId);
        }
    }

    /**
     * 检查 Emitter 是否存在
     *
     * @param taskId 任务ID
     * @return 是否存在
     */
    public boolean exists(String taskId) {
        return emitterMap.containsKey(taskId);
    }
}

