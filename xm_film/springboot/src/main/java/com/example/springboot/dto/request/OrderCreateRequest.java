package com.example.springboot.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OrderCreateRequest {
    @NotNull(message = "放映场次不能为空")
    private Integer recordId;

    @NotBlank(message = "座位不能为空")
    private String seat;

    /**
     * 幂等标识（必填）：客户端为一次"提交订单"动作生成一次，重试时沿用同一个值。
     * 服务端以 UNIQUE(user_id, request_id) 兜底，重复提交只落一笔订单，
     * 且同一标识携带不同场次/座位时会被判定为请求内容冲突。
     */
    @NotBlank(message = "缺少幂等标识 requestId，请刷新页面重试")
    @Size(max = 64, message = "幂等标识过长")
    private String requestId;
}
