package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {
    /**
     * 原子扣减用户配额
     * 使用 quota > 0 条件确保并发安全，避免超扣
     *
     * @param userId 用户ID
     * @return 影响行数，1表示成功，0表示配额不足
     */
    @Update("UPDATE user SET quota = quota - 1 WHERE id = #{userId} AND quota > 0")
    int decrementQuota(@Param("userId") Long userId);

    @Update("UPDATE user SET quota = quota + 1 WHERE id = #{id}")
    void refundQuota(@Param("id") Long id);

    /**
     * 撤销会员身份并重置配额。
     * <p>
     * 用显式 SQL 而非 {@code update(entity)}：后者默认忽略 null 字段，
     * 无法把 vipTime 清空，会留下「角色是 user 但 vipTime 有值」的不一致数据。
     *
     * @param id       用户ID
     * @param userRole 撤销后的角色
     * @param quota    重置后的配额
     * @return 影响行数
     */
    @Update("UPDATE user SET vipTime = NULL, userRole = #{userRole}, quota = #{quota} WHERE id = #{id}")
    int revokeVip(@Param("id") Long id,
                  @Param("userRole") String userRole,
                  @Param("quota") int quota);

    /**
     * 按角色统计用户数。
     * <p>
     * 手写 SQL 不会自动追加逻辑删除条件，故显式写 isDelete = 0。
     *
     * @param role 角色值（user / vip / admin）
     * @return 用户数
     */
    @Select("SELECT COUNT(*) FROM user WHERE userRole = #{role} AND isDelete = 0")
    Long countByRole(@Param("role") String role);

    /**
     * 按角色汇总剩余配额。
     *
     * @param role 角色值
     * @return 剩余配额总和；无记录时返回 0
     */
    @Select("SELECT COALESCE(SUM(quota), 0) FROM user WHERE userRole = #{role} AND isDelete = 0")
    Long sumQuotaByRole(@Param("role") String role);
}

