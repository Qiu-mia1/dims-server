# ============================================================
# DIMS 后端镜像：Java 应用 + Python 算法运行时（合一镜像）
#
# 构建上下文需包含 dims-server/ 与 dims-python/）：
#   docker build -f dims-server/Dockerfile -t dims-server:<tag> .
# ============================================================

# ---------- 阶段 1：Maven 构建 ----------
FROM 10.130.212.155/isim/dims-server:32

# 使用 root 用户启动
USER root

# 算法脚本（约 35MB，含 bag_reader/、scene_extraction/、config/）
COPY /nfs/dims/dims-python /opt/dims-python

# 应用 jar
COPY /nfs/dims/dims-1.0.0-SNAPSHOT.jar /app/dims.jar

# JAVA_OPTS 供容器内存调优（如 -XX:MaxRAMPercentage=75.0）
ENTRYPOINT ["sh", "-c", "exec java -jar /app/dims.jar"]
