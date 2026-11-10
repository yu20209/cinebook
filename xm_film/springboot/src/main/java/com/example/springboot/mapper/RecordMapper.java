package com.example.springboot.mapper;

import com.example.springboot.common.BaseMapper;
import com.example.springboot.entity.Record;
import org.apache.ibatis.annotations.Param;

public interface RecordMapper extends BaseMapper<Record> {

    Record selectByIdForUpdate(Integer id);

    /**
     * 下单路径对场次行加共享锁：多个下单事务可同时持有；
     * 与 {@code updateScoped} 的 {@code selectByIdForUpdate}（排他锁）互斥，
     * 消除"改影厅删旧座位"与"下单锁旧座位"之间的竞争窗口。
     */
    Record selectByIdForShare(Integer id);

    /** 同一影厅内与 [start, end) 存在时段重叠的未停售场次数量；id 为编辑时的排除项 */
    int countRoomOverlap(@Param("roomId") Integer roomId,
                         @Param("id") Integer id,
                         @Param("start") String start,
                         @Param("end") String end);

    int countByFilmId(Integer filmId);

    int countByCinemaId(Integer cinemaId);

    int countByRoomId(Integer roomId);
}
