package cn.zhishi.stock.admin.domain;

/**
 * 用户资料的部分更新（契约 §16.1 ADM-USR-04）。
 *
 * <p>字段为 {@code null} 表示"本次不改这一项"，空串同样按"不改"处理（用例层归一化）。
 * 因此**本接口无法把某个字段清空**——契约用的是 PATCH 语义（"Body：可选资料字段"），
 * 而表达"清空"需要与"没传"区分开（JSON 里 {@code null} 与缺键在 record 绑定下不可区分）。
 * 需要清空时应由契约给出显式语义（如保留字段加 {@code clearFields} 数组），
 * 现在编一个会让"没传这个字段"和"要求清空"变成同一个请求。
 *
 * <p>刻意**没有** password / roleIds / deleted：契约明写本接口不允许改这三项。
 * 少一个字段就少一条越权路径——把它写成"有字段但忽略"会让下一个维护者以为它能改。
 */
public record AdminUserPatch(
        String nickName,
        String realName,
        String email,
        String phone) {

    public boolean isEmpty() {
        return nickName == null && realName == null && email == null && phone == null;
    }
}
