package com.fitmind.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.user.domain.dto.BodyProfileFormDTO;
import com.fitmind.user.domain.po.UserBodyProfile;
import com.fitmind.user.domain.vo.BodyProfileVO;

/**
 * 身体档案服务 ★。
 */
public interface IBodyProfileService extends IService<UserBodyProfile> {

    /**
     * 查询指定用户的身体档案，未填写时返回 null
     */
    BodyProfileVO getByUserId(Long userId);

    /**
     * 保存或更新身体档案（一个用户一份），BMI 由后端计算
     */
    void saveOrUpdateProfile(Long userId, BodyProfileFormDTO dto);
}
