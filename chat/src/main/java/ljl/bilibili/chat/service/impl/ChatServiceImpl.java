package ljl.bilibili.chat.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.chat.entity.NoticeCount;
import ljl.bilibili.chat.entity.PPTWord;
import ljl.bilibili.chat.ppt.*;
import ljl.bilibili.chat.handler.PPTHandler;
import ljl.bilibili.chat.mapper.ChatServiceMapper;
import ljl.bilibili.chat.service.ChatService;
import ljl.bilibili.chat.vo.response.HistoryChatResponse;
import ljl.bilibili.chat.vo.request.ChatSessionRequest;
import ljl.bilibili.chat.vo.request.ChangeChatStatusRequest;
import ljl.bilibili.chat.vo.response.ChatSessionResponse;
import ljl.bilibili.chat.vo.response.PPTResponse;
import ljl.bilibili.chat.vo.response.TempSessionResponse;
import ljl.bilibili.entity.chat.Chat;
import ljl.bilibili.entity.chat.ChatSession;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.mapper.chat.ChatMapper;
import ljl.bilibili.mapper.chat.ChatSessionMapper;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ljl.bilibili.chat.constant.Constant.*;

/**
 *聊天service
 */
@Service
@Slf4j
public class ChatServiceImpl implements ChatService {
    @Resource
    ChatSessionMapper chatSessionMapper;
    @Resource
    ChatMapper chatMapper;
    @Resource
    UserMapper userMapper;
    @Resource
    ChatServiceMapper chatServiceMapper;
    /**
     * 修改私聊消息状态（通常用于将未读消息标记为已读）
     * @param changeChatStatusRequest 消息状态修改请求对象，包含接收者ID和当前用户ID
     * 接收者receiver_id就是当前聊天会话的那一头，当前用户ID:user_id
     * 当前用户(user_id)收到的、来自指定用户(receiver_id)的未读消息标记为已读
     * 在当前窗口user_id是发送者，指定用户是接收者，将receiver_id发送过来的消息标记为已读）
     * @return 操作结果，成功返回true
     */
    @Override
    public Result<Boolean> changeChatStatus(ChangeChatStatusRequest changeChatStatusRequest) {
        // 创建Chat实体的更新条件构造器
        LambdaUpdateWrapper<Chat> wrapper = new LambdaUpdateWrapper<>();
        // 设置消息状态为1（已读状态）
        wrapper.set(Chat::getStatus, 1);
        // 条件：发送者ID等于请求中的接收者ID（对方发送的消息）
        wrapper.eq(Chat::getSenderId, changeChatStatusRequest.getReceiverId());
        // 条件：接收者ID等于请求中的当前用户ID（当前用户接收的消息）
        wrapper.eq(Chat::getReceiverId, changeChatStatusRequest.getUserId());
        // 执行更新操作（更新符合条件的消息状态）
        chatMapper.update(null, wrapper);
        // 返回操作成功结果
        return Result.success(true);
    }
    /**
     *创建临时会话
     */
    @Override
    public Result<TempSessionResponse> createTempSession(Integer receiverId){
        User u=userMapper.selectById(receiverId);
        return Result.data(new TempSessionResponse().setCover(u.getCover()).setNickName(u.getNickname()));
    }
    /**
     *修改会话chat_session的最后聊天时间和内容
     */
    @Override
    public Result<Boolean> changeChatSessionTime(ChatSessionRequest chatSessionRequest) {
        LambdaUpdateWrapper<ChatSession> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ChatSession::getSenderId, chatSessionRequest.getSenderId());
        wrapper.eq(ChatSession::getReceiverId, chatSessionRequest.getReceiverId());
        wrapper.set(ChatSession::getUpdateTime, LocalDateTime.now());
        wrapper.set(ChatSession::getUpdateContent, chatSessionRequest.getUpdateContent());
        chatSessionMapper.update(null, wrapper);
        return Result.success(true);
    }


    /**
     * 获取用户的历史聊天会话列表
     * 包含会话基本信息、未读消息数及最新更新时间，并按时间倒序排序
     * @param userId 当前用户ID（用于查询该用户参与的所有会话）
     * @return 封装了会话列表的Result对象，每一个元素包含会话ID、对方信息、未读消息数、最后一次聊天内容、最后一次聊天时间等
     */
    @Override
    public Result<List<ChatSessionResponse>> getHistoryChatSession(Integer userId) {
        // 查询当前用户作为「发送者」的会话列表（正向会话）
        List<ChatSessionResponse> selfResponses = chatServiceMapper.getSelfSession(userId);
        // 查询当前用户作为「接收者」的会话列表（反向会话）
        List<ChatSessionResponse> otherResponses = chatServiceMapper.getOtherSession(userId);
        // 合并正向和反向会话列表，确保会话完整性（避免遗漏与其他用户的双向会话）
        selfResponses.addAll(otherResponses);

        // 提取所有会话的ID，用于批量查询未读消息数
        List<Integer> idList = new ArrayList<>();
        // 遍历合并后的会话列表，收集会话ID
        for (ChatSessionResponse sessionResponse : selfResponses) {
            idList.add(sessionResponse.getSessionId());
        }

        // 若存在会话，查询每个会话的未读消息数并更新会话状态
        if (idList.size() > 0) {
            // 批量获取指定会话ID列表中，当前用户未读的消息数（NoticeCount包含sessionId和noticeCount）
            List<NoticeCount> noticeCounts = chatServiceMapper.getNoticeCounts(idList, userId);

            // 遍历每个会话，匹配对应的未读消息数并设置状态
            for (ChatSessionResponse sessionResponse : selfResponses) {
                for (NoticeCount noticeCount : noticeCounts) {
                    // 会话ID匹配时，更新未读消息数和状态
                    if (noticeCount.getSessionId().equals(sessionResponse.getSessionId())) {
                        // 若未读消息数>0，设置未读数量并标记会话为「未读状态」（status=false）
                        if (noticeCount.getNoticeCount() > 0) {
                            sessionResponse.setCount(noticeCount.getNoticeCount());
                            sessionResponse.setStatus(false); // false表示有未读消息
                            break; // 匹配到当前会话后跳出内层循环，避免重复处理
                        }
                    }
                }
            }
        }
        // 按会话最后更新时间倒序排序（最新活跃的会话排在最前面）
        if (selfResponses.size() > 0) {
            selfResponses = selfResponses.stream()
                    .sorted(Comparator.comparing(ChatSessionResponse::getUpdateTime).reversed()) // 按updateTime降序
                    .collect(Collectors.toList());
        }
        // 返回封装了会话列表的成功结果
        return Result.data(selfResponses);
    }




    /**
     * 新增聊天会话和内容
     * 处理逻辑：检查会话是否已存在，不存在则创建新会话，存在则更新会话最后时间和内容，同时保存聊天消息
     * @param chatSessionRequest 聊天会话请求对象，包含发送者ID、接收者ID、聊天内容等信息
     * @return 操作结果，成功返回true
     *
     *
     */
    @Override
    public Result<Boolean> addChatSessionAndContent(ChatSessionRequest chatSessionRequest) {
        // 查询之前是否存在自己向他人发起的会话（正向会话）
        LambdaQueryWrapper<ChatSession> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(ChatSession::getSenderId, chatSessionRequest.getSenderId());
        wrapper1.eq(ChatSession::getReceiverId, chatSessionRequest.getReceiverId());

        // 查询之前是否存在他人向自己发起的会话（反向会话）
        LambdaQueryWrapper<ChatSession> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.eq(ChatSession::getSenderId, chatSessionRequest.getReceiverId());
        wrapper2.eq(ChatSession::getReceiverId, chatSessionRequest.getSenderId());

        // 执行查询，获取正向和反向会话
        ChatSession c1 = chatSessionMapper.selectOne(wrapper1);
        ChatSession c2 = chatSessionMapper.selectOne(wrapper2);

        // 将请求参数转换为会话实体，并设置最后更新时间
        ChatSession chatSession = chatSessionRequest.toSessionEntity();
        chatSession.setUpdateTime(LocalDateTime.now());

        /**
         * 如果正向和反向会话均不存在（首次创建会话）
         * 会话只存储单向的
          */
        if (c1 == null && c2 == null) {
            // 插入新的聊天会话记录
            chatSessionMapper.insert(chatSession);
        } else {
            // 会话已存在，更新会话的最后聊天内容和时间
            chatServiceMapper.updateChatSession(
                chatSessionRequest.getUpdateContent(),
                LocalDateTime.now(),
                chatSessionRequest.getSenderId(),
                chatSessionRequest.getReceiverId()
            );
        }

        // 将聊天内容插入聊天记录表（关联当前会话的最新内容）
        chatMapper.insert(chatSessionRequest.toChatEntity().setContent(chatSession.getUpdateContent()));

        // 返回操作成功结果
        return Result.success(true);
    }


    /**
     *获取某会话历史聊天内容
     */
    /**
     * 获取指定用户间的历史聊天记录
     * 包含双方发送的所有消息，并按消息创建时间倒序排序（最新消息在前）
     * @param userId 当前用户ID（消息发送者或接收者）
     * @param receiverId 聊天对方用户ID（消息接收者或发送者）
     * @return 封装了历史消息列表的Result对象，每条消息包含发送者、接收者、内容、时间等信息
     */
    @Override
    public Result<List<HistoryChatResponse>> getHistoryChat(Integer userId, Integer receiverId) {
        // 构建查询条件1：当前用户作为发送者，对方作为接收者的消息
        LambdaQueryWrapper<Chat> wrapper1 = new LambdaQueryWrapper<>();
        wrapper1.eq(Chat::getSenderId, userId);
        wrapper1.eq(Chat::getReceiverId, receiverId);

        // 构建查询条件2：当前用户作为接收者，对方作为发送者的消息（双向消息查询）
        LambdaQueryWrapper<Chat> wrapper2 = new LambdaQueryWrapper<>();
        wrapper2.eq(Chat::getReceiverId, userId);
        wrapper2.eq(Chat::getSenderId, receiverId);

        // 执行查询：获取当前用户发送给对方的消息列表
        List<Chat> list1 = chatMapper.selectList(wrapper1);
        // 执行查询：获取对方发送给当前用户的消息列表
        List<Chat> list2 = chatMapper.selectList(wrapper2);

        // 合并双向消息列表，形成完整的聊天记录
        list1.addAll(list2);

        // 将Chat实体列表转换为HistoryChatResponse DTO列表（适配前端展示）
        List<HistoryChatResponse> responses = new ArrayList<>();
        for (Chat chat : list1) {
            responses.add(new HistoryChatResponse(chat));
        }
        // 按消息创建时间倒序排序（确保最新消息显示在最前面）
        responses = responses.stream()
                .sorted(Comparator.comparing(HistoryChatResponse::getCreateTime).reversed())
                .collect(Collectors.toList());

        // 返回封装了排序后历史消息列表的结果
        return Result.data(responses);
    }


    public PPTResponse getPPT(String describe) throws IOException {
        long timestamp = System.currentTimeMillis() / 1000;
        String ts = String.valueOf(timestamp);
        // 获得鉴权信息
        ApiAuthAlgorithm auth = new ApiAuthAlgorithm();
        String signature = auth.getSignature(appId, apiSecret, timestamp);
        ApiClient client = new ApiClient("https://zwapi.xfyun.cn");
        // 大纲生成
        String outlineResp = client.createOutline(appId, ts, signature, describe);
        CreateResponse outlineResponse = JSON.parseObject(outlineResp, CreateResponse.class);
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineVo outlineVo = objectMapper.readValue(outlineResponse.getData().getOutline(), OutlineVo.class);
        List<String> chapterList = new ArrayList<>();
        Map<String, Object> chapterMap = new HashMap<>();
        int i = 1, j = 1;
        String prefix="主题是"+outlineVo.getTitle()+",副主题是"+outlineVo.getSubTitle()+",大纲是";
        for(OutlineVo.Chapter chapter : outlineVo.getChapters()){
            prefix=prefix+chapter.chapterTitle+",";
            if(chapter.chapterContents.size()>0){
                for(OutlineVo.Chapter chapter1 : chapter.chapterContents){
                    prefix=prefix+chapter1.chapterTitle+",";
                }
            }
        }
        for (OutlineVo.Chapter chapter : outlineVo.getChapters()) {
            //对每个大纲内部调用讯飞星火的提问api进行扩展详情
            chapterMap.put(String.valueOf(i), chapter.chapterTitle);
            chapterList.add(String.valueOf(i));
            for (OutlineVo.Chapter chapter1 : chapter.chapterContents) {
                chapterList.add(i + "." + j);
                PPTHandler pptHandler = new PPTHandler(chapter1.chapterTitle,appId,apiKey,apiSecret);
                chapterMap.put((i + "." + j), pptHandler);
                j++;
                try {
                    pptHandler.send(prefix+"现在的详细解说内容是:"+chapter1.chapterTitle);
                    while (pptHandler.pptDetail.getGenerateEnding() == false) {
                        Thread.sleep(200);
                        log.info(pptHandler.pptDetail.getThemeName() + "还没打印完");
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }
            i++;
            j = 1;
//        }
        }
        //将扩展后的ppt大纲和大纲扩展后的详情统一封装到集合对象里
        List<PPTWord> pptWords = new ArrayList<>();
        Pattern pattern = Pattern.compile("^[1-9]$");
        for (String index : chapterList) {
            if (pattern.matcher(index).matches()) {
                pptWords.add(new PPTWord().setIndex(index).setThemeName((String) chapterMap.get(index)));
            } else {
                PPTHandler pptHandler = (PPTHandler) chapterMap.get(index);
                pptWords.add(new PPTWord().setIndex(index).setThemeName(pptHandler.pptDetail.getThemeName()).setText(pptHandler.pptDetail.getText()));
            }
            if (chapterMap.get(index) instanceof String) {
                log.info((String) chapterMap.get(index));
            } else {
                PPTHandler pptHandler = (PPTHandler) chapterMap.get(index);
                log.info(pptHandler.pptDetail.getText());
            }

        }
        return new PPTResponse().setPptWordList(pptWords).setCoverImgSrc(outlineResponse.getData().getCoverImgSrc());
    }
    /**
     *获取讯飞星火图片响应
     */
    public String getImage(String text) throws Exception {
        //绑定图片响应地址
        String url="https://spark-api.cn-huabei-1.xf-yun.com/v2.1/tti";

        String authUrl= MyUtil.getAuthUrl(url,apiKey,apiSecret);
        //特定json格式发送请求到讯飞星火
        String json = "{\n" +
                "  \"header\": {\n" +
                "    \"app_id\": \"" + appId + "\",\n" +
                "    \"uid\": \"" + UUID.randomUUID().toString().substring(0, 15) + "\"\n" +
                "  },\n" +
                "  \"parameter\": {\n" +
                "    \"chat\": {\n" +
                "      \"domain\": \"s291394db\",\n" +
                "      \"temperature\": 0.5,\n" +
                "      \"max_tokens\": 4096,\n" +
                "      \"width\": 1024,\n" +
                "      \"height\": 1024\n" +
                "    }\n" +
                "  },\n" +
                "  \"payload\": {\n" +
                "    \"message\": {\n" +
                "      \"text\": [\n" +
                "        {\n" +
                "          \"role\": \"user\",\n" +
                "           \"content\": \"" + text + "\"\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "}";
        //获取响应的Base64编码
        String res = MyUtil.doPostJson(authUrl, null, json);
        return res;
    }
}
