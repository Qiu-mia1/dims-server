package com.isim.dims.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Python 算法引擎执行器
 *
 * <p>通过 ProcessBuilder 调用 Python 脚本，支持两种调用模式：</p>
 * <ul>
 *   <li><b>JSON 参数模式</b>（默认）：输入序列化为临时 JSON 文件，
 *       以 {@code python3 {scriptName} --input {tempJsonFile}} 调用，
 *       执行完成后自动清理临时文件。适用约定为 {@code --input args.json}
 *       的脚本（parse_rosbag / auto_tag / extract_* 等）。</li>
 *   <li><b>raw-argv 模式</b>：argv 为完整命令行参数列表（不含 python3），
 *       原样拼接到命令行直接执行，不写临时 JSON 文件。适用 argparse 标志位
 *       风格的脚本（dims-python/scene_extraction/ 下的 bag_to_unified_json.py /
 *       scene_detector.py —— 其 {@code --input} 语义为场景数据文件路径，
 *       与 JSON 参数模式的 args.json 约定不兼容，见 TASK-020 阻断项）。</li>
 * </ul>
 *
 * <p>两种模式共用 stdout/stderr drain、超时终止与退出码检查逻辑。</p>
 *
 * <p>配置项：</p>
 * <ul>
 *   <li>dims.python.script-dir — 脚本目录，默认 ../dims-python</li>
 *   <li>dims.python.timeout-sec — 默认超时秒数，默认 300</li>
 * </ul>
 * @feature: infra
 */
@Slf4j
@Component
public class PythonExecutor {

//    @Value("${dims.python.script-dir:../dims-python}")
    @Value("${dims.python.script-dir:/opt/dims-python}")
    private String scriptDir;

    @Value("${dims.python.timeout-sec:300}")
    private int defaultTimeoutSec;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Drain an InputStream completely in a background thread and return its content as a String.
     * This prevents pipe buffer deadlocks when stderr is separate from stdout.
     */
    private static Thread drainStream(InputStream stream, StringBuilder target) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (target.length() > 0) target.append("\n");
                    target.append(line);
                }
            } catch (IOException e) {
                // stream closed — expected on timeout/destroy
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 执行 Python 脚本，使用默认超时时间
     *
     * @param scriptName 脚本名，如 "parse_rosbag.py"
     * @param args       JSON 序列化参数
     * @return stdout 输出字符串
     */
    public String execute(String scriptName, Map<String, Object> args) {
        return execute(scriptName, args, defaultTimeoutSec);
    }

    /**
     * 执行 Python 脚本，工作目录为默认脚本目录
     *
     * @param scriptName 脚本名，如 "parse_rosbag.py"
     * @param args       JSON 序列化参数
     * @param timeoutSec 超时秒数
     * @return stdout 输出字符串（已 trim）
     * @throws RuntimeException 脚本执行失败、超时或退出码非零时抛出
     */
    public String execute(String scriptName, Map<String, Object> args, int timeoutSec) {
        return execute(scriptName, args, timeoutSec, null);
    }

    /**
     * 执行 Python 脚本（JSON 参数模式，指定工作目录）
     *
     * @param scriptName 脚本名，如 "parse_rosbag.py"
     * @param args       JSON 序列化参数
     * @param timeoutSec 超时秒数
     * @param workingDir 工作目录（ProcessBuilder directory）；null 时用默认脚本目录
     * @return stdout 输出字符串（已 trim）
     * @throws RuntimeException 脚本执行失败、超时或退出码非零时抛出
     */
    public String execute(String scriptName, Map<String, Object> args, int timeoutSec, String workingDir) {
        Path tempFile = null;
        try {
            // 1. 输入参数序列化为临时 JSON 文件
            tempFile = Files.createTempFile("dims_python_", ".json");
            objectMapper.writeValue(tempFile.toFile(), args);
            log.debug("PythonExecutor: args written to temp file: {} ({} bytes)",
                    tempFile, Files.size(tempFile));

            // 2. 构建命令行并执行（runProcess 负责 drain / 超时 / 退出码检查）
            return runProcess(List.of("python3", scriptName, "--input", tempFile.toString()),
                    workingDir != null ? workingDir : scriptDir, timeoutSec);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to execute Python script [" + scriptName + "]: " + e.getMessage(), e);
        } finally {
            // 3. 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                    log.debug("PythonExecutor: cleaned temp file: {}", tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file: {}", tempFile, e);
                }
            }
        }
    }

    /**
     * 执行 Python 脚本（raw-argv 模式）
     *
     * <p>argv 为完整命令行参数列表（不含 python3），原样拼接到命令行执行，
     * 不写临时 JSON 文件。用于 argparse 标志位风格的脚本：</p>
     * <pre>
     * executeRaw(List.of("bag_to_unified_json.py", "--ego", "/path/ego.json",
     *                    "--objects", "/path/perception", "--bag-id", "xxx",
     *                    "--output", "/path/scene_data.json"),
     *            "../dims-python/scene_extraction", 300)
     * </pre>
     *
     * @param argv       完整命令行参数列表（不含 python3），如
     *                   ["bag_to_unified_json.py", "--ego", "/path/ego.json", ...]
     * @param workingDir 工作目录（ProcessBuilder directory），null 时用默认脚本目录
     * @param timeoutSec 超时秒数
     * @return stdout 输出字符串（已 trim）
     * @throws RuntimeException 脚本执行失败、超时或退出码非零时抛出
     */
    public String executeRaw(List<String> argv, String workingDir, int timeoutSec) {
        List<String> cmd = new ArrayList<>(argv.size() + 1);
        cmd.add("python3");
        cmd.addAll(argv);
        return runProcess(cmd, workingDir, timeoutSec);
    }

    /**
     * 执行进程并统一处理 stdout/stderr drain、超时终止与退出码检查
     *
     * @param cmd        完整命令行（含 python3）
     * @param workingDir 工作目录，null 时用默认脚本目录
     * @param timeoutSec 超时秒数
     * @return stdout 输出字符串（已 trim）
     * @throws RuntimeException 脚本执行失败、超时或退出码非零时抛出
     */
    private String runProcess(List<String> cmd, String workingDir, int timeoutSec) {
        // 日志标签：脚本名（cmd[1]），如 "bag_to_unified_json.py"
        String label = cmd.size() > 1 ? cmd.get(1) : "python3";
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir != null ? workingDir : scriptDir));
            pb.redirectErrorStream(false); // 分离 stdout 和 stderr

            log.info("PythonExecutor: executing {} in dir={}, timeout={}s",
                    cmd, pb.directory().getAbsolutePath(), timeoutSec);

            Process process = pb.start();

            // 在分离线程中消耗 stdout 和 stderr，防止管道缓冲区满导致死锁
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            Thread stdoutThread = drainStream(process.getInputStream(), stdoutBuf);
            Thread stderrThread = drainStream(process.getErrorStream(), stderrBuf);

            // 等待完成，超时则强制终止
            boolean completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new RuntimeException(
                        "Python script [" + label + "] timed out after " + timeoutSec + "s");
            }

            // 等待 drain 线程完成
            stdoutThread.join(5000);
            stderrThread.join(5000);

            String stdout = stdoutBuf.toString();
            String stderr = stderrBuf.toString();

            // stderr 不为空时输出日志（非关键信息）
            if (!stderr.isEmpty()) {
                log.info("PythonExecutor: script [{}] stderr ({} chars): {}",
                        label, stderr.length(),
                        stderr.length() > 500 ? stderr.substring(0, 500) + "..." : stderr);
            }

            // 检查退出码
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Python script [{}] failed with exit code {}: stderr={}",
                        label, exitCode,
                        stderr.length() > 500 ? stderr.substring(0, 500) + "..." : stderr);
                String errorMsg = stdout.isEmpty() ? stderr : stdout;
                throw new RuntimeException(
                        "Python script [" + label + "] exited with code " + exitCode
                                + ", output: " + (errorMsg.length() > 500 ? errorMsg.substring(0, 500) + "..." : errorMsg));
            }

            log.info("PythonExecutor: script [{}] completed successfully (exit=0, {} chars output)",
                    label, stdout.length());
            return stdout.trim();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Python script [" + label + "] was interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to execute Python script [" + label + "]: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unexpected error executing Python script [" + label + "]", e);
        }
    }
}
