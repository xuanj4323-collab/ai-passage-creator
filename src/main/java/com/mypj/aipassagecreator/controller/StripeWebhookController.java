package com.mypj.aipassagecreator.controller;

import com.mypj.aipassagecreator.service.PaymentService;
import com.stripe.Stripe;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Stripe Webhook 控制器
 *
 */
@RestController
@RequestMapping("/webhook")
@Slf4j
@Hidden
public class StripeWebhookController {

    @Resource
    private PaymentService paymentService;

    /**
     * 处理 Stripe Webhook 回调
     */
    @PostMapping("/stripe")
    public String handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        try {
            // 验证 Webhook 签名
            Event event = paymentService.constructEvent(payload, sigHeader);

            log.info("收到 Stripe Webhook 事件, type={}", event.getType());

            // 处理事件
            switch (event.getType()) {
                case "checkout.session.completed":
                    // 支付成功
                    paymentService.handlePaymentSuccess(extractSession(event));
                    break;

                case "checkout.session.async_payment_succeeded":
                    // 异步支付成功
                    paymentService.handlePaymentSuccess(extractSession(event));
                    break;

                default:
                    log.info("未处理的事件类型: {}", event.getType());
                    break;
            }

            return "success";
        } catch (Exception e) {
            log.error("处理 Stripe Webhook 失败", e);
            return "error";
        }
    }

    /**
     * 从事件中提取 Checkout Session
     * <p>
     * Stripe 按「webhook endpoint 配置的 API 版本」序列化事件，而 SDK 的
     * {@code getObject()} 只在事件 API 版本与 {@link Stripe#API_VERSION} 完全一致时才返回结果，
     * 否则直接返回 empty（即使 JSON 完全合法）。
     * <p>
     * 因此这里做两级兜底：
     * 1）版本不一致时打印双方版本号，便于去 Dashboard 对齐 endpoint 的 API version；
     * 2）用 {@code deserializeUnsafe()} 强制解析——Session 中本项目用到的
     * {@code id} / {@code metadata} / {@code paymentIntent} 都属于跨版本稳定字段。
     */
    private Session extractSession(Event event) {
        Optional<StripeObject> object = event.getDataObjectDeserializer().getObject();
        if (object.isPresent()) {
            return (Session) object.get();
        }

        log.warn("Session 反序列化失败（事件 API 版本与 SDK 不一致），回退 deserializeUnsafe。"
                        + "建议在 Stripe Dashboard 把该 endpoint 的 API version 设为 {}。"
                        + "eventId={}, eventApiVersion={}",
                Stripe.API_VERSION, event.getId(), event.getApiVersion());

        try {
            return (Session) event.getDataObjectDeserializer().deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            // 连强制解析都失败，说明字段结构差异过大，只能人工对齐版本
            throw new RuntimeException("解析 Checkout Session 失败，请检查 Webhook endpoint 的 API 版本配置", e);
        }
    }
}

