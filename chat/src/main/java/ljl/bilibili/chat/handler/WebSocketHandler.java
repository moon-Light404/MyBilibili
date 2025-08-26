package ljl.bilibili.chat.handler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ljl.bilibili.chat.entity.ChatMessage;
import ljl.bilibili.chat.event.MessageEvent;
import ljl.bilibili.client.notice.SendNoticeClient;
import ljl.bilibili.entity.chat.Chat;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.mapper.chat.ChatMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static ljl.bilibili.chat.constant.Constant.*;
/**
 *私聊与大模型处理器
 */
@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {
    @Resource
    ApplicationEventPublisher applicationEventPublisher;
    public static final Gson gson = new Gson();
    // sessionId -> session 管理所有活跃的websocket会话
    public static volatile ConcurrentMap<String, WebSocketSession> WEB_SOCKET_SESSION_CONCURRENT_MAP = new ConcurrentHashMap<>();
    // user_id -> sessionId 管理用户ID与sessionId的映射关系，用于路由
    public static  Map<String, String> USERID_TO_SESSIONID_MAP = new ConcurrentHashMap<>();
    public static volatile ConcurrentMap<String, BigModelHandler> BIGMODEL_MAP = new ConcurrentHashMap<>();
    @Resource
    ChatMapper chatMapper;

    @EventListener
    @Async
    /**
     * 监听大模型响应事件并返回相应客户端（服务器内部事件触发的推送消息）
     *      大模型处理完成后的响应结果，将结果主动推送给客户端
     *      用于服务端异步处理完成后，将结果反馈给客户端
     *      当服务端其他组件（如大模型处理器 BigModelHandler ) 通过 ApplicationEventPublisher 发布 MessageEvent时，由 Spring 的事件机制自动触发。
     */
    public void handleMessageEvent(MessageEvent event) throws IOException {
        ChatMessage message = gson.fromJson(event.getMessage(), ChatMessage.class);
        JsonObject jsonText = new JsonObject();
        // 构建大模型回复消息的JSON对象
        jsonText.addProperty(MESSAGE_STATUS, message.getStatus());
        jsonText.addProperty(MESSAGE_TYPE, MESSAGE_TYPE_BIGMODEL);
        jsonText.addProperty(MESSAGE_CONTENT, message.getContent());
        // 发送大模型回复消息到指定用户的WebSocket会话
        WEB_SOCKET_SESSION_CONCURRENT_MAP.get(USERID_TO_SESSIONID_MAP.get(message.getUserId())).sendMessage(new TextMessage(jsonText.toString()));
    }
    /**
     *根据type处理接收的私聊与大模型消息
     */
    /**
     * 处理WebSocket客户端发送的文本消息，根据消息类型执行不同业务逻辑
     * 支持初始化会话、私聊消息转发、大模型交互、会话清理等功能
     * {
     *   "type": "message",       // 消息类型，固定为 "message"（Constant.MESSAGE_TYPE_MESSAGE）
     *   "userId": "123",         // 发送者用户 ID（Constant.USER_IDENTITY）
     *   "receiverId": "456",     // 接收者用户 ID（Constant.RECEIVER_IDENTITY）
     *   "content": "你好，这是一条私聊消息"  // 消息内容（字符串或 JSON 对象字符串，Constant.MESSAGE_CONTENT）
     *   "sessionId": "xxx", // websocket会话ID
     * }
     * @param session WebSocket会话对象，代表与客户端的连接
     * @param message 客户端发送的文本消息对象，JSON格式
     * @throws Exception 处理过程中可能抛出的异常（如JSON解析失败、网络异常等）
     */
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 将客户端发送的JSON字符串解析为JsonObject对象，提取消息内容
        JsonObject json = JsonParser.parseString(message.getPayload()).getAsJsonObject();
        // 获取消息类型字段（type），用于区分不同业务场景
        String type = json.get(MESSAGE_TYPE).getAsString();
        // 根据消息类型执行不同的处理逻辑
        switch (type) {
            // 【大模型交互】：客户端向大模型发送提问，处理后返回响应
            case MESSAGE_TYPE_BIGMODEL:
                // 提取客户端发送的提问内容
                String question = json.get(MESSAGE_TYPE_BIGMODEL_QUESTION).getAsString();
                // 提取当前用户ID（用于标识大模型对话上下文）
                String id = json.get(USER_IDENTITY).getAsString();
                // 检查是否已存在该用户的大模型处理器实例（复用对话上下文）
                if (BIGMODEL_MAP.get(id) != null) {
                    // 复用现有处理器，发送新提问
                    BIGMODEL_MAP.get(id).send(question, id);
                } else {
                    // 新建大模型处理器实例，绑定事件发布器（用于响应结果推送）
                    BigModelHandler bigModelHandler = new BigModelHandler(applicationEventPublisher);
                    // 缓存处理器实例，key为用户ID
                    BIGMODEL_MAP.put(id, bigModelHandler);
                    // 发送提问内容
                    bigModelHandler.send(question, id);
                }
                break;

            // 【会话初始化】：客户端建立连接后初始化用户与会话的映射关系
            case MESSAGE_TYPE_INIT:
                log.info("WebSocket会话初始化");
                // 提取客户端生成的sessionId（用于唯一标识连接session_id）
                String sessionId = json.get(MESSAGE_TYPE_SESSIONID).getAsString();
                // 提取当前用户ID（业务层用户标识user_id）
                String userId = json.get(USER_IDENTITY).getAsString();
                // 存储user_id与sessionId的映射关系（用于后续消息路由）
                USERID_TO_SESSIONID_MAP.put(userId, sessionId);
                // 存储sessionId与WebSocketSession的映射关系（用于直接操作连接发送消息）
                WEB_SOCKET_SESSION_CONCURRENT_MAP.put(sessionId, session);
                break;

            // 【私聊消息】：转发用户间的私聊消息，支持在线实时推送和离线消息存储
            case MESSAGE_TYPE_MESSAGE:
                // 提取消息接收者ID receiver_id
                String receiverId = json.get(RECEIVER_IDENTITY).getAsString();
                // 提取消息内容（兼容JSON对象和普通字符串类型） content
                JsonElement jsonElement = json.get(MESSAGE_CONTENT);
                String content;
                if (jsonElement.isJsonPrimitive()) {
                    // 若内容为基本类型（如字符串），直接提取字符串值
                    content = jsonElement.getAsString();
                    log.info("消息内容为JSON基本类型");
                } else {
                    // 若内容为复杂JSON对象，序列化为字符串存储
                    content = jsonElement.toString();
                    log.info("消息内容为JSON对象");
                }

                // 调试日志：打印当前在线会话数量及详情（生产环境可移除）
                log.info("当前在线会话数：" + WEB_SOCKET_SESSION_CONCURRENT_MAP.size());
                for (Map.Entry<String, WebSocketSession> entry : WEB_SOCKET_SESSION_CONCURRENT_MAP.entrySet()) {
                    log.info("会话映射：Key = " + entry.getKey() + ", Value = " + entry.getValue());
                }
                for (Map.Entry<String, String> entry : USERID_TO_SESSIONID_MAP.entrySet()) {
                    log.info("用户-会话映射：Key = " + entry.getKey() + ", Value = " + entry.getValue());
                }
                log.info("用户-会话映射数：" + USERID_TO_SESSIONID_MAP.size());
                // 检查接收者是否在线（通过用户ID查询sessionId）
                if (USERID_TO_SESSIONID_MAP.get(receiverId) != null) {
                    // 构造转发消息JSON（包含消息类型、内容、发送者ID）
                    JsonObject jsonText = new JsonObject();
                    jsonText.addProperty(MESSAGE_TYPE, MESSAGE_TYPE_MESSAGE);
                    jsonText.addProperty(MESSAGE_CONTENT, content);
                    jsonText.addProperty("senderId", json.get(USER_IDENTITY).getAsString());
                    try {
                        // 通过sessionId获取接收者的WebSocket连接，发送消息
                        WEB_SOCKET_SESSION_CONCURRENT_MAP.get(USERID_TO_SESSIONID_MAP.get(receiverId))
                            .sendMessage(new TextMessage(jsonText.toString()));
                    } catch (Exception e) {
                        // 发送异常时打印堆栈信息（可扩展为消息重试或错误通知）
                        e.printStackTrace();
                    }
                } else {
                    // 接收者不在线，将消息存储到数据库（待接收者上线后拉取）
                    Chat chat = new Chat()
                        .setContent(content)
                        .setSenderId(Integer.valueOf(json.get(USER_IDENTITY).getAsString()))  // 发送者ID
                        .setReceiverId(Integer.valueOf(receiverId));  // 接收者ID
                    chatMapper.insert(chat);  // 插入聊天记录表
                }
                break;

            // 【移除会话】：清理大模型交互的会话资源（如用户主动结束对话）
            case MESSAGE_TYPE_REMOVE_SESSION:
                // 提取当前用户ID
                String chatToBigModelUserId = json.get(USER_IDENTITY).getAsString();
                // 调用大模型处理器的移除会话方法（释放资源或终止连接）
                BIGMODEL_MAP.get(chatToBigModelUserId).removeSession();
                break;
            // 未识别的消息类型，不做处理
            default:
                break;
        }
    }
    /**
     * 在客户端与服务端建立 WebSocket 连接后，完成会话初始化并向客户端返回会话标识
     *后端返回给前端的第一条WebSocket消息，携带客户端sessionId
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //将客户端的sessionId和websocket对象绑定到一起
        WEB_SOCKET_SESSION_CONCURRENT_MAP.put(session.getId(), session);
        JsonObject json = new JsonObject();
        // type: sessionId
        // "sessionId" : sessionId
        json.addProperty(MESSAGE_TYPE, MESSAGE_TYPE_SESSIONID);
        json.addProperty(MESSAGE_TYPE_SESSIONID, session.getId());
        // 向客户端发送会话标识消息，携带客户端sessionId
        session.sendMessage(new TextMessage(json.toString()));
        log.info("连接成功");
    }
    /**
     *关闭连接后及时从服务端移除会话
     */
    /**
     * WebSocket连接关闭时的资源清理方法
     * 移除用户与会话的映射关系及会话对象，避免内存泄漏和无效连接残留
     * @param session 关闭的WebSocket会话对象
     * @param status 连接关闭状态信息（包含关闭原因、状态码等）
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 日志记录连接关闭事件
        log.info("WebSocket连接已关闭");

        // 遍历用户ID与sessionId的映射关系，移除与当前关闭会话关联的用户映射
        for (Map.Entry<String, String> entry : USERID_TO_SESSIONID_MAP.entrySet()) {
            log.info("已删除过期的WebSocket会话对象");
            // 从会话映射表中移除当前关闭的WebSocketSession对象
            // 匹配会话ID：若entry的值（sessionId）等于当前关闭会话的ID
            log.info("已删除用户与过期会话的映射关系");
            if (entry.getValue().equals(session.getId())) {
                // 遍历移除该用户ID的映射（解除用户与失效会话的绑定）
                USERID_TO_SESSIONID_MAP.remove(entry.getKey());
                log.info("已删除过期连接");
            }
        }
        // sessionId -> WebSocketSession 删除映射
        WEB_SOCKET_SESSION_CONCURRENT_MAP.remove(session.getId());
        log.info("已删除过期连接");
    }
}


/**
 * 问题：为什么不直接存储user_id到session的映射，还要搞一个user_id到session_id的映射
 * 解释：
 * 核心原因是 会话生命周期与用户身份绑定的解耦 及 系统灵活性与效率优化，具体可从以下角度分析：
 * 1. 会话建立与用户身份绑定的「时间差」问题
 * WebSocket 会话（WebSocketSession）的创建早于用户身份的确认。
 * - 连接建立阶段：客户端与服务端建立 WebSocket 连接时（afterConnectionEstablished 方法），服务端会立即生成唯一的 sessionId，并将 sessionId 与 WebSocketSession 对象存入
 * WEB_SOCKET_SESSION_CONCURRENT_MAP。此时用户可能尚未完成登录或身份验证（例如未发送 init 类型消息），user_id 尚未确定，无法直接绑定用户与会话。
 * - 用户绑定阶段：客户端后续通过 init 类型消息（MESSAGE_TYPE_INIT）发送 user_id 和 sessionId，服务端才会将 user_id 与 sessionId 绑定到 USERID_TO_SESSIONID_MAP。
 * 若直接使用 user_id -> WebSocketSession 映射，无法处理「会话已建立但用户未登录」的中间状态，导致会话对象无法被及时存储和管理。
 */
