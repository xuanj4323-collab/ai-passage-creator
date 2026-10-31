package com.mypj.aipassagecreator.agent;


import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.google.gson.reflect.TypeToken;
import com.mypj.aipassagecreator.agent.agents.ContentGeneratorAgent;
import com.mypj.aipassagecreator.agent.agents.ContentMergerAgent;
import com.mypj.aipassagecreator.agent.agents.ImageAnalyzerAgent;
import com.mypj.aipassagecreator.agent.agents.OutlineGeneratorAgent;
import com.mypj.aipassagecreator.agent.agents.TitleGeneratorAgent;
import com.mypj.aipassagecreator.agent.context.StreamHandlerContext;
import com.mypj.aipassagecreator.agent.parallel.ParallelImageGenerator;
import com.mypj.aipassagecreator.config.AgentConfig;
import com.mypj.aipassagecreator.model.dto.article.ArticleState;
import com.mypj.aipassagecreator.model.enums.SseMessageTypeEnum;
import com.mypj.aipassagecreator.utils.GsonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 文章智能体编排器
 * <p>
 * 整个创作流程编译成<b>一张</b> StateGraph，在两个人工决策点前用 {@code interruptBefore} 暂停：
 *
 * <pre>
 * START → title_generator → ⏸ → outline_generator → ⏸ → content_generator
 *       → image_analyzer → parallel_image_generator → content_merger → END
 * </pre>
 *
 * 执行位置由 MysqlSaver 持久化到 MySQL（threadId 即 taskId），因此用户在任意一个
 * 中断点停留多久、期间服务是否重启，都能用 {@code RunnableConfig.resume()} 从断点继续。
 * <p>
 * 与「三张独立图」相比的差别：图只有一份定义，阶段的先后关系由图本身表达，
 * 不再靠调用方按顺序调三个方法。中断点由 {@code CompileConfig.interruptBefore} 声明，
 * 属于静态中断；如果以后要在运行时动态决定是否暂停，可让节点实现
 * {@code InterruptableAction} 返回 {@code InterruptionMetadata}。
 */
@Service
@Slf4j
public class ArticleAgentOrchestrator {

    @Resource
    private AgentConfig agentConfig;
    @Resource
    private TitleGeneratorAgent titleGeneratorAgent;
    @Resource
    private OutlineGeneratorAgent outlineGeneratorAgent;
    @Resource
    private ContentGeneratorAgent contentGeneratorAgent;
    @Resource
    private ImageAnalyzerAgent imageAnalyzerAgent;
    @Resource
    private ParallelImageGenerator parallelImageGenerator;
    @Resource
    private ContentMergerAgent contentMergerAgent;
    @Resource
    private MysqlSaver mysqlSaver;
    @Resource
    private JdbcTemplate jdbcTemplate;

    // region 节点名常量

    private static final String NODE_TITLE = "title_generator";
    private static final String NODE_OUTLINE = "outline_generator";
    private static final String NODE_CONTENT = "content_generator";
    private static final String NODE_IMAGE_ANALYZER = "image_analyzer";
    private static final String NODE_PARALLEL_IMAGES = "parallel_image_generator";
    private static final String NODE_MERGER = "content_merger";

    // endregion

    // region 状态键常量

    private static final String KEY_TASK_ID = "taskId";
    private static final String KEY_TOPIC = "topic";
    private static final String KEY_STYLE = "style";
    private static final String KEY_USER_DESCRIPTION = "userDescription";
    private static final String KEY_MAIN_TITLE = "mainTitle";
    private static final String KEY_SUB_TITLE = "subTitle";
    private static final String KEY_TITLE_OPTIONS = "titleOptions";
    private static final String KEY_OUTLINE = "outline";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CONTENT_WITH_PLACEHOLDERS = "contentWithPlaceholders";
    private static final String KEY_IMAGE_REQUIREMENTS = "imageRequirements";
    private static final String KEY_IMAGES = "images";
    private static final String KEY_FULL_CONTENT = "fullContent";
    private static final String KEY_ENABLED_IMAGE_METHODS = "enabledImageMethods";

    // endregion

    // region 对外入口（三次调用，同一个 threadId）

    /**
     * 第一次执行：生成标题方案，随后停在 outline_generator 之前。
     */
    public void startGeneration(ArticleState state, Consumer<String> streamHandler) {
        log.info("图执行-首次（生成标题方案）开始, taskId={}", state.getTaskId());

        StreamHandlerContext.set(streamHandler);
        try {
            Map<String, Object> inputs = new HashMap<>();
            inputs.put(KEY_TASK_ID, state.getTaskId());
            inputs.put(KEY_TOPIC, state.getTopic());
            inputs.put(KEY_STYLE, state.getStyle());

            OverAllState result = invoke(inputs, threadConfig(state.getTaskId()), "标题方案生成");

            List<ArticleState.TitleOption> titleOptions = readState(result, KEY_TITLE_OPTIONS,
                    new TypeToken<List<ArticleState.TitleOption>>() {});
            if (titleOptions == null || titleOptions.isEmpty()) {
                throw new RuntimeException("标题方案生成失败：结果为空");
            }

            state.setTitleOptions(titleOptions);
            streamHandler.accept(SseMessageTypeEnum.AGENT1_COMPLETE.getValue());
            log.info("图执行-首次完成（已停在中断点1），标题方案数量={}", titleOptions.size());

        } finally {
            StreamHandlerContext.clear();
        }
    }

    /**
     * 第二次执行：带着用户选定的标题从断点1恢复，生成大纲后停在 content_generator 之前。
     */
    public void resumeWithTitle(ArticleState state, Consumer<String> streamHandler) {
        log.info("图执行-续跑（生成大纲）开始, taskId={}", state.getTaskId());

        StreamHandlerContext.set(streamHandler);
        try {
            ArticleState.TitleResult title = state.getTitle();
            if (title == null) {
                throw new RuntimeException("续跑生成大纲失败：缺少已选定的标题");
            }

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(KEY_MAIN_TITLE, title.getMainTitle());
            stateUpdate.put(KEY_SUB_TITLE, title.getSubTitle());
            stateUpdate.put(KEY_USER_DESCRIPTION,
                    state.getUserDescription() == null ? "" : state.getUserDescription());

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(state.getTaskId())
                    .resume()
                    .addStateUpdate(stateUpdate)
                    .build();

            // 用户输入同时作为 invoke 的 inputs 传入。
            // 实测：只放进 addStateUpdate 时，Checkpoint 里的状态没有被合并，
            // 大纲节点读不到 mainTitle 直接失败；作为 inputs 传才会进入状态合并流程。
            OverAllState result = invoke(stateUpdate, config, "大纲生成");

            ArticleState.OutlineResult outline = readState(result, KEY_OUTLINE,
                    ArticleState.OutlineResult.class);
            if (outline == null || outline.getSections() == null || outline.getSections().isEmpty()) {
                throw new RuntimeException("大纲生成失败：结果为空");
            }

            state.setOutline(outline);
            log.info("图执行-续跑完成（已停在中断点2），大纲段数={}", outline.getSections().size());

        } finally {
            StreamHandlerContext.clear();
        }
    }

    /**
     * 第三次执行：带着用户确认的大纲从断点2恢复，跑完正文、配图、合并直到 END。
     */
    public void resumeWithOutline(ArticleState state, Consumer<String> streamHandler) {
        log.info("图执行-续跑（生成正文+配图）开始, taskId={}", state.getTaskId());

        StreamHandlerContext.set(streamHandler);
        try {
            if (state.getOutline() == null) {
                throw new RuntimeException("续跑生成正文失败：缺少已确认的大纲");
            }

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(KEY_OUTLINE, state.getOutline());

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(state.getTaskId())
                    .resume()
                    .addStateUpdate(stateUpdate)
                    .build();

            // 同上：用户确认的大纲必须作为 inputs 传入才会合并进图状态
            OverAllState result = invoke(stateUpdate, config, "正文+配图生成");

            String contentWithPlaceholders = readState(result, KEY_CONTENT_WITH_PLACEHOLDERS, String.class);
            String content = readState(result, KEY_CONTENT, String.class);
            List<ArticleState.ImageRequirement> imageRequirements = readState(result, KEY_IMAGE_REQUIREMENTS,
                    new TypeToken<List<ArticleState.ImageRequirement>>() {});
            List<ArticleState.ImageResult> images = readState(result, KEY_IMAGES,
                    new TypeToken<List<ArticleState.ImageResult>>() {});
            String fullContent = readState(result, KEY_FULL_CONTENT, String.class);

            if (contentWithPlaceholders != null) {
                state.setContent(contentWithPlaceholders);
            } else if (content != null) {
                state.setContent(content);
            }
            streamHandler.accept(SseMessageTypeEnum.AGENT3_COMPLETE.getValue());

            if (imageRequirements != null) {
                state.setImageRequirements(imageRequirements);
                streamHandler.accept(SseMessageTypeEnum.AGENT4_COMPLETE.getValue());
            }
            if (images != null) {
                state.setImages(images);
                streamHandler.accept(SseMessageTypeEnum.AGENT5_COMPLETE.getValue());
            }
            if (fullContent != null) {
                state.setFullContent(fullContent);
                streamHandler.accept(SseMessageTypeEnum.MERGE_COMPLETE.getValue());
            }

            log.info("图执行-续跑完成（已到 END），正文长度={}, 图片数={}",
                    contentWithPlaceholders != null ? contentWithPlaceholders.length() : 0,
                    images != null ? images.size() : 0);

        } finally {
            StreamHandlerContext.clear();
        }
    }

    /**
     * 释放某个任务在图里的执行检查点。
     * <p>
     * 任务进入终态（成功或失败）后调用。
     * <p>
     * 注意框架的 {@code release()} 只是把 graph_thread.is_released 置为 1，
     * <b>不会删除</b> graph_checkpoint 里的数据。实测一个任务会留下 7~9 条记录，
     * 每条都含完整的 state JSON，长期运行表会持续膨胀。因此这里补一次物理清理。
     * <p>
     * 表结构关系：{@code graph_thread.thread_name} 存的是我们传入的 taskId，
     * {@code graph_thread.thread_id} 是框架内部生成的 UUID，
     * {@code graph_checkpoint.thread_id} 引用的正是这个 UUID。
     *
     * @param taskId 任务ID（即 threadId）
     */
    public void releaseCheckpoint(String taskId) {
        try {
            mysqlSaver.release(RunnableConfig.builder().threadId(taskId).build());

            jdbcTemplate.update(
                    "DELETE FROM graph_checkpoint WHERE thread_id IN " +
                            "(SELECT thread_id FROM graph_thread WHERE thread_name = ?)", taskId);
            jdbcTemplate.update("DELETE FROM graph_thread WHERE thread_name = ?", taskId);

            log.info("已释放并清理图执行检查点, taskId={}", taskId);
        } catch (Exception e) {
            // 释放失败不影响业务结果，只记日志
            log.warn("释放图执行检查点失败, taskId={}", taskId, e);
        }
    }

    // endregion

    // region 内部执行

    /**
     * 统一的执行入口：编译图、invoke、把异常包装成业务语义。
     */
    private OverAllState invoke(Map<String, Object> inputs, RunnableConfig config, String actionName) {
        try {
            CompiledGraph graph = compileArticleGraph();
            Optional<OverAllState> result = graph.invoke(inputs, config);
            if (result.isEmpty()) {
                throw new RuntimeException(actionName + "失败：执行结果为空");
            }
            return result.get();
        } catch (Exception e) {
            log.error("图执行失败: {}, taskId={}", actionName, config.threadId().orElse("unknown"), e);
            throw new RuntimeException(actionName + "失败: " + e.getMessage(), e);
        }
    }

    private RunnableConfig threadConfig(String taskId) {
        return RunnableConfig.builder().threadId(taskId).build();
    }

    /**
     * 编译合并后的整张图，并绑定检查点保存器与两个中断点。
     */
    private CompiledGraph compileArticleGraph() throws GraphStateException {
        StateGraph graph = new StateGraph(createKeyStrategyFactory())
                .addNode(NODE_TITLE, node_async(titleGeneratorAgent))
                .addNode(NODE_OUTLINE, node_async(outlineGeneratorAgent))
                .addNode(NODE_CONTENT, node_async(contentGeneratorAgent))
                .addNode(NODE_IMAGE_ANALYZER, node_async(imageAnalyzerAgent))
                .addNode(NODE_PARALLEL_IMAGES, node_async(parallelImageGenerator))
                .addNode(NODE_MERGER, node_async(contentMergerAgent))
                .addEdge(START, NODE_TITLE)
                .addEdge(NODE_TITLE, NODE_OUTLINE)
                .addEdge(NODE_OUTLINE, NODE_CONTENT)
                .addEdge(NODE_CONTENT, NODE_IMAGE_ANALYZER)
                .addEdge(NODE_IMAGE_ANALYZER, NODE_PARALLEL_IMAGES)
                .addEdge(NODE_PARALLEL_IMAGES, NODE_MERGER)
                .addEdge(NODE_MERGER, END);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(mysqlSaver).build())
                // 两个人工决策点：在「生成大纲」和「生成正文」之前暂停
                .interruptBefore(NODE_OUTLINE, NODE_CONTENT)
                .recursionLimit(agentConfig.getMaxIterations())
                .build();

        return graph.compile(compileConfig);
    }

    // endregion

    // region 工具

    /**
     * 创建状态键策略工厂（所有键都使用替换策略）
     */
    private KeyStrategyFactory createKeyStrategyFactory() {
        return () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(KEY_TASK_ID, new ReplaceStrategy());
            strategies.put(KEY_TOPIC, new ReplaceStrategy());
            strategies.put(KEY_STYLE, new ReplaceStrategy());
            strategies.put(KEY_USER_DESCRIPTION, new ReplaceStrategy());
            strategies.put(KEY_MAIN_TITLE, new ReplaceStrategy());
            strategies.put(KEY_SUB_TITLE, new ReplaceStrategy());
            strategies.put(KEY_TITLE_OPTIONS, new ReplaceStrategy());
            strategies.put(KEY_OUTLINE, new ReplaceStrategy());
            strategies.put(KEY_CONTENT, new ReplaceStrategy());
            strategies.put(KEY_CONTENT_WITH_PLACEHOLDERS, new ReplaceStrategy());
            strategies.put(KEY_IMAGE_REQUIREMENTS, new ReplaceStrategy());
            strategies.put(KEY_IMAGES, new ReplaceStrategy());
            strategies.put(KEY_FULL_CONTENT, new ReplaceStrategy());
            strategies.put(KEY_ENABLED_IMAGE_METHODS, new ReplaceStrategy());
            return strategies;
        };
    }

    /**
     * 从图状态中读取值并转换为目标类型（泛型版本）。
     * <p>
     * Spring AI Alibaba 的 Graph 在节点间传递状态时会对值做序列化/反序列化，
     * {@code NodeAction} 返回的自定义对象读回来可能已经变成 {@code Map}，
     * 所以不能直接强转，统一用 Gson 做一次转换兜底。
     *
     * @param state     图状态
     * @param key       状态键
     * @param typeToken 目标类型（支持泛型）
     * @return 转换结果，缺失或转换失败返回 null
     */
    private <T> T readState(OverAllState state, String key, TypeToken<T> typeToken) {
        return state.value(key)
                .map(v -> {
                    try {
                        return GsonUtils.<T>fromJson(GsonUtils.toJson(v), typeToken);
                    } catch (Exception e) {
                        log.warn("状态值转换失败, key={}, actualType={}", key, v.getClass().getName(), e);
                        return null;
                    }
                })
                .orElse(null);
    }

    /**
     * 从图状态中读取值并转换为目标类型（普通类型版本）。
     */
    private <T> T readState(OverAllState state, String key, Class<T> clazz) {
        return state.value(key)
                .map(v -> {
                    if (clazz.isInstance(v)) {
                        return clazz.cast(v);
                    }
                    try {
                        return GsonUtils.fromJson(GsonUtils.toJson(v), clazz);
                    } catch (Exception e) {
                        log.warn("状态值转换失败, key={}, actualType={}", key, v.getClass().getName(), e);
                        return null;
                    }
                })
                .orElse(null);
    }

    // endregion
}
