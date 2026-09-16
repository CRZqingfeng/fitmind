package com.fitmind.course.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.media.MediaClient;
import com.fitmind.api.dto.media.MediaDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.course.constants.CourseConstants;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.domain.dto.CataSaveDTO;
import com.fitmind.course.domain.dto.CourseMediaDTO;
import com.fitmind.course.domain.po.CourseCatalogue;
import com.fitmind.course.domain.po.CourseCatalogueDraft;
import com.fitmind.course.domain.po.CourseDraft;
import com.fitmind.course.domain.vo.CataVO;
import com.fitmind.course.mapper.CourseCatalogueDraftMapper;
import com.fitmind.course.mapper.CourseCatalogueMapper;
import com.fitmind.course.service.ICourseCatalogueDraftService;
import com.fitmind.course.service.ICourseCataSubjectService;
import com.fitmind.course.service.ICourseDraftService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 训练目录服务实现。
 *
 * <p><b>本类最容易写错的两个地方</b>（都是常见实现踩过的坑）：</p>
 * <ol>
 *   <li>{@link #packageCatalogue} —— 保存是全量覆盖（先删后插），
 *       但必须从内存 map 里复用老对象，否则 mediaId 和动作参数会被清空</li>
 *   <li>{@link #checkIndex} —— 已上架的训练日不能删、序号不能改</li>
 * </ol>
 */
@Slf4j
@Service
public class CourseCatalogueDraftServiceImpl
        extends ServiceImpl<CourseCatalogueDraftMapper, CourseCatalogueDraft>
        implements ICourseCatalogueDraftService {

    private static final int TYPE_CHAPTER = CourseConstants.CataType.CHAPTER;
    private static final int TYPE_SECTION = CourseConstants.CataType.SECTION;
    private static final int TYPE_PRACTICE = CourseConstants.CataType.PRACTICE;

    private final ICourseDraftService courseDraftService;
    /**
     * ⚠️ 依赖方向：目录 → 题目关联（单向）。
     * 题目关联服务那边只注 Mapper、不注目录服务，所以不成环。
     */
    private final ICourseCataSubjectService courseCataSubjectService;
    private final CourseCatalogueMapper courseCatalogueMapper;
    private final MediaClient mediaClient;

    public CourseCatalogueDraftServiceImpl(ICourseDraftService courseDraftService,
                                           CourseCatalogueMapper courseCatalogueMapper,
                                           MediaClient mediaClient,
                                           ICourseCataSubjectService courseCataSubjectService) {
        this.courseDraftService = courseDraftService;
        this.courseCatalogueMapper = courseCatalogueMapper;
        this.mediaClient = mediaClient;
        this.courseCataSubjectService = courseCataSubjectService;
    }

    // ==================== 保存目录 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long courseId, List<CataSaveDTO> dtos, Integer step) {
        if (CollectionUtils.isEmpty(dtos)) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATAS_EMPTY);
        }
        // 1. 按序号升序
        List<CataSaveDTO> sorted = dtos.stream()
                .sorted(Comparator.comparing(CataSaveDTO::getIndex))
                .collect(Collectors.toList());

        // 2. 已上架的目录（用于结构保护校验）
        List<CourseCatalogue> shelfCatalogues = courseCatalogueMapper.selectList(
                new LambdaQueryWrapper<CourseCatalogue>().eq(CourseCatalogue::getCourseId, courseId));

        // 3. 校验序号 + 已上架结构保护
        checkIndex(sorted, shelfCatalogues);

        // 4. 组装（★ 关键：从 map 里复用老对象，保住 mediaId 与动作参数）
        List<CourseCatalogueDraft> drafts = packageCatalogue(courseId, sorted, shelfCatalogues);

        // 5. 删除旧草稿目录（先删后插）
        if (step != null && step == CourseConstants.CourseStep.SUBJECT) {
            baseMapper.deleteByCourseId(courseId, Arrays.asList(TYPE_CHAPTER, TYPE_SECTION, TYPE_PRACTICE));
        } else {
            baseMapper.deleteByCourseId(courseId, Arrays.asList(TYPE_CHAPTER, TYPE_SECTION));
        }

        // 6. 批量保存
        saveOrUpdateBatch(drafts);

        // 7. 清理「刚被删掉的那些节点」上挂的题目关联。
        //    必须在目录落库之后做——它以最新的节点列表为准。
        //    否则删掉一个训练动作后，它下面的题目关联就成了孤儿：
        //    接口不报错、按节点也查不到，只在管理端全量查询时冒出来。
        courseCataSubjectService.deleteNotInCataIdList(courseId);

        // 8. 推进填写进度到「目录」
        courseDraftService.updateStep(courseId, CourseConstants.CourseStep.CATALOGUE);
        log.info("保存训练目录：courseId={}, 训练日 {} 个, 目录记录 {} 条",
                courseId, sorted.size(), drafts.size());
    }

    /**
     * 校验训练日的序号与已上架结构。
     */
    private void checkIndex(List<CataSaveDTO> dtos, List<CourseCatalogue> shelfCatalogues) {
        // 1. 序号不能重复
        long distinctCount = dtos.stream().map(CataSaveDTO::getIndex).distinct().count();
        if (distinctCount < dtos.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATAS_INDEX_REPEAT);
        }
        // 2. 序号不能跳号（已按升序排，最大值必须等于数量）
        if (dtos.get(dtos.size() - 1).getIndex() > dtos.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATAS_INDEX_INTERRUPTED);
        }
        // 3. 每个训练日下面必须有训练动作
        for (CataSaveDTO dto : dtos) {
            if (CollectionUtils.isEmpty(dto.getSections())) {
                throw new BizIllegalException(String.format(CourseErrorInfo.Msg.CATAS_CHAPTER_WITHOUT_ACTION, dto.getName()));
            }
        }
        // 4. 已上架结构保护
        if (CollectionUtils.isEmpty(shelfCatalogues)) {
            return;
        }
        Map<Long, Integer> submitIndexMap = new HashMap<>();
        for (CataSaveDTO dto : dtos) {
            if (dto.getId() != null) {
                submitIndexMap.put(dto.getId(), dto.getIndex());
            }
        }
        for (CourseCatalogue shelf : shelfCatalogues) {
            if (shelf.getType() == null || shelf.getType() != TYPE_CHAPTER) {
                continue;
            }
            Integer index = submitIndexMap.get(shelf.getId());
            if (index == null) {
                throw new BizIllegalException(
                        String.format(CourseErrorInfo.Msg.CATAS_CHAPTER_DELETED, shelf.getName()));
            }
            if (!index.equals(shelf.getCIndex())) {
                throw new BizIllegalException(
                        String.format(CourseErrorInfo.Msg.CATAS_CHAPTER_INDEX_LOCKED, shelf.getName()));
            }
        }
    }

    /**
     * 组装要落库的目录实体。
     *
     * <p><b>数据优先级：本次提交 > 草稿 > 已上架</b></p>
     *
     * <p>为什么不能直接拿 DTO 建实体：接口提交的 {@link CataSaveDTO} 只有
     * id / 名称 / 序号 / 类型，<b>没有 mediaId 和动作参数</b>。
     * 所以先用一个 map 把「已上架 + 草稿」的完整数据装进去，
     * 遍历提交数据时从 map 里取出老对象复用，<b>只覆盖基本信息</b>，
     * 其余业务字段（mediaId、组数、次数…）原样保留。</p>
     *
     * <p>一句话：删的是数据库记录，数据在内存里先保住了。</p>
     */
    private List<CourseCatalogueDraft> packageCatalogue(Long courseId, List<CataSaveDTO> dtos,
                                                         List<CourseCatalogue> shelfCatalogues) {
        // 1. 先放已上架的数据（最完整）
        Map<Long, CourseCatalogueDraft> savedMap = new HashMap<>();
        if (!CollectionUtils.isEmpty(shelfCatalogues)) {
            for (CourseCatalogue c : shelfCatalogues) {
                savedMap.put(c.getId(), BeanUtil.copyProperties(c, CourseCatalogueDraft.class));
            }
        }
        // 2. 再放草稿的数据（同一 id 会覆盖上面的，草稿比上架的新）
        List<CourseCatalogueDraft> existDrafts = baseMapper.getByCourseId(courseId);
        if (!CollectionUtils.isEmpty(existDrafts)) {
            for (CourseCatalogueDraft d : existDrafts) {
                savedMap.put(d.getId(), d);
            }
        }

        // 3. 遍历本次提交，能复用就复用，只更新基本信息
        List<CourseCatalogueDraft> result = new ArrayList<>();
        for (CataSaveDTO dto : dtos) {
            Long chapterId = dto.getId() == null ? IdUtil.getSnowflakeNextId() : dto.getId();
            CourseCatalogueDraft chapter = savedMap.get(chapterId);
            if (chapter == null) {
                chapter = new CourseCatalogueDraft();
                chapter.setId(chapterId);
                chapter.setCreater(UserContext.getUser());
            }
            chapter.setCataBaseInfo(dto.getIndex(), dto.getName(), TYPE_CHAPTER, 0L, courseId);
            result.add(chapter);

            // 训练日下面的动作
            int actionIndex = 1;
            for (CataSaveDTO section : dto.getSections()) {
                Long actionId = section.getId() == null ? IdUtil.getSnowflakeNextId() : section.getId();
                CourseCatalogueDraft action = savedMap.get(actionId);
                if (action == null) {
                    action = new CourseCatalogueDraft();
                    action.setId(actionId);
                    action.setCreater(UserContext.getUser());
                }
                Integer type = section.getType() == null ? TYPE_SECTION : section.getType();
                action.setCataBaseInfo(actionIndex++, section.getName(), type, chapterId, courseId);
                result.add(action);
            }
        }
        return result;
    }

    // ==================== 查询目录 ====================

    @Override
    public List<CataVO> queryCourseCatalogues(Long courseId, Boolean see) {
        if (Boolean.TRUE.equals(see)) {
            // 用户端优先看已上架的目录
            List<CourseCatalogue> shelfList = courseCatalogueMapper.selectList(
                    new LambdaQueryWrapper<CourseCatalogue>().eq(CourseCatalogue::getCourseId, courseId));
            if (!CollectionUtils.isEmpty(shelfList)) {
                return buildTreeFromShelf(shelfList);
            }
        }
        return buildTree(baseMapper.getByCourseId(courseId), false);
    }

    /**
     * 组装目录树。
     *
     * @param fromShelf 目录数据取自正式表（true）还是草稿表（false）。
     *                  <b>题目数必须跟着同一张表取</b>——上架后关联数据搬到了正式表，
     *                  还去草稿表数字段就会是 0（"明明刚配了题，列表显示没有"）。
     */
    private List<CataVO> buildTree(List<CourseCatalogueDraft> list, boolean fromShelf) {
        if (CollectionUtils.isEmpty(list)) {
            return new ArrayList<>();
        }
        // 小测题目数：一次 GROUP BY 拿全。
        // 逐个节点查是 N+1（8 个训练日 × 6 个动作 = 48 次查询）
        Map<Long, Integer> subjectNumMap = courseCataSubjectService.countByCataIds(
                list.stream().map(CourseCatalogueDraft::getId).collect(Collectors.toList()), fromShelf);
        List<CataVO> result = new ArrayList<>();
        Map<Long, CataVO> chapterMap = new LinkedHashMap<>();

        // 先放训练日
        list.stream()
                .filter(c -> c.getType() != null && c.getType() == TYPE_CHAPTER)
                .sorted(Comparator.comparing(CourseCatalogueDraft::getCIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(c -> {
                    CataVO vo = toVO(c, subjectNumMap);
                    vo.setSections(new ArrayList<>());
                    chapterMap.put(c.getId(), vo);
                    result.add(vo);
                });
        // 再把动作挂到对应的训练日下
        list.stream()
                .filter(c -> c.getType() != null && c.getType() == TYPE_SECTION)
                .sorted(Comparator.comparing(CourseCatalogueDraft::getCIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(c -> {
                    CataVO parent = chapterMap.get(c.getParentCatalogueId());
                    if (parent != null) {
                        parent.getSections().add(toVO(c, subjectNumMap));
                    }
                });
        return result;
    }

    private List<CataVO> buildTreeFromShelf(List<CourseCatalogue> list) {
        // 正式表结构相同，转成草稿实体复用上面那套组装逻辑
        List<CourseCatalogueDraft> converted = list.stream()
                .map(c -> BeanUtil.copyProperties(c, CourseCatalogueDraft.class))
                .collect(Collectors.toList());
        return buildTree(converted, true);
    }

    private CataVO toVO(CourseCatalogueDraft c, Map<Long, Integer> subjectNumMap) {
        CataVO vo = new CataVO();
        vo.setId(c.getId());
        vo.setIndex(c.getCIndex());
        vo.setName(c.getName());
        vo.setType(c.getType());
        vo.setMediaId(c.getMediaId());
        vo.setMediaName(c.getVideoName());
        vo.setMediaDuration(c.getMediaDuration());
        vo.setTrailer(c.getTrailer() != null && c.getTrailer() == 1);
        // 动作参数
        vo.setSets(c.getSets());
        vo.setReps(c.getReps());
        vo.setRestSeconds(c.getRestSeconds());
        vo.setWeight(c.getWeight());
        vo.setDurationSeconds(c.getDurationSeconds());
        vo.setActionType(c.getActionType());
        vo.setEquipment(c.getEquipment());
        vo.setTargetMuscles(c.getTargetMuscles());
        // 该节点配了几道小测（0 = 没配）
        vo.setSubjectNum(subjectNumMap.getOrDefault(c.getId(), 0));
        return vo;
    }

    // ==================== 保存动作信息（绑视频 + 动作参数） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveMediaInfo(Long courseId, List<CourseMediaDTO> dtos) {
        if (CollectionUtils.isEmpty(dtos)) {
            return;
        }
        // 1. 校验这些动作都属于当前课程（防止挂到别人的课程上）
        List<Long> cataIds = dtos.stream().map(CourseMediaDTO::getCataId).distinct().collect(Collectors.toList());
        checkSectionIds(cataIds, courseId);

        // 2. 必须先保存目录
        CourseDraft draft = courseDraftService.getById(courseId);
        if (draft == null || draft.getStep() == null
                || draft.getStep() < CourseConstants.CourseStep.CATALOGUE) {
            throw new BizIllegalException(CourseErrorInfo.Msg.MEDIA_SAVE_BEFORE_CATAS);
        }

        // 3. 校验绑定的媒资真实存在（防止动作指向一个不存在的视频，用户点开看不了）
        List<Long> mediaIds = dtos.stream()
                .map(CourseMediaDTO::getMediaId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        for (Long mediaId : mediaIds) {
            MediaDTO media = mediaClient.queryMediaById(mediaId);
            if (media == null) {
                throw new BizIllegalException(String.format(CourseErrorInfo.Msg.MEDIA_NOT_EXISTS, mediaId));
            }
        }

        // 3. 更新每个动作的视频与参数
        List<CourseCatalogueDraft> updates = new ArrayList<>();
        for (CourseMediaDTO dto : dtos) {
            CourseCatalogueDraft update = new CourseCatalogueDraft();
            update.setId(dto.getCataId());
            update.setMediaId(dto.getMediaId());
            update.setVideoName(dto.getVideoName());
            update.setMediaDuration(dto.getMediaDuration());
            update.setTrailer(Boolean.TRUE.equals(dto.getTrailer()) ? 1 : 0);
            // ★ 动作参数
            update.setSets(dto.getSets());
            update.setReps(dto.getReps());
            update.setRestSeconds(dto.getRestSeconds());
            update.setWeight(dto.getWeight());
            update.setDurationSeconds(dto.getDurationSeconds());
            update.setActionType(dto.getActionType());
            update.setEquipment(dto.getEquipment());
            update.setTargetMuscles(dto.getTargetMuscles());
            update.setUpdater(UserContext.getUser());
            updates.add(update);
        }
        updateBatchById(updates, 500);

        // 4. 推进到「视频」步
        courseDraftService.updateStep(courseId, CourseConstants.CourseStep.MEDIA);

        // 5. ★ 重算每个训练日的视频总时长（前端要显示"本节共 32 分钟"）
        List<CourseCatalogueDraft> chapters = buildChapterDurations(courseId);
        if (!chapters.isEmpty()) {
            updateBatchById(chapters, 500);
        }
        log.info("保存训练动作信息：courseId={}, 动作 {} 个", courseId, dtos.size());
    }

    private void checkSectionIds(List<Long> cataIds, Long courseId) {
        List<CourseCatalogueDraft> actions = list(new LambdaQueryWrapper<CourseCatalogueDraft>()
                .in(CourseCatalogueDraft::getId, cataIds)
                .eq(CourseCatalogueDraft::getCourseId, courseId)
                .eq(CourseCatalogueDraft::getType, TYPE_SECTION));
        if (actions.size() != cataIds.size()) {
            throw new BizIllegalException(CourseErrorInfo.Msg.MEDIA_CATA_NOT_IN_COURSE);
        }
    }

    /** 把「训练日时长统计」转成待更新的实体列表 */
    private List<CourseCatalogueDraft> buildChapterDurations(Long courseId) {
        Map<Long, Integer> durationMap = calculateMediaDuration(courseId);
        List<CourseCatalogueDraft> chapters = new ArrayList<>();
        durationMap.forEach((chapterId, duration) -> {
            CourseCatalogueDraft c = new CourseCatalogueDraft();
            c.setId(chapterId);
            c.setMediaDuration(duration);
            chapters.add(c);
        });
        return chapters;
    }

    // ==================== 上架校验与同步 ====================

    @Override
    public void checkCataInfoImplated(Long courseId) {
        List<CourseCatalogueDraft> list = list(new LambdaQueryWrapper<CourseCatalogueDraft>()
                .eq(CourseCatalogueDraft::getCourseId, courseId));
        if (CollectionUtils.isEmpty(list)) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATAS_EMPTY);
        }
        // ★ 每个训练动作都必须绑定视频：训练动作不配视频，用户根本不知道怎么练
        for (CourseCatalogueDraft c : list) {
            if (c.getType() != null && c.getType() == TYPE_SECTION
                    && (c.getVideoName() == null || c.getVideoName().isBlank())) {
                throw new BizIllegalException(
                        String.format(CourseErrorInfo.Msg.CATAS_ACTION_WITHOUT_MEDIA, c.getName()));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void copyToShelf(Long courseId, Boolean isFirstShelf) {
        List<CourseCatalogueDraft> drafts = baseMapper.getByCourseId(courseId);
        if (CollectionUtils.isEmpty(drafts)) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATAS_EMPTY);
        }
        // 1. 先清空正式表的旧目录
        //    （不能只做 saveOrUpdate：本次删掉的动作，正式表里的旧记录会残留）
        courseCatalogueMapper.delete(new LambdaQueryWrapper<CourseCatalogue>()
                .eq(CourseCatalogue::getCourseId, courseId));
        // 2. 全量插入
        for (CourseCatalogueDraft d : drafts) {
            CourseCatalogue c = BeanUtil.copyProperties(d, CourseCatalogue.class);
            c.setId(d.getId());
            c.setDeleted(0);
            courseCatalogueMapper.insert(c);
        }
        // 3. 删除草稿
        baseMapper.deleteByCourseId(courseId, Arrays.asList(TYPE_CHAPTER, TYPE_SECTION, TYPE_PRACTICE));
        log.info("训练目录上架：courseId={}, 共 {} 条", courseId, drafts.size());
    }

    @Override
    public void copyFromShelf(Long courseId) {
        int n = baseMapper.insertFromCourseCatalogue(courseId);
        log.info("训练目录回到草稿：courseId={}, 共 {} 条", courseId, n);
    }

    // ==================== 统计 ====================

    @Override
    public Map<Long, Integer> calculateMediaDuration(Long courseId) {
        List<CourseCatalogueDraft> actions = list(new LambdaQueryWrapper<CourseCatalogueDraft>()
                .eq(CourseCatalogueDraft::getCourseId, courseId)
                .eq(CourseCatalogueDraft::getType, TYPE_SECTION));
        if (CollectionUtils.isEmpty(actions)) {
            return new HashMap<>();
        }
        // 按所属训练日分组，累加视频时长
        return actions.stream().collect(Collectors.groupingBy(
                CourseCatalogueDraft::getParentCatalogueId,
                Collectors.summingInt(a -> a.getMediaDuration() == null ? 0 : a.getMediaDuration())));
    }

    @Override
    public Integer totalSectionNums(Long courseId) {
        return Math.toIntExact(count(new LambdaQueryWrapper<CourseCatalogueDraft>()
                .eq(CourseCatalogueDraft::getCourseId, courseId)
                .eq(CourseCatalogueDraft::getType, TYPE_SECTION)));
    }

    @Override
    public List<Long> queryCataIdsOfCourse(Long courseId, List<Integer> types) {
        List<CourseCatalogueDraft> list = list(new LambdaQueryWrapper<CourseCatalogueDraft>()
                .eq(CourseCatalogueDraft::getCourseId, courseId)
                .in(!CollectionUtils.isEmpty(types), CourseCatalogueDraft::getType, types));
        return list.stream().map(CourseCatalogueDraft::getId).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCourseId(Long courseId) {
        // 草稿 + 正式一起删
        baseMapper.deleteByCourseId(courseId, Arrays.asList(TYPE_CHAPTER, TYPE_SECTION, TYPE_PRACTICE));
        courseCatalogueMapper.delete(new LambdaQueryWrapper<CourseCatalogue>()
                .eq(CourseCatalogue::getCourseId, courseId));
        log.info("删除课程训练目录：courseId={}", courseId);
    }
}
