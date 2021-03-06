package com.itranswarp.crypto.candiess.web.modules.admin.canal.service;

import com.baomidou.mybatisplus.service.IService;
import com.itranswarp.crypto.candiess.common.utils.PageUtils;
import com.itranswarp.crypto.candiess.api.query.QueryViewVo;
import com.itranswarp.crypto.candiess.web.modules.admin.canal.entity.CanalpositionsConfigEntity;

import java.util.Map;
import java.util.List;
/**
 * 
 *
 * @author chenshun
 * @email sunlightcs@gmail.com
 * @date 2018-05-31 22:17:36
 */
public interface CanalpositionsConfigService extends IService<CanalpositionsConfigEntity> {

    PageUtils queryPage(Map<String, Object> params);
    
    PageUtils queryPage(QueryViewVo<CanalpositionsConfigEntity> queryViewVo);

	List<CanalpositionsConfigEntity> download(QueryViewVo<CanalpositionsConfigEntity> queryViewVo);
}

