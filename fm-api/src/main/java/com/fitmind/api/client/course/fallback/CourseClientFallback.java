package com.fitmind.api.client.course.fallback;

import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CataSimpleDTO;
import com.fitmind.api.dto.course.CourseFullDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.course.CourseSearchInfoDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link CourseClient} 的降级工厂。
 *
 * <p>fm-course 不可用时返回 null。对 fm-search 来说这意味着"这个课程暂时取不到数据"，
 * 消费方会跳过本次索引同步（消息不 ack 的话会重投，见 M4 的死信队列设计），
 * 而不是把整个消息监听线程搞崩。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class CourseClientFallback implements FallbackFactory<CourseClient> {

    @Override
    public CourseClient create(Throwable cause) {
        return new CourseClient() {
            @Override
            public CourseSearchInfoDTO querySearchInfo(Long id) {
                log.error("课程服务不可用，无法查询课程检索信息。id={}，原因：{}", id, cause.getMessage());
                return null;
            }

            @Override
            public CourseFullDTO queryCourseFull(Long id, boolean withCatalogues) {
                log.error("课程服务不可用，无法查询课程全文。id={}，原因：{}", id, cause.getMessage());
                return null;
            }

            @Override
            public List<CourseSimpleDTO> queryCourseSimpleList(List<Long> ids) {
                log.error("课程服务不可用，无法批量查询课程简要信息。原因：{}", cause.getMessage());
                return null;
            }

            @Override
            public List<CataSimpleDTO> queryCataSimpleList(List<Long> ids) {
                log.error("课程服务不可用，无法批量查询训练目录名。原因：{}", cause.getMessage());
                return null;
            }
        };
    }
}
