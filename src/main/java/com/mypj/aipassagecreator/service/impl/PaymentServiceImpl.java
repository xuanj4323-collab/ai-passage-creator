package com.mypj.aipassagecreator.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.mypj.aipassagecreator.config.StripeConfig;
import com.mypj.aipassagecreator.constant.UserConstant;
import com.mypj.aipassagecreator.exception.BusinessException;
import com.mypj.aipassagecreator.exception.ErrorCode;
import com.mypj.aipassagecreator.mapper.PaymentRecordMapper;
import com.mypj.aipassagecreator.mapper.UserMapper;
import com.mypj.aipassagecreator.model.entity.PaymentRecord;
import com.mypj.aipassagecreator.model.entity.User;
import com.mypj.aipassagecreator.model.enums.PaymentStatusEnum;
import com.mypj.aipassagecreator.model.enums.ProductTypeEnum;
import com.mypj.aipassagecreator.service.PaymentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 支付服务实现
 *
 */
@Service
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private static final String CURRENCY_USD = "usd";

    @Resource
    private StripeConfig stripeConfig;

    @Resource
    private UserMapper userMapper;

    @Resource
    private PaymentRecordMapper paymentRecordMapper;

    @Override
    public String createVipPaymentSession(Long userId) throws StripeException {
        User user = getUserOrThrow(userId);
        validateNotVip(user);

        ProductTypeEnum productType = ProductTypeEnum.VIP_PERMANENT;
        Session session = createStripeSession(userId, productType);
        savePaymentRecord(userId, session, productType);

        log.info("创建支付会话成功, userId={}, sessionId={}", userId, session.getId());
        return session.getUrl();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handlePaymentSuccess(Session session) {
        String sessionId = session.getId();

        // 兼容 deserializeUnsafe 的兜底路径：跨 API 版本反序列化时 metadata 可能为空，
        // 显式判空，避免 NPE 掩盖真正的问题（元数据缺失）
        Map<String, String> metadata = session.getMetadata();
        String userId = metadata != null ? metadata.get("userId") : null;
        if (userId == null || userId.isBlank()) {
            log.error("支付会话缺少 userId 元数据, sessionId={}", sessionId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "支付会话信息不完整，无法开通会员");
        }

        String paymentIntentId = session.getPaymentIntent();

        // ===== 业务校验：Webhook 是外部输入，验签只保证来源，不保证金额/产品正确 =====
        String productTypeValue = metadata.get("productType");
        ProductTypeEnum productType = ProductTypeEnum.getByValue(productTypeValue);
        if (productType == null) {
            log.error("支付会话产品类型无效, sessionId={}, productType={}", sessionId, productTypeValue);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "支付会话产品类型无效");
        }

        long expectedCents = productType.getPrice().movePointRight(2).longValueExact();
        Long actualCents = session.getAmountTotal();
        if (actualCents == null || actualCents != expectedCents) {
            log.error("支付金额与订单不一致, sessionId={}, expected={}, actual={}",
                    sessionId, expectedCents, actualCents);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "支付金额与订单不一致");
        }

        PaymentRecord record = findPaymentRecordBySessionId(sessionId);
        if (record == null) {
            // 不能静默返回 success：否则 Stripe 不再重试，用户付了钱却永远开不了会员。
            // 抛异常让 Controller 返回 error，Stripe 会按重试策略再次投递。
            log.error("支付记录缺失，无法开通会员, sessionId={}, userId={}", sessionId, userId);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "支付记录缺失，请稍后重试");
        }

        // 幂等性检查
        if (PaymentStatusEnum.SUCCEEDED.getValue().equals(record.getStatus())) {
            log.info("支付记录已处理, sessionId={}", sessionId);
            return;
        }

        updatePaymentStatus(record.getId(), PaymentStatusEnum.SUCCEEDED, paymentIntentId);
        upgradeUserToVip(Long.valueOf(userId));

        log.info("支付成功，用户已升级为 VIP, userId={}, sessionId={}", userId, sessionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handleRefund(Long userId, String reason) throws StripeException {
        User user = getUserOrThrow(userId);
        validateIsVip(user);

        PaymentRecord paymentRecord = findLatestSuccessfulPayment(userId);
        if (paymentRecord == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到支付记录");
        }

        if (paymentRecord.getStripePaymentIntentId() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "支付记录无效");
        }

        Refund refund = createStripeRefund(paymentRecord.getStripePaymentIntentId());
        if (!"succeeded".equals(refund.getStatus())) {
            return false;
        }

        updateRefundRecord(paymentRecord.getId(), reason);
        revokeVipStatus(userId);

        log.info("退款成功，已取消 VIP 身份, userId={}, refundId={}", userId, refund.getId());
        return true;
    }

    @Override
    public Event constructEvent(String payload, String sigHeader) throws Exception {
        return Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
    }

    @Override
    public List<PaymentRecord> getPaymentRecords(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .orderBy("createTime", false);
        return paymentRecordMapper.selectListByQuery(queryWrapper);
    }

    // ==================== 私有方法封装 ====================

    /**
     * 获取用户或抛出异常
     */
    private User getUserOrThrow(Long userId) {
        User user = userMapper.selectOneById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return user;
    }

    /**
     * 验证用户不是 VIP
     */
    private void validateNotVip(User user) {
        if (UserConstant.VIP_ROLE.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "您已经是永久会员");
        }
    }

    /**
     * 验证用户是 VIP
     */
    private void validateIsVip(User user) {
        if (!UserConstant.VIP_ROLE.equals(user.getUserRole())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "您不是会员，无法退款");
        }
    }

    /**
     * 创建 Stripe 支付会话
     */
    private Session createStripeSession(Long userId, ProductTypeEnum productType) throws StripeException {
        // movePointRight + longValueExact：精度非法时直接抛异常，
        // 避免 multiply().longValue() 静默截断小数
        long amountInCents = productType.getPrice().movePointRight(2).longValueExact();

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(stripeConfig.getSuccessUrl())
                .setCancelUrl(stripeConfig.getCancelUrl())
                .addLineItem(buildLineItem(productType, amountInCents))
                .putMetadata("userId", String.valueOf(userId))
                .putMetadata("productType", productType.getValue())
                .build();

        return Session.create(params);
    }

    /**
     * 构建支付行项目
     */
    private SessionCreateParams.LineItem buildLineItem(ProductTypeEnum productType, long amountInCents) {
        return SessionCreateParams.LineItem.builder()
                .setPriceData(
                        SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(CURRENCY_USD)
                                .setUnitAmount(amountInCents)
                                .setProductData(
                                        SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                                .setName(productType.getDescription())
                                                .setDescription("解锁全部高级功能，无限创作配额，终身有效")
                                                .build()
                                )
                                .build()
                )
                .setQuantity(1L)
                .build();
    }

    /**
     * 保存支付记录
     */
    private void savePaymentRecord(Long userId, Session session, ProductTypeEnum productType) {
        PaymentRecord record = PaymentRecord.builder()
                .userId(userId)
                .stripeSessionId(session.getId())
                .amount(productType.getPrice())
                .currency(CURRENCY_USD)
                .status(PaymentStatusEnum.PENDING.getValue())
                .productType(productType.getValue())
                .description(productType.getDescription())
                .build();
        // 必须用 insertSelective：insert(entity) 会把未赋值的 createTime 以 NULL 写入，
        // 覆盖掉列的 DEFAULT CURRENT_TIMESTAMP，导致支付记录时间全为空、
        // 以及 findLatestSuccessfulPayment 的 orderBy createTime 失效
        paymentRecordMapper.insertSelective(record);
    }

    /**
     * 根据 Session ID 查询支付记录
     */
    private PaymentRecord findPaymentRecordBySessionId(String sessionId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("stripeSessionId", sessionId);
        return paymentRecordMapper.selectOneByQuery(queryWrapper);
    }

    /**
     * 查询最近的成功支付记录
     */
    private PaymentRecord findLatestSuccessfulPayment(Long userId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("userId", userId)
                .eq("status", PaymentStatusEnum.SUCCEEDED.getValue())
                .eq("productType", ProductTypeEnum.VIP_PERMANENT.getValue())
                .orderBy("createTime", false)
                .limit(1);
        return paymentRecordMapper.selectOneByQuery(queryWrapper);
    }

    /**
     * 更新支付状态
     */
    private void updatePaymentStatus(Long recordId, PaymentStatusEnum status, String paymentIntentId) {
        PaymentRecord updateRecord = new PaymentRecord();
        updateRecord.setId(recordId);
        updateRecord.setStatus(status.getValue());
        updateRecord.setStripePaymentIntentId(paymentIntentId);
        paymentRecordMapper.update(updateRecord);
    }

    /**
     * 升级用户为 VIP
     */
    private void upgradeUserToVip(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setVipTime(LocalDateTime.now());
        user.setUserRole(UserConstant.VIP_ROLE);
        userMapper.update(user);
    }

    /**
     * 创建 Stripe 退款
     */
    private Refund createStripeRefund(String paymentIntentId) throws StripeException {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .build();
        return Refund.create(params);
    }

    /**
     * 更新退款记录
     */
    private void updateRefundRecord(Long recordId, String reason) {
        PaymentRecord updateRecord = new PaymentRecord();
        updateRecord.setId(recordId);
        updateRecord.setStatus(PaymentStatusEnum.REFUNDED.getValue());
        updateRecord.setRefundTime(LocalDateTime.now());
        updateRecord.setRefundReason(reason);
        paymentRecordMapper.update(updateRecord);
    }

    /**
     * 撤销用户 VIP 身份
     * <p>
     * 必须用自定义 SQL：{@code userMapper.update(entity)} 默认忽略 null 字段，
     * 无法把 vipTime 清空，会留下「角色是 user 但 vipTime 有值」的不一致数据。
     */
    private void revokeVipStatus(Long userId) {
        userMapper.revokeVip(userId, UserConstant.DEFAULT_ROLE, UserConstant.DEFAULT_QUOTA);
    }
}