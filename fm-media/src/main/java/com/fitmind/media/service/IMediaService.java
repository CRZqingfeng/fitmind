package com.fitmind.media.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.media.domain.dto.ConfirmMediaDTO;
import com.fitmind.media.domain.dto.MediaDTO;
import com.fitmind.media.domain.dto.UploadSignatureDTO;
import com.fitmind.media.domain.po.Media;
import com.fitmind.media.domain.query.MediaQuery;
import com.fitmind.media.domain.vo.MediaVO;
import com.fitmind.media.domain.vo.PlayUrlVO;

import java.util.List;

/**
 * 媒资服务（训练视频）。
 *
 * <p>核心流程是「申请签名 → 前端直传 → 确认」，三步缺一不可，
 * 详见 {@link #applyUploadSignature} 和 {@link #confirmUpload} 的注释。</p>
 */
public interface IMediaService extends IService<Media> {

    /** 分页查询媒资 */
    PageDTO<MediaVO> queryMediaPage(MediaQuery query);

    /**
     * 申请上传签名（第 1 步）。
     *
     * <p>会先插一条 status=1（上传中）的记录，把 id 返回给前端，
     * 前端直传完再带上这个 id 调确认接口。</p>
     */
    UploadSignatureDTO applyUploadSignature(String filename, long size);

    /**
     * 确认上传（第 3 步）。
     *
     * <p>服务端主动向 MinIO 核实对象是否真的存在，拿到真实大小写入，
     * 再把状态改成「已上传」。不信任前端传的大小。</p>
     */
    MediaDTO confirmUpload(Long id, ConfirmMediaDTO dto);

    /** 获取播放地址（带签名，会过期） */
    PlayUrlVO getPlayUrl(Long id);

    /** 管理端预览地址（有效期更短） */
    PlayUrlVO getPreviewUrl(Long id);

    /** 删除媒资：被课程引用时拒绝 */
    void deleteMedia(Long id);

    /** 批量删除媒资 */
    void deleteMedias(List<Long> ids);

    /** 按 id 查询（内部 Feign 调用用，不包 R） */
    MediaDTO getMediaById(Long id);
}
