package com.zzh.stock_calculator.orchestration.executor;

/**
 * mq_wait 挂起信号（步 6-2）：不是错误——run() 捕获后跳过「置 done」收尾，
 * 实例保持 waiting 等待 MQ 结果事件唤醒；栈迹不需要（纯控制流）。
 */
public class MqWaitSuspendedException extends RuntimeException {

    /** 挂起发生的节点 id（唤醒/超时定位断点用） */
    private final String nodeId;

    public MqWaitSuspendedException(String nodeId) {
        super("mq_wait 挂起: " + nodeId, null, false, false);
        this.nodeId = nodeId;
    }

    public String getNodeId() {
        return nodeId;
    }
}
