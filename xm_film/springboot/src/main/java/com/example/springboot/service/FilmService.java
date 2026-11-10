package com.example.springboot.service;

import com.example.springboot.common.BaseMapper;
import com.example.springboot.common.BaseService;
import com.example.springboot.common.RedisCacheService;
import com.example.springboot.common.enums.ErrorCode;
import com.example.springboot.dto.response.FilmTypeJoinView;
import com.example.springboot.entity.Film;
import com.example.springboot.entity.Type;
import com.example.springboot.exception.CustomException;
import com.example.springboot.mapper.FilmMapper;
import com.example.springboot.mapper.OrderedMapper;
import com.example.springboot.mapper.RecordMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.pagehelper.PageInfo;
import com.github.pagehelper.page.PageMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 影片的增删改查，外加类型关联的同步读写与按票房/评分的榜单查询。
 * 类型详情在此一次性批量填充，避免逐条影片各查一轮。
 */
// 同类调用 BaseService 的 REQUIRED 方法，外层事务已存在；规则说明见 audit/sonarqube-report.md §9.1
@SuppressWarnings("java:S6809")
@Service
@Transactional(readOnly = true)
public class FilmService extends BaseService<Film> {

    private final FilmMapper filmMapper;

    private RecordMapper recordMapper;

    private OrderedMapper orderedMapper;

    /** 缓存组件（可选注入：单测直接 new 时不带缓存，自动走数据库） */
    private RedisCacheService cacheService;

    public FilmService(FilmMapper filmMapper) {
        this.filmMapper = filmMapper;
    }

    /** 删除守卫的计数源，setter 注入以免破坏直接 new 的既有用法 */
    @Autowired
    private void setReferenceMappers(RecordMapper recordMapper, OrderedMapper orderedMapper) {
        this.recordMapper = recordMapper;
        this.orderedMapper = orderedMapper;
    }

    @Autowired(required = false)
    public void setCacheService(RedisCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    protected BaseMapper<Film> mapper() {
        return filmMapper;
    }

    /**
     * 列表缓存键：把影响结果集的查询字段拼进来，并带上版本号。
     * 任一字段为空时用 "-" 占位，保证同一条件稳定命中同一个 key。
     */
    private String filmListCacheKey(Film film) {
        if (cacheService == null) {
            return null;
        }
        String ver = cacheService.filmVersion();
        if (ver == null) {
            return null;
        }
        String cond = film == null ? "-" : String.join("_",
                String.valueOf(film.getTitle()),
                String.valueOf(film.getTypeId()),
                String.valueOf(film.getAreaId()),
                String.valueOf(film.getStatus()),
                String.valueOf(film.getYear()),
                String.valueOf(film.getTopNum()));
        return "film:list:" + ver + ":" + Math.abs(cond.hashCode());
    }

    /** 影片写操作后让缓存整体失效（版本号 +1）；必须等事务提交，回滚时缓存保持不变 */
    private void invalidateFilmCache() {
        if (cacheService != null) {
            cacheService.bumpFilmVersionAfterCommit();
        }
    }

    @Override
    public List<Film> selectAll(Film film) {
        // 缓存键带上查询条件与版本号：条件不同不会互相污染，版本号 +1 后旧键自动失效
        String key = filmListCacheKey(film);
        if (cacheService != null) {
            List<Film> cached = cacheService.get(key, new TypeReference<List<Film>>() {});
            if (cached != null) {
                return cached;
            }
        }
        List<Film> list = mapper().selectAll(film);
        fillFilmTypes(list);
        if (cacheService != null) {
            // 提交后再写缓存：事务回滚时不留下这次查询的结果
            cacheService.putAfterCommit(key, list, null);
        }
        return list;
    }

    @Override
    public Film selectById(Integer id) {
        String key = null;
        if (cacheService != null) {
            String ver = cacheService.filmVersion();
            if (ver != null) {
                key = "film:detail:" + ver + ":" + id;
                Film cached = cacheService.get(key, new TypeReference<Film>() {});
                if (cached != null) {
                    return cached;
                }
            }
        }
        Film film = mapper().selectById(id);
        if (film != null) {
            fillFilmTypes(List.of(film));
            if (cacheService != null) {
                cacheService.putAfterCommit(key, film, null);
            }
        }
        return film;
    }

    public List<Film> selectByCinema(Integer cinemaId, Integer filmId) {
        return filmMapper.selectByCinema(cinemaId, filmId);
    }

    public List<Film> selectByTitle(String title) {
        return filmMapper.selectByTitle(title);
    }

    /** 下拉选项：只有 id/title，不填类型、不做票房聚合 */
    public List<Film> selectOptions() {
        return filmMapper.selectOptions();
    }

    public PageInfo<Film> selectPage(Film film, Integer pageNum, Integer pageSize) {
        PageMethod.startPage(pageNum, pageSize);
        List<Film> list = filmMapper.selectAll(film);
        fillFilmTypes(list);
        return new PageInfo<>(list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Film film) {
        mapper().insert(film);
        if (film.getTypeIds() != null && !film.getTypeIds().isEmpty()) {
            filmMapper.insertFilmTypes(film.getId(), film.getTypeIds());
        }
        invalidateFilmCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Film film) {
        // 先判存在再落库：片不存在时基类直接 NOT_FOUND，避免下面按 film.getId()
        // 写出一批指向空片的 film_type 关联行
        super.update(film);
        if (film.getTypeIds() != null) {
            filmMapper.deleteFilmTypesByFilmId(film.getId());
            if (!film.getTypeIds().isEmpty()) {
                filmMapper.insertFilmTypes(film.getId(), film.getTypeIds());
            }
        }
        invalidateFilmCache();
    }

    /**
     * 删除影片：先锁影片行，再数排片/订单引用，最后删除。
     *
     * <p>与 {@link CinemaService#deleteGuarded} 同一口径：守卫从 Controller 下沉到
     * 事务内，消除「数到 0 → 有人插入排片 → DELETE」的窗口；批量按 id 升序加锁，
     * 避免 [A,B] 与 [B,A] 交叉等待死锁。完整理由见 CinemaService 的同名方法。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteGuarded(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Integer> ordered = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        for (Integer id : ordered) {
            if (filmMapper.selectByIdForUpdate(id) == null) {
                throw new CustomException(ErrorCode.NOT_FOUND, "影片不存在，可能已被删除");
            }
            int records = recordMapper.countByFilmId(id);
            int orders = orderedMapper.countByFilmId(id);
            if (records > 0 || orders > 0) {
                throw new CustomException(ErrorCode.BUSINESS_CONFLICT,
                        "影片 ID " + id + " 已有 " + records + " 个排片、" + orders
                                + " 笔订单，无法删除；如需下架请将状态改为「停止上映」");
            }
        }
        deleteBatch(ordered);
        invalidateFilmCache();
    }

    public List<Film> getBoxOfficeTop(Film film) {
        List<Film> result = filmMapper.selectBoxOfficeTop(resolveTopNum(film));
        fillFilmTypes(result);
        return result;
    }

    public List<Film> getMarkTop(Film film) {
        List<Film> result = filmMapper.selectMarkTop(resolveTopNum(film));
        fillFilmTypes(result);
        return result;
    }

    /** 榜单条数：缺省 10。非正数会让 SQL 拼出 `LIMIT -1`/`LIMIT 0` 直接报错或空榜，故一并归到缺省 */
    private static int resolveTopNum(Film film) {
        Integer topNum = film.getTopNum();
        return topNum != null && topNum > 0 ? topNum : 10;
    }

    private void fillFilmTypes(List<Film> filmList) {
        if (filmList == null || filmList.isEmpty()) {
            return;
        }
        List<Integer> filmIds = filmList.stream().map(Film::getId).toList();
        List<FilmTypeJoinView> rows = filmMapper.selectFilmTypeJoin(filmIds);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<Integer, List<Integer>> filmTypeIdMap = new LinkedHashMap<>();
        Map<Integer, List<Type>> filmTypeMap = new LinkedHashMap<>();
        for (FilmTypeJoinView row : rows) {
            Integer filmId = row.getFilmId();
            Integer typeId = row.getTypeId();
            String typeTitle = row.getTypeTitle();
            filmTypeIdMap.computeIfAbsent(filmId, k -> new ArrayList<>()).add(typeId);
            filmTypeMap.computeIfAbsent(filmId, k -> new ArrayList<>()).add(new Type(typeId, typeTitle));
        }
        for (Film film : filmList) {
            film.setTypeIds(filmTypeIdMap.get(film.getId()));
            film.setTypeList(filmTypeMap.get(film.getId()));
        }
    }
}
