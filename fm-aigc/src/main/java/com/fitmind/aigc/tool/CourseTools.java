package com.fitmind.aigc.tool;

import com.fitmind.aigc.client.SearchClient;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
@Slf4j
public class CourseTools {

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
            按关键词搜索平台上已有的课程，返回课程id、名称、价格、训练目标、难度、周期、器械。
            关键词用课程相关的词，例如「哑铃」「增肌」「核心」「入门」。
            推荐课程之前必须先调用它拿到真实存在的课程id —— 不要凭印象编课程名或课程id。
            如果没搜到，就如实告诉用户平台暂时没有相关课程。""")
    public String searchCourses(@ToolParam(description = "搜索关键词，例如：哑铃、增肌、核心") String keyword) {
        if (!trace.tryAcquire()) {
            return "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
        }
        long start = System.currentTimeMillis();
        String args = "{\"keyword\":\"" + keyword + "\"}";
        try {
            // ★ 走 ToolAuthContext：工具跑在 Reactor 线程上，ThreadLocal 里的身份不会跟过来
            List<Long> ids = ToolAuthContext.runAs(userId, authToken,
                    () -> searchClient.queryIdsByKeyword(keyword, 10));
            if (ids == null || ids.isEmpty()) {
                trace.record("searchCourses", args, "没有搜到课程", System.currentTimeMillis() - start, false, null);
                return "没有搜到和「" + keyword + "」相关的课程。可以换个更通用的词再试，或者如实告诉用户暂时没有。";
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

    @Tool(name = "queryCourseDetail", description = """
            查询某门课程的详细信息：训练周期、每周频次、难度、器械要求、课程目录与节数。
            courseId 必须来自 searchCourses 的返回结果，不要自己拼一个 id。""")
    public String queryCourseDetail(
            @ToolParam(description = "课程id，必须来自 searchCourses 的结果") Long courseId) {
        if (!trace.tryAcquire()) {
            return "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
        }
        long start = System.currentTimeMillis();
        String args = "{\"courseId\":" + courseId + "}";
        try {
            CourseFullDTO full = ToolAuthContext.runAs(userId, authToken,
                    () -> courseClient.queryCourseFull(courseId, true));
            if (full == null) {
                // ★ 不存在的 id：返回人话，不抛异常。
                //   这是"模型编造 id"最常见也最无害的表现，处理方式决定了
                //   用户看到的是"这门课我没找到"还是"系统错误"
                trace.record("queryCourseDetail", args, "课程不存在", System.currentTimeMillis() - start, false, null);
                return "没找到 id=" + courseId + " 对应的课程。请先用 searchCourses 搜一下，"
                        + "不要用这个 id 继续推荐或下单。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("课程《").append(full.getName()).append("》\n");
            if (full.getTotalDays() != null) {
                sb.append("- 训练周期：").append(full.getTotalDays()).append(" 天\n");
            }
            if (full.getWeeklyFrequency() != null) {
                sb.append("- 每周频次：").append(full.getWeeklyFrequency()).append(" 次\n");
            }
            if (full.getSectionNum() != null) {
                sb.append("- 节数：").append(full.getSectionNum()).append('\n');
            }
            sb.append("- 价格：").append(full.getFree() != null && full.getFree() == 1 ? "免费" : yuan(full.getPrice())).append('\n');
            if (full.getCatalogues() != null && !full.getCatalogues().isEmpty()) {
                sb.append("- 课程目录：");
                full.getCatalogues().stream().limit(8)
                        .forEach(c -> sb.append(c.getName()).append("；"));
                sb.append('\n');
            }
            trace.record("queryCourseDetail", args, "查询成功", System.currentTimeMillis() - start, false, null);
            log.info("工具调用[queryCourseDetail]：requestId={}，courseId={}", requestId, courseId);
            return sb.toString();
        } catch (Exception e) {
            trace.record("queryCourseDetail", args, "查询失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[queryCourseDetail]：courseId={}，{}", courseId, e.getMessage());
            return "没找到 id=" + courseId + " 对应的课程。";
        }
    }

    /** 分 → 元（金额在库里一律是分，展示给用户时才转） */
    private String yuan(Integer cents) {
        if (cents == null) {
            return "价格未知";
        }
        return String.format("%.2f 元", cents / 100.0);
    }
}
