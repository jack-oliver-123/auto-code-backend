package com.jack.autocodebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.AppAddDTO;
import com.jack.autocodebackend.model.dto.AppAdminUpdateDTO;
import com.jack.autocodebackend.model.dto.AppNameQueryDTO;
import com.jack.autocodebackend.model.dto.AppQueryDTO;
import com.jack.autocodebackend.model.dto.AppUpdateDTO;
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用服务。
 */
public interface AppService extends IService<App> {

    long createApp(AppAddDTO appAddDTO, User loginUser);

    Flux<AppGenerationEvent> chatToGenCode(Long appId, String message, User loginUser);

    AppPreviewVO createAppPreview(Long appId, User loginUser);

    AppDeployVO deployApp(Long appId, User loginUser);

    boolean updateAppByUser(AppUpdateDTO appUpdateDTO, User loginUser);

    boolean deleteAppByUser(Long appId, User loginUser);

    boolean updateAppByAdmin(AppAdminUpdateDTO appAdminUpdateDTO);

    boolean deleteAppByAdmin(Long appId);

    QueryWrapper<App> getMyAppQueryWrapper(AppNameQueryDTO appNameQueryDTO, Long userId);

    QueryWrapper<App> getGoodAppQueryWrapper(AppNameQueryDTO appNameQueryDTO);

    QueryWrapper<App> getQueryWrapper(AppQueryDTO appQueryDTO);

    AppVO getAppVO(App app);

    List<AppVO> getAppVOList(List<App> appList);

    Page<AppVO> getAppVOPage(Page<App> appPage);

    AppDetailVO getAppDetailVOByOwner(Long appId, User loginUser);

    PublicAppDetailVO getPublicAppDetailVO(Long appId);

    AppDetailVO getAppDetailVO(App app);
}
