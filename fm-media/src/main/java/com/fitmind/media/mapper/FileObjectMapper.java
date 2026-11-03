package com.fitmind.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.media.domain.po.FileObject;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 物理对象 Mapper。
 */
@Mapper
public interface FileObjectMapper extends BaseMapper<FileObject> {

    /** 引用 +1（秒传命中时调用）。用 SQL 自增而不是"读-改-写"，避免并发丢更新。 */
    @Update("UPDATE file_object SET ref_count = ref_count + 1 WHERE id = #{id}")
    int increaseRef(@Param("id") Long id);

    /**
     * 引用 -1。
     *
     * <p>★ 带 {@code ref_count > 0} 条件：并发的两次删除不能让计数减成负数，
     * 也不能让"本来还有 1 个引用"被连减两次。</p>
     */
    @Update("UPDATE file_object SET ref_count = ref_count - 1 WHERE id = #{id} AND ref_count > 0")
    int decreaseRef(@Param("id") Long id);

    /**
     * 引用归零才删这一行。
     *
     * <p>条件写在 SQL 里而不是 Java 里，是为了让"能不能删"由数据库当次的值决定 ——
     * 否则两个请求都读到 0，就会重复删。</p>
     */
    @Delete("DELETE FROM file_object WHERE id = #{id} AND ref_count <= 0")
    int deleteIfUnused(@Param("id") Long id);
}
