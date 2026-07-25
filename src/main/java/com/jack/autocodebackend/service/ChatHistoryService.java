package com.jack.autocodebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.jack.autocodebackend.model.domain.ChatHistory;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.ChatHistoryAdminQueryDTO;
import com.jack.autocodebackend.model.dto.ChatHistoryCursorQueryDTO;
import com.jack.autocodebackend.model.enums.ChatHistoryMessageTypeEnum;
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;

/**
 * 对话历史服务。
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    long addChatMessage(
            Long appId,
            Long userId,
            String message,
            ChatHistoryMessageTypeEnum messageType
    );

    long addAiFailureMessage(Long appId, Long userId, Throwable failure);

    long addAiCancellationMessage(Long appId, Long userId);

    ChatHistoryCursorPageVO listAppHistory(
            ChatHistoryCursorQueryDTO queryDTO,
            User loginUser
    );

    Page<ChatHistoryVO> listAllHistoryByAdmin(ChatHistoryAdminQueryDTO queryDTO);

    void deleteByAppId(Long appId);

    ChatHistoryVO getChatHistoryVO(ChatHistory chatHistory);

    Page<ChatHistoryVO> getChatHistoryVOPage(Page<ChatHistory> chatHistoryPage);
}
