package com.mypj.aipassagecreator.service;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.protocol.ConnectionConfigurations;
import com.alibaba.dashscope.utils.Constants;
import com.mypj.aipassagecreator.config.QwenImageConfig;
import com.mypj.aipassagecreator.model.dto.image.ImageData;
import com.mypj.aipassagecreator.model.dto.image.ImageRequest;
import com.mypj.aipassagecreator.model.enums.ImageMethodEnum;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static com.mypj.aipassagecreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * Qwen 图片生成 服务
 * 使用 Qwen-Image-2.1-Turbo 模型生成图片
 *
 */
@Service
@Slf4j
public class QwenImageService implements ImageSearchService {

    @PostConstruct
    public void init() {
        Constants.baseHttpApiUrl = qwenImageConfig.getBaseHttpApiUrl();
        // 超时必须短于上层的整体超时，否则外层的等待形同虚设。
        // ParallelImageGenerator 给整个配图环节设了 180 秒上限，
        // 原来这里 readTimeout 是 300 秒——单张就能拖到 5 分钟，
        // 超过整体上限后线程仍被这个连接占着，累积几次就会拖垮 imageExecutor。
        Constants.connectionConfigurations = ConnectionConfigurations.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))      // 通义万相单张通常 10~30 秒，留一倍余量
                .writeTimeout(Duration.ofSeconds(30))
                .build();
    }


    @Resource
    private QwenImageConfig qwenImageConfig;

    @Override
    public String searchImage(String keywords) {
        // 此方法已废弃，请使用 getImageData()
        // 返回 null，上传逻辑由 ImageServiceStrategy 统一处理
        return null;
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        String prompt = request.getEffectiveParam(true);
        return generateImageData(prompt);
    }

    /**
     * 根据提示词生成图片数据（千问 Qwen-Image）
     *
     * @param prompt 生图提示词
     * @return ImageData 包含图片字节数据，生成失败返回 null
     */
    public ImageData generateImageData(String prompt) {
        try {

            String apiKey = qwenImageConfig.getAlyApiKey();
            String model = qwenImageConfig.getModel() != null
                    ? qwenImageConfig.getModel()
                    : "qwen-image-2.1-turbo";

            // 构建用户消息（文生图仅需 text）
            MultiModalMessage userMessage = MultiModalMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Collections.singletonList(
                            Collections.singletonMap("text", prompt)
                    ))
                    .build();

            // 构建生成参数
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("prompt_extend", true);
            parameters.put("watermark", false);
            // 千问尺寸格式为 宽*高（星号分隔），与 OpenAI 兼容模式的 x 不同
            if (qwenImageConfig.getSize() != null) {
                parameters.put("size", qwenImageConfig.getSize());
            }

            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(Collections.singletonList(userMessage))
                    .parameters(parameters)
                    .build();

            log.info("Qwen-Image 开始生成图片, model={}, prompt={}", model, prompt);

            MultiModalConversation conv = new MultiModalConversation();
            MultiModalConversationResult result = conv.call(param);

            // 从响应中提取图片 URL
            if (result.getOutput() == null
                    || result.getOutput().getChoices() == null
                    || result.getOutput().getChoices().isEmpty()) {
                log.warn("Qwen-Image 未返回有效结果, prompt={}", prompt);
                return null;
            }

            Map<String, Object> imageMap = result.getOutput()
                    .getChoices().get(0)
                    .getMessage().getContent().get(0);
            String imageUrl = (String) imageMap.get("image");

            if (imageUrl == null || imageUrl.isEmpty()) {
                log.warn("Qwen-Image 响应中无图片 URL, prompt={}", prompt);
                return null;
            }

            log.info("Qwen-Image 图片生成成功, url={}", imageUrl);
            return ImageData.fromUrl(imageUrl);

        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            log.error("Qwen-Image API 调用失败, prompt={}", prompt, e);
            return null;
        } catch (Exception e) {
            log.error("Qwen-Image 生成图片异常, prompt={}", prompt, e);
            return null;
        }
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.QWEN_IMAGE;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }
}

