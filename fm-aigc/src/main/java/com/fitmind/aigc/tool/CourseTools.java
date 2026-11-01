package com.fitmind.aigc.tool;

import com.fitmind.aigc.client.SearchClient;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseCatalogueDTO;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 课程工具 —— AI 能"查平台上的课"。
 *
 * <p>★ <b>两个刻意的设计</b>：</p>
 *
 * <p>① <b>检索走 fm-search，不自己查业务库</b>。课程检索这件事 fm-search 已经做好了
 * （IK 分词、相关性排序、索引随上下架同步）。AI 再写一套 SQL 去查，等于把
 * "什么课程算相关"这件事做出第二份实现 —— 而两份实现早晚会不一致
 * （M4-3 那个"推荐减 110、下单减 100"就是这个味道）。</p>
 *
 * <p>② <b>课程 id 一律当作"用户输入"来校验</b>。模型给的 id 可能是不存在的、
 * 也可能是它编的。工具内先查存在性，不存在就返回一句"没找到"——
 * <b>而不是把异常抛出去：抛异常会直接打断对话，用户看到的是"系统错误"，
 * 而他其实只是问了一个课程名不对的问题。</b></p>
 *
 * <p>★★ <b>M6-8 第 2 期：这里曾经有一条"看似严谨、其实把路堵死"的约束</b>
 * （{@code #12}）。原话是"课程 id <b>必须来自 searchCourses 的结果</b>"，
 * 于是模型严格守约：<b>凡是用户手输的 id，一律不敢拿它去查</b> ——
 * 用户说"课程 535 有几节"，模型只能先去搜关键词，搜不到就回
 * "我没法确认这门课存在"。<b>连咨询这条路都走不通</b>，更别说下单。</p>
 *
 * <p>★ 修法不是"把校验删掉"，而是<b>把"合法 id 的来源"从"搜索"换成"核实"</b>：
 * <b>搜索只是核实的一种手段</b>，用户明确给出的 id 直接拿来核实就行。
 * "不许编造 id"这条底线一个字都没松 —— 编造的 id 仍然会在
 * {@link #queryCourseDetail} 里被"没找到"挡住。</p>
 */
@Slf4j
public class CourseTools {

    /** 目录渲染的字符预算：超了就**显式声明**截断，而不是悄悄切掉（见 {@link #renderCatalogues}） */
    private static final int CATALOGUE_CHAR_BUDGET = 1200;

    /**
     * ★ M6-8 #9：工具**被限流拦下**时的统一文案与轨迹记录。
     *
     * <p>为什么"被拦"也要写一条 trace：以前这里直接 {@code return}，于是调试面板上
     * <b>看不到这条记录</b> —— 而 {@code CoachPanel} 里设计好的"超出调用次数上限被拦下"
     * 橙标就成了<b>永远不会出现的死 UI</b>，排查"模型为什么不再调工具了"时无据可查。</p>
     *
     * <p>★ 别误判成"限额泄漏"：限流的 Lua 是"<b>先校验、通过后才 INCR</b>"，
     * 被拦的这一次<b>并没有</b>被计入调用次数。这里补的只是"被拦"这个<b>事件</b>。</p>
     */
    private static final String BLOCKED_HINT =
            "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
    private static final String BLOCKED_SUMMARY = "超出本轮工具调用次数上限";
    private static final String BLOCKED_ERROR = "超出调用次数上限";

    private final SearchClient searchClient;
    private final CourseClient courseClient;
    private final ToolTrace trace;
    private final String requestId;
    /** 调用者身份：由编排层从 UserContext 传来，**不来自模型**（见 ToolAuthContext） */
    private final Long userId;
    private final String authToken;

    public CourseTools(SearchClient searchClient, CourseClient courseClient,
                       ToolTrace trace, String requestId, Long userId, String authToken) {
        this.searchClient = searchClient;
        this.courseClient = courseClient;
        this.trace = trace;
        this.requestId = requestId;
        this.userId = userId;
        this.authToken = authToken;
    }

    @Tool(name = "searchCourses", description = """
            按关键词搜索平台上已有的课程，返回课程 id、名称、价格与节数。
            关键词用课程相关的词，例如「哑铃」「增肌」「核心」「入门」。
            不确定该推荐哪门课时优先用它搜一搜；如果用户已经明确给出了课程 id，直接调 queryCourseDetail 核实即可，不必先搜。
            不要凭印象编课程名或课程 id。搜不到就如实告诉用户平台暂时没有相关课程。""")
    public String searchCourses(@ToolParam(description = "搜索关键词，例如：哑铃、增肌、核心") String keyword) {
        String args = "{\"keyword\":\"" + keyword + "\"}";
        if (!trace.tryAcquire()) {
            trace.record("searchCourses", args, BLOCKED_SUMMARY, 0, true, BLOCKED_ERROR);
            return BLOCKED_HINT;
        }
        long start = System.currentTimeMillis();
        try {
            // ★ 走 ToolAuthContext：工具跑在 Reactor 线程上，ThreadLocal 里的身份不会跟过来
            List<Long> ids = ToolAuthContext.runAs(userId, authToken,
                    () -> searchClient.queryIdsByKeyword(keyword, 10));
            if (ids == null || ids.isEmpty()) {
                trace.record("searchCourses", args, "没有搜到课程", System.currentTimeMillis() - start, false, null);
                // ★ 措辞刻意给"下一步"：只告诉它失败，模型会换个词原地重试到超限
                return "没有搜到和「" + keyword + "」相关的课程。可以换个更通用的词再试，"
                        + "或者如实告诉用户暂时没有（如果用户给了具体课程 id，也可以直接用 queryCourseDetail 核实）。";
            }
            List<CourseSimpleDTO> courses = ToolAuthContext.runAs(userId, authToken,
                    () -> courseClient.queryCourseSimpleList(ids));
            if (courses == null || courses.isEmpty()) {
                trace.record("searchCourses", args, "索引有结果但课程已下架", System.currentTimeMillis() - start, false, null);
                return "和「" + keyword + "」相关的课程当前都没有上架。";
            }

            StringBuilder sb = new StringBuilder("搜到 ").append(courses.size()).append(" 门相关课程：\n");
            List<Map<String, Object>> items = new ArrayList<>();
            for (CourseSimpleDTO c : courses) {
                sb.append("- id=").append(c.getId())
                        .append("｜").append(c.getName())
                        .append("｜").append(c.getFree() != null && c.getFree() == 1 ? "免费" : yuan(c.getPrice()))
                        .append(c.getSectionNum() != null ? "｜" + c.getSectionNum() + " 节" : "")
                        .append('\n');
                // ★ 卡片数据：与给模型看的文本**分开**准备。
                //   文本是给模型读的，卡片是给前端渲染的 —— 两者的字段需求并不一样
                //   （卡片要封面图，模型不需要）。混在一起只会让两边都不好改。
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("courseId", c.getId());
                item.put("courseName", c.getName());
                item.put("coverUrl", c.getCoverUrl());
                item.put("free", c.getFree());
                item.put("price", c.getPrice());
                item.put("sectionNum", c.getSectionNum());
                item.put("validDuration", c.getValidDuration());
                items.add(item);
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "COURSE_LIST");
            card.put("title", "为你找到的课程");
            card.put("keyword", keyword);
            card.put("courses", items);
            trace.addCard(card);

            String result = sb.toString();
            trace.record("searchCourses", args, "命中 " + courses.size() + " 门",
                    System.currentTimeMillis() - start, false, null);
            log.info("工具调用[searchCourses]：requestId={}，keyword={}，命中 {} 门", requestId, keyword, courses.size());
            return result;
        } catch (Exception e) {
            trace.record("searchCourses", args, "检索失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[searchCourses]：keyword={}，{}", keyword, e.getMessage());
            return "课程检索暂时不可用，请告诉用户稍后再试，不要凭印象推荐课程。";
        }
    }

    /**
     * 查课程详情 —— <b>也是"核实一个课程 id 是否真实存在"的入口</b>。
     *
     * <p>★★ M6-8 #13：描述与实现的<b>对齐</b>。</p>
     * <p>旧描述承诺了"<b>器械要求</b>"，而 {@code CourseFullDTO} <b>根本没有这个字段</b>
     * —— 承诺了却给不出，等于在教模型"我这边看不到器械"，
     * 它要么瞎猜、要么对用户说"这门课没写器械"。<b>描述里出现实现给不出的字段，是骗模型。</b></p>
     * <p>反过来，库里<b>明明有</b>的 {@code trainingLevel} / {@code goalType} / {@code validDuration}
     * 却一直没输出 —— 模型只能靠"零基础""12 周"这些课名去猜难度。**库里有的信息，不该让它猜。**</p>
     */
    @Tool(name = "queryCourseDetail", description = """
            按课程 id 查询某门课程的详细信息：训练周期、每周频次、难度、训练目标、有效期、课程目录与节数。
            用法：用户在本轮对话里**明确给出**课程 id 时，可以直接用它来核实这门课是否存在；
            而你自己猜测或拼出来的 id 属于编造，必须先经 searchCourses 搜到真实 id。
            查不到时说明这个 id 不存在，请如实告诉用户，不要拿它继续推荐或下单。""")
    public String queryCourseDetail(
            @ToolParam(description = "课程 id（用户明确给出的 id 可直接传入核实；自己猜的 id 必须先搜索）") Long courseId) {
        String args = "{\"courseId\":" + courseId + "}";
        if (!trace.tryAcquire()) {
            trace.record("queryCourseDetail", args, BLOCKED_SUMMARY, 0, true, BLOCKED_ERROR);
            return BLOCKED_HINT;
        }
        long start = System.currentTimeMillis();
        try {
            CourseFullDTO full = ToolAuthContext.runAs(userId, authToken,
                    () -> courseClient.queryCourseFull(courseId, true));
            if (full == null) {
                // ★ 不存在的 id：返回人话，不抛异常。
                //   这是"模型编造 id"最常见也最无害的表现，处理方式决定了
                //   用户看到的是"这门课我没找到"还是"系统错误"
                trace.record("queryCourseDetail", args, "课程不存在", System.currentTimeMillis() - start, false, null);
                // ★ M6-8 #12：提示不再"一律要求先搜索" —— 用户给的 id 核实不到，
                //   该做的是**如实告诉他没这门课**，而不是让模型绕回搜索空转
                return "没找到 id=" + courseId + " 对应的课程（可能不存在，或已下架）。"
                        + "如果这个 id 是用户给你的，请如实告诉他没找到这门课；"
                        + "如果是你自己猜的，请先用 searchCourses 搜到真实 id 再来。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("课程《").append(full.getName()).append("》\n");
            if (full.getTotalDays() != null) {
                sb.append("- 训练周期：").append(full.getTotalDays()).append(" 天\n");
            }
            if (full.getWeeklyFrequency() != null) {
                sb.append("- 每周频次：").append(full.getWeeklyFrequency()).append(" 次\n");
            }
            // ★ M6-8 #13：把库里已有、以前没输出的字段补上（数字翻成中文，别让模型自己解释编码）
            String level = trainingLevelText(full.getTrainingLevel());
            if (level != null) {
                sb.append("- 难度：").append(level).append('\n');
            }
            String goal = goalTypeText(full.getGoalType());
            if (goal != null) {
                sb.append("- 训练目标：").append(goal).append('\n');
            }
            if (full.getSectionNum() != null) {
                sb.append("- 节数：").append(full.getSectionNum()).append(" 节（训练动作数）\n");
            }
            if (full.getValidDuration() != null) {
                sb.append("- 有效期：购买后 ").append(full.getValidDuration()).append(" 天\n");
            }
            sb.append("- 价格：").append(full.getFree() != null && full.getFree() == 1 ? "免费" : yuan(full.getPrice())).append('\n');
            // ★ M6-8 #14：目录不再"取前 8 条就完事"
            sb.append(renderCatalogues(full.getCatalogues()));

            trace.record("queryCourseDetail", args, "查询成功", System.currentTimeMillis() - start, false, null);
            log.info("工具调用[queryCourseDetail]：requestId={}，courseId={}", requestId, courseId);
            return sb.toString();
        } catch (Exception e) {
            trace.record("queryCourseDetail", args, "查询失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[queryCourseDetail]：courseId={}，{}", courseId, e.getMessage());
            return "没找到 id=" + courseId + " 对应的课程。";
        }
    }

    /**
     * ★★★ 把扁平目录**按训练日聚合**后再输出（M6-8 #14）。
     *
     * <p><b>为什么不能像原来那样 `limit(8)` 了事</b>：
     * {@code catalogues} 是一张<b>扁平表</b>（{@code type} 1=训练日 / 2=训练动作，
     * 动作靠 {@code parentCatalogueId} 指向所属训练日），
     * 而**训练日内的动作序号是各自从 1 开始的** —— 所以"扁平顺序"根本不是"课程结构顺序"。
     * 取前 8 条的结果是：<b>把 Day 2 的中间截断，却假装这就是全部目录</b>。
     * 用户拿着这份"目录"去判断课程适不适合自己，看到的是一个残缺的东西。</p>
     *
     * <p>★ 聚合输出一次解决三件事：</p>
     * <ol>
     *   <li><b>不丢结构</b>：动作挂在它所属的训练日下面，不会被切在半天中间；</li>
     *   <li><b>省 token</b>：不用给每个动作编号，靠"、"分隔就够；</li>
     *   <li><b>自证完整</b>：开头写"共 N 条 / M 个训练日"—— 即使后面因为预算被截断，
     *       模型也知道"总共多少"，<b>不会把"我看到的"当成"全部"</b>。</li>
     * </ol>
     *
     * <p>⚠️ 超预算时**显式声明**"其余 N 个训练日未展示"，绝不静默丢弃
     * （与 RAG 侧 {@code truncate} 同思路：宁可少给，也要让调用方知道少了）。</p>
     */
    private String renderCatalogues(List<CourseCatalogueDTO> catalogues) {
        if (catalogues == null || catalogues.isEmpty()) {
            return "";
        }
        Comparator<CourseCatalogueDTO> byIndex =
                Comparator.comparingInt(c -> c.getIndex() == null ? 0 : c.getIndex());

        List<CourseCatalogueDTO> days = catalogues.stream()
                .filter(c -> Objects.equals(c.getType(), DAY_TYPE))
                .sorted(byIndex)
                .toList();
        Map<Long, List<CourseCatalogueDTO>> actionsByDay = catalogues.stream()
                .filter(c -> Objects.equals(c.getType(), ACTION_TYPE))
                .filter(c -> c.getParentCatalogueId() != null)
                .collect(Collectors.groupingBy(CourseCatalogueDTO::getParentCatalogueId));

        StringBuilder sb = new StringBuilder();
        // ★ "共 N 条 / M 个训练日"——这句自证完整的话，是本次修复的关键
        sb.append("- 课程目录（共 ").append(catalogues.size()).append(" 条 / ")
                .append(days.isEmpty() ? 0 : days.size()).append(" 个训练日）：\n");

        // 兜底：数据里没有"训练日"层级时，退化成平铺 —— 但**同样声明总量**，仍不静默截断
        if (days.isEmpty()) {
            int shown = 0;
            for (CourseCatalogueDTO c : catalogues.stream().sorted(byIndex).toList()) {
                String line = "  " + c.getName() + "\n";
                if (sb.length() + line.length() > CATALOGUE_CHAR_BUDGET) {
                    break;
                }
                sb.append(line);
                shown++;
            }
            int rest = catalogues.size() - shown;
            if (rest > 0) {
                sb.append("  （其余 ").append(rest).append(" 条未展示）\n");
            }
            return sb.toString();
        }

        int shownDays = 0;
        for (CourseCatalogueDTO day : days) {
            List<CourseCatalogueDTO> actions = actionsByDay.getOrDefault(day.getId(), List.of())
                    .stream().sorted(byIndex).toList();
            String names = actions.isEmpty()
                    ? "（暂无动作）"
                    : actions.stream().map(CourseCatalogueDTO::getName).collect(Collectors.joining("、"));
            String line = "  " + day.getName() + "：" + names + "\n";
            if (sb.length() + line.length() > CATALOGUE_CHAR_BUDGET) {
                break;
            }
            sb.append(line);
            shownDays++;
        }
        if (shownDays < days.size()) {
            // ★ 显式声明，不静默丢弃
            sb.append("  （其余 ").append(days.size() - shownDays).append(" 个训练日未展示）\n");
        }
        return sb.toString();
    }

    /** 目录项类型：1=训练日 2=训练动作（动作靠 {@code parentCatalogueId} 指向所属训练日） */
    private static final Integer DAY_TYPE = 1;
    private static final Integer ACTION_TYPE = 2;

    /** 适用水平：1-零基础 2-初级 3-中级 4-高级（与前端 `TRAINING_LEVELS` 同一套口径） */
    private String trainingLevelText(Integer level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case 1 -> "零基础";
            case 2 -> "初级";
            case 3 -> "中级";
            case 4 -> "高级";
            default -> "未知（" + level + "）";
        };
    }

    /** 训练目标：1-增肌 2-减脂 3-塑形 4-保持（与前端 `GOAL_TYPES` 同一套口径） */
    private String goalTypeText(Integer goal) {
        if (goal == null) {
            return null;
        }
        return switch (goal) {
            case 1 -> "增肌";
            case 2 -> "减脂";
            case 3 -> "塑形";
            case 4 -> "保持";
            default -> "未知（" + goal + "）";
        };
    }

    /** 分 → 元（金额在库里一律是分，展示给用户时才转） */
    private String yuan(Integer cents) {
        if (cents == null) {
            return "价格未知";
        }
        return String.format("%.2f 元", cents / 100.0);
    }
}
