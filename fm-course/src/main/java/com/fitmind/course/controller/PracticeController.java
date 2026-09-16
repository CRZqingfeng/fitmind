package com.fitmind.course.controller;

import com.fitmind.common.utils.UserContext;
import com.fitmind.course.domain.dto.PracticeSubmitDTO;
import com.fitmind.course.domain.vo.PracticeResultVO;
import com.fitmind.course.domain.vo.SubjectSimpleVO;
import com.fitmind.course.service.ISubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 动作小测学员接口。
 *
 * <p>这一组是「练完就来一道」的闭环：拉题 → 作答 → 判分。</p>
 *
 * <p><b>★ 拉题接口不返回答案</b>（返回类型是 {@link SubjectSimpleVO}，
 * 结构里就没有 answer 字段）——答案只在 {@code /practices/submit} 的判分结果里出现。
 * 否则打开浏览器控制台就能看到答案，小测失去意义。</p>
 *
 * <p>网关侧 {@code /cs/**} 本就在登录白名单外（需登录），所以这一组自动要求登录，
 * 无需额外配置——提交时要拿 userId 算积分。</p>
 */
@Validated
@RestController
@RequestMapping("/practices")
@RequiredArgsConstructor
@Tag(name = "动作小测接口（学员）")
public class PracticeController {

    private final ISubjectService subjectService;

    @Operation(summary = "拉取某个训练动作的小测题目（不含答案）")
    @Parameters(@Parameter(name = "cataId", description = "训练动作 / 测评节点 id"))
    @GetMapping("/{cataId}")
    public List<SubjectSimpleVO> practice(@PathVariable("cataId") Long cataId) {
        return subjectService.queryPractice(cataId);
    }

    @Operation(summary = "提交小测作答并判分（答对的题会得积分）")
    @PostMapping("/submit")
    public PracticeResultVO submit(@RequestBody @Valid PracticeSubmitDTO dto) {
        return subjectService.submitPractice(dto, UserContext.getUser());
    }
}
