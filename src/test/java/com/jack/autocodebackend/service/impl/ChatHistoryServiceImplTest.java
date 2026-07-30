package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"rawtypes", "unchecked"})
class ChatHistoryServiceImplTest {

    private static final long APP_ID = 2001L;

    private static final long OWNER_ID = 1001L;

    private final ChatHistoryMapper chatHistoryMapper = mock(ChatHistoryMapper.class);

    private final AppMapper appMapper = mock(AppMapper.class);

    private final AtomicLong idSequence = new AtomicLong(3000L);

    private final AtomicReference<Page<ChatHistory>> selectedPage = new AtomicReference<>();

    private final AtomicReference<QueryWrapper<ChatHistory>> selectedWrapper =
            new AtomicReference<>();

    private List<ChatHistory> selectedRecords = List.of();

    private long selectedTotal;

    private ChatHistoryServiceImpl chatHistoryService;

    @BeforeEach
    void setUp() {
        chatHistoryService = new ChatHistoryServiceImpl(appMapper);
        ReflectionTestUtils.setField(chatHistoryService, "baseMapper", chatHistoryMapper);
        given(chatHistoryMapper.insert(any(ChatHistory.class))).willAnswer(invocation -> {
            ChatHistory inserted = invocation.getArgument(0);
            inserted.setId(idSequence.incrementAndGet());
            return 1;
        });
        given(chatHistoryMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .willAnswer(invocation -> {
                    Page<ChatHistory> page = invocation.getArgument(0);
                    selectedPage.set(page);
                    selectedWrapper.set((QueryWrapper<ChatHistory>) invocation.getArgument(1));
                    page.setRecords(new ArrayList<>(selectedRecords));
                    page.setTotal(selectedTotal);
                    return page;
                });
    }

    @Test
    void addChatMessagePersistsTypedApplicationScopedContentWithoutTrimming() {
        String message = "  leading\nrepeated  spaces\n";

        long historyId = chatHistoryService.addChatMessage(
                APP_ID,
                OWNER_ID,
                message,
                ChatHistoryMessageTypeEnum.USER
        );

        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(chatHistoryMapper).insert(captor.capture());
        ChatHistory inserted = captor.getValue();
        assertThat(historyId).isEqualTo(inserted.getId()).isPositive();
        assertThat(inserted.getMessage()).isEqualTo(message);
        assertThat(inserted.getMessageType()).isEqualTo("user");
        assertThat(inserted.getAppId()).isEqualTo(APP_ID);
        assertThat(inserted.getUserId()).isEqualTo(OWNER_ID);
        assertThat(inserted.getIsDelete()).isNull();
    }

    @Test
    void addChatMessageRejectsInvalidInputBeforeWriting() {
        assertBusinessException(() -> chatHistoryService.addChatMessage(
                null, OWNER_ID, "message", ChatHistoryMessageTypeEnum.USER),
                ErrorCode.PARAMS_ERROR);
        assertBusinessException(() -> chatHistoryService.addChatMessage(
                APP_ID, 0L, "message", ChatHistoryMessageTypeEnum.USER),
                ErrorCode.PARAMS_ERROR);
        assertBusinessException(() -> chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "", ChatHistoryMessageTypeEnum.USER),
                ErrorCode.PARAMS_ERROR);
        assertBusinessException(() -> chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "message", null),
                ErrorCode.PARAMS_ERROR);

        verify(chatHistoryMapper, never()).insert(any(ChatHistory.class));
    }

    @Test
    void addChatMessageMapsZeroRowsAndMapperFailureToOperationError() {
        given(chatHistoryMapper.insert(any(ChatHistory.class)))
                .willReturn(0)
                .willThrow(new IllegalStateException("database unavailable"));

        assertBusinessException(() -> chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "first", ChatHistoryMessageTypeEnum.USER),
                ErrorCode.OPERATION_ERROR);
        BusinessException mapperFailure = assertBusinessException(
                () -> chatHistoryService.addChatMessage(
                        APP_ID, OWNER_ID, "second", ChatHistoryMessageTypeEnum.USER),
                ErrorCode.OPERATION_ERROR);

        assertThat(mapperFailure.getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    @Test
    void failureAndCancellationMessagesAreSafeAiRecords() {
        RuntimeException unknownFailure = new RuntimeException(
                "token=secret /private/generated/path");

        chatHistoryService.addAiFailureMessage(APP_ID, OWNER_ID, unknownFailure);
        chatHistoryService.addAiFailureMessage(
                APP_ID,
                OWNER_ID,
                new BusinessException(ErrorCode.OPERATION_ERROR, "internal path C:\\secret")
        );
        chatHistoryService.addAiCancellationMessage(APP_ID, OWNER_ID);

        ArgumentCaptor<ChatHistory> captor = ArgumentCaptor.forClass(ChatHistory.class);
        verify(chatHistoryMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<ChatHistory> inserted = captor.getAllValues();
        assertThat(inserted).extracting(ChatHistory::getMessageType)
                .containsOnly("ai");
        assertThat(inserted.get(0).getMessage())
                .isEqualTo(ChatHistoryServiceImpl.AI_REPLY_FAILED_MESSAGE)
                .doesNotContain("secret", "path", "token");
        assertThat(inserted.get(1).getMessage())
                .isEqualTo("AI 回复失败：操作失败")
                .doesNotContain("secret", "path");
        assertThat(inserted.get(2).getMessage())
                .isEqualTo(ChatHistoryServiceImpl.AI_REPLY_CANCELLED_MESSAGE);
    }

    @Test
    void initialCursorPageReturnsLatestTenChronologicallyWithStableIdCursor() {
        Date sameTime = new Date(1_700_000_000_000L);
        selectedRecords = descendingHistory(20, 10, sameTime);
        given(appMapper.selectById(APP_ID)).willReturn(app(OWNER_ID));
        ChatHistoryCursorQueryDTO query = cursorQuery(null, null);

        ChatHistoryCursorPageVO result = chatHistoryService.listAppHistory(
                query, user(OWNER_ID, "user"));

        assertThat(result.getRecords()).extracting(ChatHistoryVO::getId)
                .containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(result.isHasMore()).isTrue();
        assertThat(result.getNextCursor()).isEqualTo(11L);
        assertThat(selectedPage.get().getSize()).isEqualTo(11);
        assertThat(selectedPage.get().searchCount()).isFalse();
        assertThat(compactSql(selectedWrapper.get())).contains("appid=", "orderbyiddesc");
        assertThat(selectedWrapper.get().getParamNameValuePairs()).containsValue(APP_ID);
    }

    @Test
    void olderCursorPageIsExclusiveAndUnaffectedByNewerIds() {
        selectedRecords = descendingHistory(10, 8, new Date());
        given(appMapper.selectById(APP_ID)).willReturn(app(OWNER_ID));
        ChatHistoryCursorQueryDTO query = cursorQuery(11L, 2);

        ChatHistoryCursorPageVO result = chatHistoryService.listAppHistory(
                query, user(OWNER_ID, "user"));

        assertThat(result.getRecords()).extracting(ChatHistoryVO::getId)
                .containsExactly(9L, 10L);
        assertThat(result.getNextCursor()).isEqualTo(9L);
        assertThat(compactSql(selectedWrapper.get())).contains("id<", "orderbyiddesc");
        assertThat(selectedWrapper.get().getParamNameValuePairs())
                .containsValues(APP_ID, 11L);
    }

    @Test
    void cursorRemainsAnchoredWhenNewMessagesArriveBetweenRequests() {
        given(appMapper.selectById(APP_ID)).willReturn(app(OWNER_ID));
        selectedRecords = descendingHistory(30, 20, new Date(1_000L));

        ChatHistoryCursorPageVO initial = chatHistoryService.listAppHistory(
                cursorQuery(null, 10), user(OWNER_ID, "user"));

        assertThat(initial.getRecords()).extracting(ChatHistoryVO::getId)
                .containsExactly(21L, 22L, 23L, 24L, 25L, 26L, 27L, 28L, 29L, 30L);
        assertThat(initial.getNextCursor()).isEqualTo(21L);

        selectedRecords = descendingHistory(20, 10, new Date(1_000L));
        ChatHistoryCursorPageVO older = chatHistoryService.listAppHistory(
                cursorQuery(initial.getNextCursor(), 10), user(OWNER_ID, "user"));

        assertThat(older.getRecords()).extracting(ChatHistoryVO::getId)
                .containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(compactSql(selectedWrapper.get())).contains("id<");
        assertThat(selectedWrapper.get().getParamNameValuePairs()).containsValue(21L);
    }

    @Test
    void emptyCursorPageHasNoCursorAndAdministratorCanReadForeignApp() {
        selectedRecords = List.of();
        given(appMapper.selectById(APP_ID)).willReturn(app(OWNER_ID));

        ChatHistoryCursorPageVO result = chatHistoryService.listAppHistory(
                cursorQuery(null, 10), user(9000L, "admin"));

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.isHasMore()).isFalse();
        assertThat(result.getNextCursor()).isNull();
    }

    @Test
    void cursorAuthorizationDistinguishesMissingAndForeignApplications() {
        given(appMapper.selectById(APP_ID)).willReturn(null, app(OWNER_ID + 1));
        ChatHistoryCursorQueryDTO query = cursorQuery(null, 10);

        assertBusinessException(
                () -> chatHistoryService.listAppHistory(query, user(OWNER_ID, "user")),
                ErrorCode.NOT_FOUND_ERROR);
        assertBusinessException(
                () -> chatHistoryService.listAppHistory(query, user(OWNER_ID, "user")),
                ErrorCode.NO_AUTH_ERROR);

        verify(chatHistoryMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void cursorRequestRejectsInvalidLoginIdsCursorAndPageSize() {
        for (ChatHistoryCursorQueryDTO query : List.of(
                cursorQuery(null, 0),
                cursorQuery(null, 21),
                cursorQuery(0L, 10))) {
            assertBusinessException(
                    () -> chatHistoryService.listAppHistory(query, user(OWNER_ID, "user")),
                    ErrorCode.PARAMS_ERROR);
        }
        ChatHistoryCursorQueryDTO missingApp = new ChatHistoryCursorQueryDTO();
        assertBusinessException(
                () -> chatHistoryService.listAppHistory(missingApp, user(OWNER_ID, "user")),
                ErrorCode.PARAMS_ERROR);
        assertBusinessException(
                () -> chatHistoryService.listAppHistory(
                        cursorQuery(null, 10), new User()),
                ErrorCode.NOT_LOGIN_ERROR);
        verify(appMapper, never()).selectById(anyLong());
    }

    @Test
    void administratorPageUsesValidatedFiltersAndDeterministicDescendingOrder() {
        ChatHistory newest = history(22L, new Date(2_000L));
        ChatHistory older = history(21L, new Date(1_000L));
        selectedRecords = List.of(newest, older);
        selectedTotal = 7;
        ChatHistoryAdminQueryDTO query = new ChatHistoryAdminQueryDTO();
        query.setPageNum(2);
        query.setPageSize(2);
        query.setAppId(APP_ID);
        query.setUserId(OWNER_ID);
        query.setMessageType("ai");

        Page<ChatHistoryVO> result = chatHistoryService.listAllHistoryByAdmin(query);

        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(7);
        assertThat(result.getRecords()).extracting(ChatHistoryVO::getId)
                .containsExactly(22L, 21L);
        assertThat(compactSql(selectedWrapper.get()))
                .contains("appid=", "userid=", "messagetype=",
                        "orderbycreatetimedesc,iddesc");
        assertThat(selectedWrapper.get().getParamNameValuePairs())
                .containsValues(APP_ID, OWNER_ID, "ai");
        assertThat(Arrays.stream(ChatHistoryAdminQueryDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("sortField", "sortOrder");
    }

    @Test
    void administratorPageRejectsInvalidPaginationAndFiltersBeforeQuerying() {
        List<ChatHistoryAdminQueryDTO> invalidQueries = new ArrayList<>();
        invalidQueries.add(adminQuery(0, 10, null, null, null));
        invalidQueries.add(adminQuery(1, 0, null, null, null));
        invalidQueries.add(adminQuery(1, 101, null, null, null));
        invalidQueries.add(adminQuery(1, 10, 0L, null, null));
        invalidQueries.add(adminQuery(1, 10, null, -1L, null));
        invalidQueries.add(adminQuery(1, 10, null, null, "system"));

        for (ChatHistoryAdminQueryDTO query : invalidQueries) {
            assertBusinessException(
                    () -> chatHistoryService.listAllHistoryByAdmin(query),
                    ErrorCode.PARAMS_ERROR);
        }
        verify(chatHistoryMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void deleteByAppIdTreatsZeroRowsAsSuccessAndPropagatesMappedFailure() {
        given(chatHistoryMapper.delete(any(QueryWrapper.class)))
                .willReturn(0)
                .willThrow(new IllegalStateException("delete unavailable"));

        chatHistoryService.deleteByAppId(APP_ID);
        BusinessException failure = assertBusinessException(
                () -> chatHistoryService.deleteByAppId(APP_ID),
                ErrorCode.OPERATION_ERROR);

        ArgumentCaptor<QueryWrapper<ChatHistory>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(chatHistoryMapper, org.mockito.Mockito.times(2)).delete(captor.capture());
        assertThat(compactSql(captor.getAllValues().getFirst())).contains("appid=");
        assertThat(captor.getAllValues().getFirst().getParamNameValuePairs())
                .containsValue(APP_ID);
        assertThat(failure.getCause()).hasMessage("delete unavailable");
    }

    @Test
    void internalMemoryQueryIsBoundedApplicationScopedAndCursorExclusive() {
        selectedRecords = descendingHistory(20, 11, new Date());

        List<ChatHistory> result = chatHistoryService.listLatestForMemory(
                APP_ID, 21L, 10);

        assertThat(result).extracting(ChatHistory::getId)
                .containsExactly(20L, 19L, 18L, 17L, 16L, 15L, 14L, 13L, 12L, 11L);
        assertThat(selectedPage.get().getSize()).isEqualTo(10);
        assertThat(selectedPage.get().searchCount()).isFalse();
        assertThat(compactSql(selectedWrapper.get()))
                .contains("appid=", "id<", "orderbyiddesc");
        assertThat(selectedWrapper.get().getParamNameValuePairs())
                .containsValues(APP_ID, 21L);
        verify(appMapper, never()).selectById(anyLong());
    }

    @Test
    void mapperAndViewContractsKeepLogicalDeleteAndInternalFieldsPrivate() throws Exception {
        TableId tableId = ChatHistory.class.getDeclaredField("id").getAnnotation(TableId.class);
        assertThat(tableId).isNotNull();
        assertThat(tableId.type()).isEqualTo(IdType.AUTO);
        assertThat(ChatHistory.class.getDeclaredField("isDelete")
                .getAnnotation(TableLogic.class)).isNotNull();
        assertThat(Arrays.stream(ChatHistoryVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .contains("id", "message", "messageType", "appId", "userId", "createTime")
                .doesNotContain("updateTime", "isDelete");

        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/ChatHistoryMapper.xml")) {
            assertThat(input).isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(xml).contains(
                    "column=\"messageType\"",
                    "column=\"appId\"",
                    "column=\"userId\"",
                    "column=\"createTime\"",
                    "column=\"updateTime\"",
                    "column=\"isDelete\"");
        }
    }

    @Test
    void canonicalSchemaUsesLargeMessagesAndCursorModerationIndexes() throws Exception {
        String sql = Files.readString(Path.of("sql", "init.sql"), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql).contains(
                "create table if not exists chat_history",
                "message mediumtext",
                "index idx_appid_isdelete_id (appid, isdelete, id)",
                "index idx_isdelete_createtime_id (isdelete, createtime, id)"
        );
    }

    private static ChatHistoryCursorQueryDTO cursorQuery(Long beforeId, Integer pageSize) {
        ChatHistoryCursorQueryDTO query = new ChatHistoryCursorQueryDTO();
        query.setAppId(APP_ID);
        query.setBeforeId(beforeId);
        query.setPageSize(pageSize);
        return query;
    }

    private static ChatHistoryAdminQueryDTO adminQuery(
            int pageNum,
            int pageSize,
            Long appId,
            Long userId,
            String messageType
    ) {
        ChatHistoryAdminQueryDTO query = new ChatHistoryAdminQueryDTO();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        query.setAppId(appId);
        query.setUserId(userId);
        query.setMessageType(messageType);
        return query;
    }

    private static List<ChatHistory> descendingHistory(int newestId, int oldestId, Date time) {
        List<ChatHistory> records = new ArrayList<>();
        for (long id = newestId; id >= oldestId; id--) {
            records.add(history(id, time));
        }
        return records;
    }

    private static ChatHistory history(Long id, Date createTime) {
        ChatHistory history = new ChatHistory();
        history.setId(id);
        history.setMessage("message-" + id);
        history.setMessageType(id % 2 == 0 ? "ai" : "user");
        history.setAppId(APP_ID);
        history.setUserId(OWNER_ID);
        history.setCreateTime(createTime);
        return history;
    }

    private static App app(Long ownerId) {
        App app = new App();
        app.setId(APP_ID);
        app.setUserId(ownerId);
        return app;
    }

    private static User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private static String compactSql(Wrapper<?> wrapper) {
        return wrapper.getSqlSegment().replaceAll("\\s+", "").toLowerCase();
    }

    private static BusinessException assertBusinessException(
            Runnable action,
            ErrorCode errorCode
    ) {
        BusinessException exception = catchThrowableOfType(
                BusinessException.class,
                action::run
        );
        assertThat(exception).isNotNull();
        assertThat(exception.getCode()).isEqualTo(errorCode.getCode());
        return exception;
    }
}
