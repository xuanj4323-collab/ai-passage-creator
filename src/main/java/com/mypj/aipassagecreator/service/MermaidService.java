package com.mypj.aipassagecreator.service;


import cn.hutool.core.io.FileUtil;
import cn.hutool.system.SystemUtil;
import com.mypj.aipassagecreator.config.MermaidConfig;
import com.mypj.aipassagecreator.model.dto.image.ImageData;
import com.mypj.aipassagecreator.model.dto.image.ImageRequest;
import com.mypj.aipassagecreator.model.enums.ImageMethodEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.mypj.aipassagecreator.constant.ArticleConstant.PICSUM_URL_TEMPLATE;

/**
 * Mermaid 流程图生成服务
 * 使用 mermaid-cli 将 Mermaid 代码转换为图片
 *
 */
@Service
@Slf4j
public class MermaidService implements ImageSearchService {

    @Resource
    private MermaidConfig mermaidConfig;

    @Override
    public String searchImage(String keywords) {
        // 此方法已废弃，请使用 getImageData()
        return null;
    }

    @Override
    public String getImage(ImageRequest request) {
        // 此方法已废弃，请使用 getImageData()
        return null;
    }

    @Override
    public ImageData getImageData(ImageRequest request) {
        // 优先使用 prompt（Mermaid 代码），否则使用 keywords
        String mermaidCode = request.getEffectiveParam(true);
        return generateDiagramData(mermaidCode);
    }

    /**
     * 生成 Mermaid 图表数据
     *
     * @param mermaidCode Mermaid 代码
     * @return 图片字节数据，生成失败返回 null
     */
    public ImageData generateDiagramData(String mermaidCode) {
        if (mermaidCode == null || mermaidCode.trim().isEmpty()) {
            log.warn("Mermaid 代码为空");
            return null;
        }

        File tempInputFile = null;
        File tempOutputFile = null;

        try {
            // 创建临时输入文件
            tempInputFile = FileUtil.createTempFile("mermaid_input_", ".mmd", true);
            FileUtil.writeUtf8String(mermaidCode, tempInputFile);

            // 创建临时输出文件
            String outputExtension = "." + mermaidConfig.getOutputFormat();
            tempOutputFile = FileUtil.createTempFile("mermaid_output_", outputExtension, true);

            // 转换为图片
            convertMermaidToImage(tempInputFile, tempOutputFile);

            // 检查输出文件
            if (!tempOutputFile.exists() || tempOutputFile.length() == 0) {
                log.error("Mermaid CLI 执行失败，输出文件不存在或为空");
                return null;
            }

            // 读取图片字节数据
            byte[] imageBytes = FileUtil.readBytes(tempOutputFile);
            String mimeType = getMimeType(mermaidConfig.getOutputFormat());

            log.info("Mermaid 图表生成成功, size={} bytes", imageBytes.length);
            return ImageData.fromBytes(imageBytes, mimeType);

        } catch (Exception e) {
            log.error("Mermaid 图表生成异常", e);
            return null;
        } finally {
            // 清理临时文件
            if (tempInputFile != null) {
                FileUtil.del(tempInputFile);
            }
            if (tempOutputFile != null) {
                FileUtil.del(tempOutputFile);
            }
        }
    }

    /**
     * 根据输出格式获取 MIME 类型
     */
    private String getMimeType(String format) {
        return switch (format.toLowerCase()) {
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            case "pdf" -> "application/pdf";
            default -> "image/png";
        };
    }

    /**
     * 调用 Mermaid CLI 转换为图片
     */
    private void convertMermaidToImage(File inputFile, File outputFile) {
        try {
            // 根据操作系统解析命令
            String command = resolveCliCommand();

            // 构建命令行参数
            String cmdLine = String.format("%s -i %s -o %s -b %s",
                    command,
                    inputFile.getAbsolutePath(),
                    outputFile.getAbsolutePath(),
                    mermaidConfig.getBackgroundColor()
            );

            // mmdc 12 起已移除 -w/--width（传了会直接报 unknown option 并退出，什么都不生成），
            // 改用 --size：对 svg 是设置 max-width，对 png 是限制最大边长
            if (mermaidConfig.getWidth() != null && mermaidConfig.getWidth() > 0) {
                cmdLine += " --size " + mermaidConfig.getWidth();
            }

            log.info("执行 Mermaid CLI 命令: {}", cmdLine);

            // 带超时执行：原实现用 RuntimeUtil.execForStr，完全没有超时保护，
            // mmdc 一旦卡住（Chromium 起不来、渲染进入死循环），调用线程会永久阻塞。
            // 这条链路跑在 imageExecutor（core=4）上，累积几次就能把配图线程池占满。
            // 超时时间取自 mermaid.timeout —— 这个配置项此前从未被读取过。
            long timeoutMs = mermaidConfig.getTimeout() != null ? mermaidConfig.getTimeout() : 30_000L;
            String result = execWithTimeout(cmdLine, timeoutMs);

            log.debug("Mermaid CLI 执行结果: {}", result);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("执行 Mermaid CLI 被中断", e);
            throw new RuntimeException("Mermaid CLI 执行被中断", e);
        } catch (Exception e) {
            log.error("执行 Mermaid CLI 失败", e);
            throw new RuntimeException("Mermaid CLI 执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 带超时执行外部命令。
     * <p>
     * 三个必须处理的点：
     * <ol>
     *   <li><b>并发读走输出</b>：子进程的 stdout 写满管道缓冲区后会阻塞在 write 上，
     *       此时父进程还在 waitFor，两边互相等待形成死锁。这是 {@link Process} 最经典的坑。</li>
     *   <li><b>超时后杀进程树</b>：Windows 上 mmdc.cmd 会派生 node 子进程，
     *       {@code destroyForcibly} 只能结束 cmd.exe，node 会残留，
     *       必须用 {@code taskkill /T} 按进程树清理。</li>
     *   <li><b>中断标志恢复</b>：捕获 InterruptedException 后重新设置中断状态，
     *       否则上层线程池无法感知取消信号。</li>
     * </ol>
     *
     * @param cmdLine   命令行
     * @param timeoutMs 超时毫秒数
     * @return 命令的标准输出（stderr 已合并进来）
     */
    private String execWithTimeout(String cmdLine, long timeoutMs) throws IOException, InterruptedException {
        boolean windows = SystemUtil.getOsInfo().isWindows();
        ProcessBuilder pb = windows
                ? new ProcessBuilder("cmd.exe", "/c", cmdLine)
                : new ProcessBuilder("sh", "-c", cmdLine);
        // stderr 并入 stdout，避免两路管道都要单独消费
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道会提前关闭，属正常情况
            }
        }, "mmdc-output-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            killProcessTree(process);
            throw new RuntimeException("Mermaid CLI 执行超时（" + timeoutMs + "ms），已强制终止");
        }

        // 进程已退出，等读取线程把剩余输出刷完（给个短上限，避免极端情况挂住）
        reader.join(2000);
        return output.toString();
    }

    /**
     * 结束进程及其派生的子进程。
     * <p>
     * Windows 上 mmdc.cmd → node，{@code destroyForcibly} 只能杀掉 cmd.exe，
     * node 会变成孤儿进程继续占 CPU 和内存，所以先用 taskkill /T 清理整棵树。
     */
    private void killProcessTree(Process process) {
        if (SystemUtil.getOsInfo().isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("taskkill 调用失败，回退到 destroyForcibly: {}", e.getMessage());
            }
        }
        process.destroyForcibly();
    }

    @Override
    public ImageMethodEnum getMethod() {
        return ImageMethodEnum.MERMAID;
    }

    @Override
    public String getFallbackImage(int position) {
        return String.format(PICSUM_URL_TEMPLATE, position);
    }

    @Override
    public boolean isAvailable() {
        try {
            // 检查 mermaid-cli 是否已安装（同样带超时，避免 --version 卡住）
            // 超时给 25 秒：实测 mmdc --version 本身就要 5.3 秒（要拉起 Node 和 Chromium 做自检），
            // 机器负载高时会更久，阈值压太紧会把「可用」误判成「不可用」并触发降级。
            String command = resolveCliCommand();
            String version = execWithTimeout(command + " --version", 25_000L);
            log.info("Mermaid CLI 版本: {}", version.trim());
            return version != null && !version.isBlank();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Mermaid CLI 检测被中断");
            return false;
        } catch (Exception e) {
            log.warn("Mermaid CLI 不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 mmdc 可执行命令名。
     * <p>
     * 配置项 mermaid.cli-command 的默认值是 Windows 下的 mmdc.cmd。
     * 之前"检测"和"执行"两处各自用三元表达式判断平台，结果互相矛盾：
     * 执行时 Linux 硬编码用 mmdc，检测时 Linux 却去读配置里的 mmdc.cmd，
     * 导致 Linux 上明明装了 mermaid-cli，isAvailable() 却恒为 false。
     * 这里收敛成一个方法，两处共用，避免再次跑偏。
     */
    private String resolveCliCommand() {
        String configured = mermaidConfig.getCliCommand();
        if (SystemUtil.getOsInfo().isWindows()) {
            return (configured == null || configured.isBlank()) ? "mmdc.cmd" : configured;
        }
        // 非 Windows：配置若仍是 Windows 的默认值（或为空），说明没针对平台改过，
        // 用 mermaid-cli 在 Linux/Mac 上的真实命令名兜底；显式配置了则尊重配置。
        if (configured == null || configured.isBlank() || "mmdc.cmd".equals(configured)) {
            return "mmdc";
        }
        return configured;
    }
}

