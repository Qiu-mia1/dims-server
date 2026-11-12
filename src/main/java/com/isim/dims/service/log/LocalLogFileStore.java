package com.isim.dims.service.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件系统实现：日志目录已经挂在进程可见的路径上时用它（最快，无网络往返）。
 *
 * <p>无状态、线程安全，注册为单例即可。
 */
@Slf4j
@Component
public class LocalLogFileStore implements LogFileStore {

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String label() {
        return "local-filesystem";
    }

    @Override
    public boolean isDirectory(String path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.isDirectory(Path.of(path));
        } catch (RuntimeException e) {
            // 非法路径（含 NUL 等）当作不存在
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.exists(Path.of(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<String> list(String path) {
        List<String> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(Path.of(path))) {
            stream.forEach(p -> result.add(p.toString()));
        } catch (IOException | RuntimeException e) {
            log.warn("[日志下载] 本地目录列举失败：{} ({})", path, e.getMessage());
        }
        return result;
    }

    @Override
    public long size(String path) {
        try {
            return Files.size(Path.of(path));
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        return Files.newInputStream(Path.of(path));
    }
}
