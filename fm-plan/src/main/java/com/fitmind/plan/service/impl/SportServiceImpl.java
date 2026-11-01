package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.DietErrorInfo;
import com.fitmind.plan.domain.dto.SportLogCreateDTO;
import com.fitmind.plan.domain.po.Sport;
import com.fitmind.plan.domain.po.SportLog;
import com.fitmind.plan.domain.query.SportQuery;
import com.fitmind.plan.domain.vo.SportDictVO;
import com.fitmind.plan.domain.vo.SportLogResultVO;
import com.fitmind.plan.domain.vo.SportVO;
import com.fitmind.plan.mapper.SportLogMapper;
import com.fitmind.plan.mapper.SportMapper;
import com.fitmind.plan.service.ISportService;
import com.fitmind.plan.utils.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 运动实现。
 *
 * <p><b>本类最容易写错的地方</b>：消耗热量的算法。
 * {@code kcal = kcal_per_30min × 分钟 / 30} —— 分母是 <b>30</b>，不是 60。
 * 写成 /60 的后果是"跑 30 分钟只消耗 130 千卡"，
 * 而这种错不会报错、也不会被页面发现，只会在用户觉得"怎么吃这么点还胖"时才暴露。</p>
 *
 * <p>★ 运动记录**不合并**（与饮食相反）：跑两次 30 分钟是两条记录，
 * 各自有时长，用户能单独删掉其中一条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SportServiceImpl implements ISportService {

    private final SportMapper sportMapper;
    private final SportLogMapper sportLogMapper;

    /** 估算基准时长（分钟）：kcal_per_30min 就是"30 分钟"的消耗 */
    private static final int BASE_MINUTES = 30;

    @Override
    public PageDTO<SportVO> queryPage(SportQuery query) {
        LoginUser.id();

        String category = trimToNull(query.getCategory());
        String keyword = trimToNull(query.getKeyword());

        Page<Sport> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<Sport> result = sportMapper.selectPage(page, Wrappers.<Sport>lambdaQuery()
                .eq(Sport::getStatus, 1)
                .eq(category != null, Sport::getCategory, category)
                .like(keyword != null, Sport::getName, keyword)
                .orderByAsc(Sport::getSort)
                .orderByAsc(Sport::getId));
        return PageDTO.of(result, this::toVo);
    }

    @Override
    public SportDictVO queryDict() {
        List<String> categories = orderCategories(
                sportMapper.selectObjs(new QueryWrapper<Sport>().select("DISTINCT category").eq("status", 1)));

        List<SportDictVO.EntryVO> entries = new ArrayList<>();
        entries.add(new SportDictVO.EntryVO("common", "常见", "entry"));
        entries.add(new SportDictVO.EntryVO("search", "搜索", "search"));
        for (String c : categories) {
            entries.add(new SportDictVO.EntryVO(c, c, "category"));
        }
        // ★ 「动作库」是**跳页面**的入口（去训练侧的动作库），不是筛选条件。
        //   标成 kind=link，前端据此决定"跳转"还是"改筛选"。
        entries.add(new SportDictVO.EntryVO("/plan/exercise-lib", "动作库", "link"));

        SportDictVO vo = new SportDictVO();
        vo.setCategories(categories);
        vo.setEntries(entries);
        return vo;
    }

    @Override
    public SportLogResultVO createLog(SportLogCreateDTO dto) {
        Long userId = LoginUser.id();
        LocalDate date = dto.getDate() == null ? LocalDate.now() : dto.getDate();

        Sport sport = sportMapper.selectById(dto.getSportId());
        if (sport == null || !Integer.valueOf(1).equals(sport.getStatus())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.SPORT_NOT_EXISTS);
        }
        int minutes = dto.getDurationMin();
        if (minutes < 1 || minutes > 600) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.SPORT_DURATION_ILLEGAL);
        }

        // ★ 分母是 30（kcal_per_30min 的基准），HALF_UP 与库里其他热量口径一致
        int kcal = BigDecimal.valueOf(sport.getKcalPer30min())
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(BASE_MINUTES), 0, RoundingMode.HALF_UP)
                .intValue();

        SportLog record = new SportLog()
                .setUserId(userId)
                .setLogDate(date)
                .setSportId(sport.getId())
                // 快照：运动库以后改价，这条历史记录不变
                .setSportName(sport.getName())
                .setDurationMin(minutes)
                .setKcal(kcal);
        // 不合并：一次运动一行
        sportLogMapper.insert(record);

        log.info("记运动：userId={}, sport={}, {}分钟, {}千卡, logId={}",
                userId, sport.getName(), minutes, kcal, record.getId());
        return new SportLogResultVO(record.getId(), kcal, minutes + " 分钟 · 约 " + kcal + " 千卡");
    }

    @Override
    public void removeLog(Long id) {
        Long userId = LoginUser.id();
        SportLog record = sportLogMapper.selectById(id);
        if (record == null) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.SPORT_LOG_NOT_EXISTS);
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            throw new BizIllegalException(403, DietErrorInfo.Msg.SPORT_LOG_NOT_OWNER);
        }
        sportLogMapper.deleteById(id);
    }

    // ==================== 内部 ====================

    private SportVO toVo(Sport sport) {
        SportVO vo = new SportVO();
        vo.setId(sport.getId());
        vo.setName(sport.getName());
        vo.setCategory(sport.getCategory());
        vo.setKcalPer30min(sport.getKcalPer30min());
        vo.setKcalText(sport.getKcalPer30min() + " 千卡/30分钟");
        vo.setTags(List.of(sport.getCategory()));
        return vo;
    }

    private List<String> orderCategories(List<Object> raw) {
        List<String> found = raw.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.toList());
        found.sort((a, b) -> {
            int ia = DietConstants.SPORT_CATEGORY_ORDER.indexOf(a);
            int ib = DietConstants.SPORT_CATEGORY_ORDER.indexOf(b);
            if (ia < 0) {
                ia = Integer.MAX_VALUE;
            }
            if (ib < 0) {
                ib = Integer.MAX_VALUE;
            }
            return Integer.compare(ia, ib);
        });
        return found;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
