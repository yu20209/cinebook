package com.example.springboot.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderCreateRequest {
    @NotNull(message = "放映场次不能为空")
    private Integer recordId;

    @NotBlank(message = "座位不能为空")
    private String seat;

    /**
     * 幂等标识（可选）：客户端为一次"提交订单"动作生成一次，重试时沿用同一个值。
     * 服务端以 UNIQUE(user_id, request_id) 兜底，重复提交只落一笔订单。
     */
    private String requestId;
}
