package com.fitmind.media.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.media.domain.dto.FileDTO;
import com.fitmind.media.domain.dto.InitUploadDTO;
import com.fitmind.media.domain.po.FileUploadTask;
import com.fitmind.media.domain.vo.InitUploadVO;
import com.fitmind.media.domain.vo.PartUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 分片上传（断点续传 + 秒传）。
 *
 * <p>四步：{@link #init} → {@link #uploadPart}（可重复/可中断）→ {@link #complete}；
 * 放弃时 {@link #abort}。</p>
 *
 * <p>★ 所有方法都接收 {@code userId} 并校验任务归属 —— 上传任务 id 是自增的，
 * 不校验归属的话，任何人换个 id 就能往别人的任务里塞分片、甚至替别人完成上传。</p>
 */
public interface IUploadTaskService extends IService<FileUploadTask> {

    /**
     * 初始化：秒传判定 → 断点查询 → 新建任务（按顺序短路，本身幂等）。
     */
    InitUploadVO init(InitUploadDTO dto, Long userId);

    /**
     * 查询进度（刷新页面后恢复用）。
     *
     * <p>★ 恢复的正确姿势是「拿同一个 hash 再 init 一次」，而不是把 taskId 存在
     * localStorage 里 —— 本地状态会丢、会被清，服务端不会。</p>
     */
    InitUploadVO progress(Long taskId, Long userId);

    /**
     * 上传一片。同一片重复传是幂等的（覆盖）。
     */
    PartUploadVO uploadPart(Long taskId, int partNumber, MultipartFile chunk, Long userId);

    /**
     * 合并并生成正式文件记录。缺片会被拒绝（不会"尽力合并"）。
     */
    FileDTO complete(Long taskId, Long userId);

    /**
     * 取消上传，清理已上传的分片对象。
     */
    void abort(Long taskId, Long userId);
}
