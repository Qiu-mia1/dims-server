package com.isim.dims.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 重新导入（重新转换）请求参数
 *
 * 对应 POST /v1/data-slices/reimport 的请求体。
 * 客户端提交一组 sliceId，服务端逐个执行：查询 → 删除 → 以 importSourceDir 重新触发本地目录导入。
 * @feature: slice
 */
@Data
public class ReimportRequest {

    /** 需要重新导入的数据切片 ID 集合（必填） */
    @JsonProperty("slice_ids")
    @NotEmpty(message = "sliceIds is required")
    private List<String> sliceIds;
}