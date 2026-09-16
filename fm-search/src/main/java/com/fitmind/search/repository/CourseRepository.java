package com.fitmind.search.repository;

import com.fitmind.search.domain.po.CourseDoc;
import com.fitmind.search.domain.query.CourseSearchQuery;

import java.util.List;

/**
 * 课程索引仓储。
 *
 * <p>把 ES 的 DSL 收在这一层，Service 只说"我要什么"，不用关心怎么查。</p>
 *
 * <p>接口方法<b>不声明 IOException</b>——实现类统一转成业务异常抛出，
 * 这样上层的每个调用点都不用写 try-catch。</p>
 */
public interface CourseRepository {

    /** 写入 / 覆盖一条文档（幂等：同 id 重复写入就是覆盖） */
    void save(CourseDoc doc);

    /** 删除一条文档 */
    void deleteById(Long id);

    /** 文档是否存在 */
    boolean exists(Long id);

    /** 关键词检索（含筛选、排序、分页、高亮） */
    SearchPage search(CourseSearchQuery query);

    /** 按关键词只取 id（供其他服务做联想/匹配） */
    List<Long> queryIdsByKeyword(String keyword, int size);

    /** 综合最优：评分 + 报名数加权 */
    List<CourseDoc> topBest(int size);

    /** 最新上架 */
    List<CourseDoc> topNew(int size);

    /** 免费好课 */
    List<CourseDoc> topFree(int size);

    /** 按分类 id 取课程（兴趣推荐用） */
    List<CourseDoc> topByCategories(List<Long> categoryIds, int size);

    /**
     * 检索结果（含高亮）。
     *
     * @param total     命中总数
     * @param docs      当前页文档
     * @param highlight 课程id -> 高亮后的课程名
     */
    record SearchPage(long total, List<CourseDoc> docs, java.util.Map<Long, String> highlight) {
        public static SearchPage empty() {
            return new SearchPage(0, List.of(), java.util.Map.of());
        }
    }
}
