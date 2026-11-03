package com.fitmind.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.media.domain.po.FileUploadPart;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分片进度 Mapper。
 */
@Mapper
public interface FileUploadPartMapper extends BaseMapper<FileUploadPart> {

    /**
     * 幂等写入一片进度。
     *
     * <p>★ 用 {@code ON DUPLICATE KEY UPDATE} 而不是"先查再插"：
     * 并发传同一个分片时，"先查再插"两个请求都会认为不存在，然后一个报唯一键冲突；
     * 交给数据库的原子 upsert，两个请求都能成功且结果一致。</p>
     */
    @Insert("INSERT INTO file_upload_part (task_id, part_number, etag, size) "
            + "VALUES (#{taskId}, #{partNumber}, #{etag}, #{size}) "
            + "ON DUPLICATE KEY UPDATE etag = VALUES(etag), size = VALUES(size)")
    int upsert(FileUploadPart part);

    /** 已传分片数 —— ★ 每次重算，不在内存里累加（重传会把它算多） */
    @Select("SELECT COUNT(*) FROM file_upload_part WHERE task_id = #{taskId}")
    int countByTask(@Param("taskId") Long taskId);

    /** 已传字节数，同上 */
    @Select("SELECT COALESCE(SUM(size), 0) FROM file_upload_part WHERE task_id = #{taskId}")
    long sumSizeByTask(@Param("taskId") Long taskId);
}
