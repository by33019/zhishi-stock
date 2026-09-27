package cn.zhishi.stock.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * "最后一个超级管理员"保护的边界（契约 §16.1 的三处"不得"）。
 *
 * <p>纯函数，因此可以直接穷举真值表——这三行判据是三处保护的唯一来源，
 * 它错了三处一起错，而那种错误在端到端里表现为"某个超管被删掉了，且没有报错"。
 */
class SuperAdminRuleTest {

    @Test
    void blocksOnlyWhenTheOperationWouldRemoveTheLastActiveSuperAdmin() {
        // 会收回超管权限 + 目标是启用超管 + 只剩一个 → 拒绝
        assertThat(SuperAdminRule.blocks(1, true, true)).isTrue();

        // 还有一个别人 → 允许
        assertThat(SuperAdminRule.blocks(2, true, true)).isFalse();

        // 目标不是超管 → 与超管数量无关
        assertThat(SuperAdminRule.blocks(1, false, true)).isFalse();

        // 这次操作不收回超管权限（如改昵称）→ 允许
        assertThat(SuperAdminRule.blocks(1, true, false)).isFalse();
    }

    /** 计数与判定是两次查询，中间可能有人刚被删；0 也要落在保护范围内。 */
    @Test
    void blocksWhenThereAreNoActiveSuperAdminsLeftEither() {
        assertThat(SuperAdminRule.blocks(0, true, true)).isTrue();
    }
}
