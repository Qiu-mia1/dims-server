package com.isim.dims.service.log;

import com.emc.ecs.nfsclient.nfs.io.Nfs3File;
import com.emc.ecs.nfsclient.nfs.io.NfsFileInputStream;
import com.emc.ecs.nfsclient.nfs.nfs3.Nfs3;
import com.emc.ecs.nfsclient.rpc.CredentialUnix;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NFS 直连实现：不依赖操作系统挂载点，按 NFSv3 协议直接读服务端导出目录。
 *
 * <p>为什么不用「pod 里挂 NFS」：{@code import_source_dir} 记的是 {@code /data/downloads/...}，
 * 那是<b>容器/节点视角</b>；DIMS pod 里未必有这条路，而日志又在 NFS 上。按协议直连只需
 * 服务端 2049/tcp 可达 + 一个导出路径，运维成本最低 —— 与 SIMS（{@code NfsUtil}）同一套做法。
 *
 * <p><b>连接复用</b>：{@code Nfs3} 构造即一次 MOUNT 往返（对现网实测约 350ms），而一次日志下载
 * 要发数十次 RPC（递归扫描 + 逐文件取大小），因此按 {@code host|export|uid|gid} 缓存实例。
 *
 * <p><b>为什么可以不加锁共用</b>（读源码确认，非推测）：
 * <ul>
 *   <li>{@code NetMgr} 是进程级单例，内部持有唯一的 {@code ChannelFactory} 与
 *       {@code ConcurrentHashMap<InetSocketAddress, Connection>} —— 同一台 NFS 服务端
 *       全局只有一条 TCP 连接，{@code Nfs3} 实例多建几个不会多开线程或连接；</li>
 *   <li>{@code Connection.sendAndWait} 用 XID 做请求/响应对应
 *       （{@code ConcurrentHashMap<Integer, ChannelFuture> _futureMap} +
 *       {@code _responseMap}），本身支持并发在途请求；</li>
 *   <li>{@code Nfs3} 挂载后字段（根 handle / server / export / 凭据 / 端口）不再变化，
 *       相当于不可变对象。</li>
 * </ul>
 * 所以不引入应用层锁：日志下载并发的场景（多人同时点「日志」）由库自身多路复用处理，
 * 加锁反而会让一次 17MB 的流式读阻塞其它请求。
 *
 * <p><b>失效自愈</b>：NFS 服务重启后缓存的文件句柄会失效，所有 RPC 开始报错。因此
 * 「判定目录」失败时把缓存丢掉，让下一次请求重新 MOUNT。
 *
 * <p>错误码约定（沿用本项目「异常消息前缀即错误码」的写法）：
 * 连不上/导出不合法 → {@code LOG_STORE_UNAVAILABLE}，由 controller 映射 503；
 * 单个条目读失败只降级（返回 false/空/0）并打 WARN，不影响其余文件。
 */
@Slf4j
@Component
public class NfsLogFileStore implements LogFileStore {

    @Value("${dims.download.log.nfs.host:}")
    private String host;

    @Value("${dims.download.log.nfs.export-dir:/nfs/dims}")
    private String exportDir;

    /** NFSv3 的 AUTH_UNIX 凭据；-2 即 default/nobody，与 SIMS 读取侧一致（只读场景够用）。 */
    @Value("${dims.download.log.nfs.uid:-2}")
    private int uid;

    @Value("${dims.download.log.nfs.gid:-2}")
    private int gid;

    /** 已建立的挂载，key = host|export|uid|gid。 */
    private final Map<String, Nfs3> mounts = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return "nfs";
    }

    @Override
    public String label() {
        return configured() ? "nfs://" + host + ":" + exportDir : "nfs(unconfigured)";
    }

    /** 是否配置了 NFS 来源；未配置时调用方应跳过本 store 而不是报错。 */
    public boolean configured() {
        return host != null && !host.isBlank() && exportDir != null && !exportDir.isBlank();
    }

    @Override
    public boolean isDirectory(String path) {
        if (!configured() || path == null || path.isBlank()) {
            return false;
        }
        try {
            Nfs3File file = file(path);
            return file.exists() && file.isDirectory();
        } catch (Exception e) {
            // 可能是句柄失效（NFS 重启）——丢缓存让下次重挂，避免一直坏下去
            invalidate();
            log.warn("[日志下载] NFS 判定目录失败：{}{} ({})", label(), path, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean exists(String path) {
        if (!configured() || path == null || path.isBlank()) {
            return false;
        }
        try {
            return file(path).exists();
        } catch (Exception e) {
            log.warn("[日志下载] NFS 判定存在失败：{}{} ({})", label(), path, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> list(String path) {
        List<String> result = new ArrayList<>();
        if (!configured() || path == null || path.isBlank()) {
            return result;
        }
        String base = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        try {
            List<Nfs3File> children = file(path).listFiles();
            if (children == null) {
                return result;
            }
            for (Nfs3File child : children) {
                String name = child.getName();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                result.add(base + "/" + name);
            }
        } catch (Exception e) {
            log.warn("[日志下载] NFS 列举失败：{}{} ({})", label(), path, e.getMessage());
        }
        return result;
    }

    @Override
    public long size(String path) {
        if (!configured() || path == null || path.isBlank()) {
            return 0L;
        }
        try {
            return file(path).length();
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public InputStream open(String path) throws IOException {
        // 单参构造：缓冲区取服务端 fsinfo.rtpref（现网通常 1MB），不用自己指定
        return new NfsFileInputStream(file(path));
    }

    private Nfs3File file(String path) throws IOException {
        return new Nfs3File(mounted(), path);
    }

    /** 取（或建立）挂载；失败抛 {@code LOG_STORE_UNAVAILABLE}，由 controller 映射 503。 */
    private Nfs3 mounted() {
        if (!configured()) {
            throw new RuntimeException("LOG_STORE_UNAVAILABLE: NFS not configured"
                    + " (set dims.download.log.nfs.host or use dims.download.log.source=local)");
        }
        String key = host + "|" + exportDir + "|" + uid + "|" + gid;
        Nfs3 cached = mounts.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (mounts) {
            Nfs3 again = mounts.get(key);
            if (again != null) {
                return again;
            }
            long start = System.currentTimeMillis();
            try {
                Nfs3 created = new Nfs3(host, exportDir, new CredentialUnix(uid, gid, null), 3);
                mounts.put(key, created);
                log.info("[日志下载] NFS 挂载成功：host={} export={} uid={} gid={} 耗时 {} ms",
                        host, exportDir, uid, gid, System.currentTimeMillis() - start);
                return created;
            } catch (Exception e) {
                // 不缓存失败：服务端可能只是重启中，下次请求仍应重试
                throw new RuntimeException("LOG_STORE_UNAVAILABLE: nfs mount failed host=" + host
                        + " export=" + exportDir + " (" + e.getMessage() + ")");
            }
        }
    }

    /** 丢掉缓存的挂载（句柄失效或连接异常时调用），下次请求重新 MOUNT。 */
    private void invalidate() {
        mounts.clear();
    }
}
