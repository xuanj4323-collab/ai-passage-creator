package com.mypj.aipassagecreator.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mypj.aipassagecreator.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}

