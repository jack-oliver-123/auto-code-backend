package com.jack.autocodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.mapper.AppMapper;
import com.jack.autocodebackend.mapper.ChatHistoryMapper;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.ChatHistory;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.ChatHistoryAdminQueryDTO;
import com.jack.autocodebackend.model.dto.ChatHistoryCursorQueryDTO;
import com.jack.autocodebackend.model.enums.ChatHistoryMessageTypeEnum;
import com.jack.autocodebackend.model.enums.UserRoleEnum;
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;
import com.jack.autocodebackend.service.ChatHistoryService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 对话历史服务实现。
 */
@Service
public class ChatHistoryServiceImpl
        extends ServiceImpl<ChatHistoryMapper, ChatHistory>
        implements ChatHistoryService {

    static final int DEFAULT_CURSOR_PAGE_SIZE = 10;

    static final int MAX_CURSOR_PAGE_SIZE = 20;

    static final int MAX_ADMIN_PAGE_SIZE = 100;

    static final String AI_REPLY_FAILED_MESSAGE = "AI 回复失败，请稍后重试";

    static final String AI_REPLY_CANCELLED_MESSAGE = "AI 回复已取消";

    private final AppMapper appMapper;

    public ChatHistoryServiceImpl(AppMapper appMapper) {
        this.appMapper = appMapper;
    }

    @Override
    public long addChatMessage(
            Long appId,
            Long userId,
            String message,
            ChatHistoryMessageTypeEnum messageType
    ) {
        validatePositiveId(appId, "应用 id 不合法");
        validatePositiveId(userId, "用户 id 不合法");
        if (message == null || message.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        if (messageType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不合法");
        }

        ChatHistory chatHistory = new ChatHistory();
        chatHistory.setMessage(message);
        chatHistory.setMessageType(messageType.getValue());
        chatHistory.setAppId(appId);
        chatHistory.setUserId(userId);
        try {
            if (baseMapper.insert(chatHistory) != 1 || chatHistory.getId() == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "保存对话历史失败");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw operationFailure("保存对话历史失败", exception);
        }
        return chatHistory.getId();
    }

    @Override
    public long addAiFailureMessage(Long appId, Long userId, Throwable failure) {
        if (failure == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "失败原因不能为空");
        }
        return addChatMessage(
                appId,
                userId,
                getSafeFailureMessage(failure),
                ChatHistoryMessageTypeEnum.AI
        );
    }

    @Override
    public long addAiCancellationMessage(Long appId, Long userId) {
        return addChatMessage(
                appId,
                userId,
                AI_REPLY_CANCELLED_MESSAGE,
                ChatHistoryMessageTypeEnum.AI
        );
    }

    @Override
    public ChatHistoryCursorPageVO listAppHistory(
            ChatHistoryCursorQueryDTO queryDTO,
            User loginUser
    ) {
        if (queryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        validateLoginUser(loginUser);
        Long appId = queryDTO.getAppId();
        validatePositiveId(appId, "应用 id 不合法");
        Long beforeId = queryDTO.getBeforeId();
        if (beforeId != null) {
            validatePositiveId(beforeId, "游标不合法");
        }
        int pageSize = queryDTO.getPageSize() == null
                ? DEFAULT_CURSOR_PAGE_SIZE
                : queryDTO.getPageSize();
        if (pageSize < 1 || pageSize > MAX_CURSOR_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数不合法");
        }

        authorizeHistoryAccess(appId, loginUser);
        QueryWrapper<ChatHistory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("appId", appId)
                .lt(beforeId != null, "id", beforeId)
                .orderByDesc("id");
        Page<ChatHistory> selectedPage = this.page(
                new Page<>(1, pageSize + 1L, false),
                queryWrapper
        );
        if (selectedPage == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询对话历史失败");
        }

        List<ChatHistory> records = selectedPage.getRecords() == null
                ? new ArrayList<>()
                : new ArrayList<>(selectedPage.getRecords());
        boolean hasMore = records.size() > pageSize;
        if (hasMore) {
            records.remove(records.size() - 1);
        }
        Collections.reverse(records);

        ChatHistoryCursorPageVO result = new ChatHistoryCursorPageVO();
        result.setRecords(getChatHistoryVOList(records));
        result.setHasMore(hasMore);
        result.setNextCursor(hasMore && !records.isEmpty() ? records.getFirst().getId() : null);
        return result;
    }

    @Override
    public Page<ChatHistoryVO> listAllHistoryByAdmin(ChatHistoryAdminQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        if (queryDTO.getPageNum() < 1
                || queryDTO.getPageSize() < 1
                || queryDTO.getPageSize() > MAX_ADMIN_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数不合法");
        }
        if (queryDTO.getAppId() != null) {
            validatePositiveId(queryDTO.getAppId(), "应用 id 不合法");
        }
        if (queryDTO.getUserId() != null) {
            validatePositiveId(queryDTO.getUserId(), "用户 id 不合法");
        }
        ChatHistoryMessageTypeEnum messageType = null;
        if (queryDTO.getMessageType() != null) {
            messageType = ChatHistoryMessageTypeEnum.getEnumByValue(queryDTO.getMessageType());
            if (messageType == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息类型不合法");
            }
        }

        QueryWrapper<ChatHistory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(queryDTO.getAppId() != null, "appId", queryDTO.getAppId())
                .eq(queryDTO.getUserId() != null, "userId", queryDTO.getUserId())
                .eq(messageType != null, "messageType",
                        messageType == null ? null : messageType.getValue())
                .orderByDesc("createTime")
                .orderByDesc("id");
        Page<ChatHistory> historyPage = this.page(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()),
                queryWrapper
        );
        if (historyPage == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询对话历史失败");
        }
        return getChatHistoryVOPage(historyPage);
    }

    @Override
    public void deleteByAppId(Long appId) {
        validatePositiveId(appId, "应用 id 不合法");
        QueryWrapper<ChatHistory> deleteWrapper = new QueryWrapper<>();
        deleteWrapper.eq("appId", appId);
        try {
            baseMapper.delete(deleteWrapper);
        } catch (RuntimeException exception) {
            throw operationFailure("删除对话历史失败", exception);
        }
    }

    @Override
    public List<ChatHistory> listLatestForMemory(Long appId, Long beforeId, int limit) {
        validatePositiveId(appId, "应用 id 不合法");
        if (beforeId != null) {
            validatePositiveId(beforeId, "历史记录游标不合法");
        }
        if (limit <= 0 || limit > AppChatMemoryProperties.MAX_HISTORY_LIMIT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "历史记录数量不合法");
        }
        QueryWrapper<ChatHistory> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("appId", appId)
                .lt(beforeId != null, "id", beforeId)
                .orderByDesc("id");
        try {
            Page<ChatHistory> selectedPage = this.page(
                    new Page<>(1, limit, false), queryWrapper);
            if (selectedPage == null || selectedPage.getRecords() == null) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "查询对话记忆失败");
            }
            return List.copyOf(selectedPage.getRecords());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw operationFailure("查询对话记忆失败", exception);
        }
    }

    @Override
    public ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory) {
        if (chatHistory == null) {
            return null;
        }
        ChatHistoryVO chatHistoryVO = new ChatHistoryVO();
        BeanUtils.copyProperties(chatHistory, chatHistoryVO);
        return chatHistoryVO;
    }

    @Override
    public Page<ChatHistoryVO> getChatHistoryVOPage(Page<ChatHistory> chatHistoryPage) {
        if (chatHistoryPage == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页结果为空");
        }
        Page<ChatHistoryVO> result = new Page<>(
                chatHistoryPage.getCurrent(),
                chatHistoryPage.getSize(),
                chatHistoryPage.getTotal()
        );
        result.setRecords(getChatHistoryVOList(chatHistoryPage.getRecords()));
        return result;
    }

    private List<ChatHistoryVO> getChatHistoryVOList(List<ChatHistory> historyList) {
        if (CollUtil.isEmpty(historyList)) {
            return new ArrayList<>();
        }
        return historyList.stream().map(this::getChatHistoryVO).toList();
    }

    private void authorizeHistoryAccess(Long appId, User loginUser) {
        App app = appMapper.selectById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        boolean administrator = UserRoleEnum.ADMIN.equals(
                UserRoleEnum.getEnumByValue(loginUser.getUserRole()));
        if (!administrator && !Objects.equals(app.getUserId(), loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private String getSafeFailureMessage(Throwable failure) {
        if (failure instanceof BusinessException businessException) {
            for (ErrorCode errorCode : ErrorCode.values()) {
                if (errorCode != ErrorCode.SUCCESS
                        && errorCode.getCode() == businessException.getCode()) {
                    return "AI 回复失败：" + errorCode.getMessage();
                }
            }
        }
        return AI_REPLY_FAILED_MESSAGE;
    }

    private void validateLoginUser(User loginUser) {
        if (loginUser == null || loginUser.getId() == null || loginUser.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }

    private void validatePositiveId(Long id, String message) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, message);
        }
    }

    private BusinessException operationFailure(String message, RuntimeException cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }
}
