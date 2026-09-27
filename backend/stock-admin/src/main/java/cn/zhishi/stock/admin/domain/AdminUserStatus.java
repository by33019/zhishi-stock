package cn.zhishi.stock.admin.domain;

/**
 * 用户在后台的可见状态（契约 §16.1 末段）。
 *
 * <p>契约要求 API 层做语义转换：库里 {@code 1} → {@code ACTIVE}、{@code 2} → {@code LOCKED}。
 * 转换写在这里而不是每个 mapper 里各写一次——散开之后，"3 该映射成什么"会各处不同，
 * 而未知取值最该做的事是响亮地失败，不是默认成正常。
 *
 * <p>{@code DISABLED} 不在本枚举里：{@code UserAccount.Status} 有它，但 {@code sys_user.status}
 * 这一列没有第三个取值。给一个永远取不到的值留位置，会让前端为一个不存在的状态写分支。
 */
public enum AdminUserStatus {

    /** 库里为 1：可登录。锁定与解锁之外的一切操作都保持它。 */
    ACTIVE(1),

    /** 库里为 2：不可登录。刷新会话会被一并撤销（ADM-USR-05）。 */
    LOCKED(2);

    private final int dbValue;

    AdminUserStatus(int dbValue) {
        this.dbValue = dbValue;
    }

    public int dbValue() {
        return dbValue;
    }

    public static AdminUserStatus fromDb(int value) {
        for (AdminUserStatus status : values()) {
            if (status.dbValue == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的用户状态：" + value);
    }
}
