package com.example.springboot.entity;

import com.example.springboot.common.Identifiable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 场次座位库存表：每个场次的每个座位都是独立行。
 * 座位级锁替代原"整场 record 行锁 + 订单 seat 字符串判重"的方案。
 *
 * <p>状态流转：0 可用 → 1 已锁定（待支付） → 2 已售出（已支付/待取票/已取票）
 *                        ↑                 ↓
 *                        └── 取消/超时释放 ─┘
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ScreeningSeat implements Serializable, Identifiable {
    private static final long serialVersionUID = 1L;

    private Integer id;
    private Integer recordId;
    private String seatCode;
    /** 0 可用、1 已锁定、2 已售出 */
    private Integer status;
    /** 当前锁定该座位的订单号（status=1 时有值） */
    private String lockOrderNo;
    /** 锁定过期时间（status=1 时有值） */
    private String lockExpireAt;
    private String createdAt;
    private String updatedAt;

    // ===== 状态常量 =====
    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_LOCKED = 1;
    public static final int STATUS_SOLD = 2;
}
