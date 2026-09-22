package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.executor.Executor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 步 6-2 MQ 节点单测（纯内存）：mq_wait Zombie 防御拒载 + mq_send 节点 DAG 合法性。
 * Executor 依赖经构造注入，校验路径不触达，传 null 即可。
 */
class MqNodeValidateTest {

    private final Executor executor = new Executor(null, null, null, null);
    private final ObjectMapper om = new ObjectMapper();

    private ObjectNode dag(ObjectNode... nodes) {
        ObjectNode dag = om.createObjectNode();
        var arr = om.createArrayNode();
        for (ObjectNode n : nodes) {
            arr.add(n);
        }
        dag.set("nodes", arr);
        return dag;
    }

    @Test
    void mq_wait缺timeout_seconds_拒载() {
        ObjectNode node = om.createObjectNode().put("id", "w1").put("type", "mq_wait");
        assertThatThrownBy(() -> executor.validateDag(dag(node)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout_seconds");
    }

    @Test
    void mq_wait带timeout_合法放行() {
        ObjectNode node = om.createObjectNode()
                .put("id", "w1").put("type", "mq_wait").put("timeout_seconds", 60);
        assertThatCode(() -> executor.validateDag(dag(node))).doesNotThrowAnyException();
    }

    @Test
    void mq_send节点_与普通节点同样放行() {
        ObjectNode node = om.createObjectNode().put("id", "s1").put("type", "mq_send");
        assertThatCode(() -> executor.validateDag(dag(node))).doesNotThrowAnyException();
    }
}
