package ljl.bilibili.chat.controller;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import ljl.bilibili.chat.service.ChatService;
import ljl.bilibili.chat.vo.response.HistoryChatResponse;
import ljl.bilibili.chat.vo.request.ChatSessionRequest;
import ljl.bilibili.chat.vo.response.ChatSessionResponse;
import ljl.bilibili.chat.vo.request.ChangeChatStatusRequest;
import ljl.bilibili.chat.vo.response.PPTResponse;
import ljl.bilibili.chat.vo.response.TempSessionResponse;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/chat")
@Api(tags = "获取历史聊天记录和聊天对象列表")
@Slf4j
public class ChatController {
    @Resource
    ChatService chatService;

    /**
     * 根据文本描述生成PPT文档
     * @param describe PPT内容描述文本（如"生成一份Spring Boot入门PPT"）
     * @return PPTResponse - 包含生成的PPT文件信息（如封面、PPT详情等）
     * @throws IOException 文档生成过程中IO异常
     * @throws InterruptedException 线程等待超时异常（如调用外部API时）
     */
    @GetMapping("/getPPT/{describe}")
    public PPTResponse getPPT(@PathVariable String describe) throws IOException, InterruptedException {
        return chatService.getPPT(describe);
    }
    /**
     * 根据文本描述生成图片
     * @param text 图片内容描述文本（如"一只猫坐在键盘上"）
     * @return String - 图片资源标识（可能为URL链接或Base64编码字符串）
     * @throws Exception 图片生成过程中异常（如调用AI绘图接口失败）
     */
    @GetMapping("/getImage/{text}")
    public String getImage(@PathVariable String text) throws Exception {
        return chatService.getImage(text);
    }

    /**
     * 创建临时聊天会话（无需添加好友即可发起聊天）
     * @param receiverId 接收方用户ID（被聊天的用户）
     * @return Result<TempSessionResponse> - 临时会话创建结果，包含会话ID等信息
     */
    @GetMapping("/createTempSession/{receiverId}")
    @ApiOperation("创建临时会话")
    public Result<TempSessionResponse> createTempSession(@PathVariable Integer receiverId){
        return chatService.createTempSession(receiverId);
    }

    /**
     * 获取两个用户之间的历史聊天记录
     * @param userId 当前用户ID（消息发送方/查看方）
     * @param receiverId 聊天对方用户ID（消息接收方）
     * @return Result<List<HistoryChatResponse>> - 历史消息列表，包含每条消息的内容、时间、发送者等信息
     */
    @GetMapping("/getHistoryChat/{userId}/{receiverId}")
    @ApiOperation("获取历史聊天记录")
    public Result<List<HistoryChatResponse>> getHistoryChat(@PathVariable Integer userId, @PathVariable Integer receiverId){
        return chatService.getHistoryChat(userId,receiverId);
    }

    /**
     * 获取用户的历史聊天会话列表（聊天窗口列表）
     * @param userId 当前用户ID（消息发送方/查看方）
     * @return Result<List<ChatSessionResponse>> - 历史会话列表，包含每个会话的基本信息（如会话ID、对方用户ID、最后聊天时间、最后聊天内容、未读消息数等）
     */
    @ApiOperation("获取历史聊天会话列表")
    @GetMapping("/getHistoryChatSession/{userId}")
    public Result<List<ChatSessionResponse>> getHistoryChatSession(@PathVariable Integer userId){
        return chatService.getHistoryChatSession(userId);
    }

    /**
     * 修改聊天记录的状态从未读到已读
     * @param changeChatStatusRequest 包含用户id 和 聊天会话另一头的用户id
     * @return Result<Boolean> - 修改结果，成功返回true，失败返回false
     */
    @ApiOperation("修改聊天记录的状态从未读到已读")
    @PostMapping("/changeChatStatus")
    public Result<Boolean> changeChatStatus(@RequestBody ChangeChatStatusRequest changeChatStatusRequest){
        return chatService.changeChatStatus(changeChatStatusRequest);
    }

    /**
     * 修改聊天会话的最后聊天时间和最后聊天内容
     * @param chatSessionRequest 包含发送方id 和 接收方id 和 更新内容
     * @return Result<Boolean> - 修改结果，成功返回true，失败返回false
     */
    @ApiOperation("修改聊天会话的最后聊天时间和最后聊天内容")
    @PostMapping("/changeChatSessionTime")
    public Result<Boolean> changeChatSessionTime(@RequestBody ChatSessionRequest chatSessionRequest){
        
        return chatService.changeChatSessionTime(chatSessionRequest);
    }

    /**
     * 新增聊天会话/新增聊天内容
     * @param chatSessionRequest 包含发送方id 和 接收方id 和 更新内容
     * @return Result<Boolean> - 修改结果，成功返回true，失败返回false
     */
    @ApiOperation("/新增聊天会话和聊天内容")
    @PostMapping("/addChatSessionAndContent")
    public Result<Boolean> addChatSessionAndContent(@RequestBody ChatSessionRequest chatSessionRequest){
        
        return chatService.addChatSessionAndContent(chatSessionRequest);
    }
}
