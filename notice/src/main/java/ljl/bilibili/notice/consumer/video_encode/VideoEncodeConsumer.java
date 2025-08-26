package ljl.bilibili.notice.consumer.video_encode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.client.file.CustomMultipartFile;
import ljl.bilibili.client.pojo.UploadVideo;
import ljl.bilibili.client.video.VideoClient;
import ljl.bilibili.entity.notice.dynamic.Dynamic;
import ljl.bilibili.entity.video.video_production.upload.Video;
import ljl.bilibili.mapper.notice.dynamic.DynamicMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoMapper;
import ljl.bilibili.notice.service.send_notice.impl.SendDBChangeServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import ws.schild.jave.*;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.encode.VideoAttributes;
import ws.schild.jave.info.VideoInfo;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static ljl.bilibili.notice.constant.Constant.*;
/**
 *视频转码消费者
 * 负责监听视频上传后的转码、封面生成、时长计算、格式转换等核心媒体处理流程，确保视频符合播放标准并同步元数据到数据库。
 *
 * 1. 消息解析与视频获取
 * 解析消息：将 RocketMQ 消息体（JSON 格式）反序列化为 UploadVideo 对象，提取视频 ID、名称、是否有封面等元数据。
 * 获取视频流：通过 VideoClient.getVideo(uploadVideo) 远程调用，从 MinIO 存储获取已上传的视频文件流。
 *
 * 2. 临时文件管理
 * 创建临时目录（Files.createTempDirectory(".tmp")）存储视频文件和生成的封面，避免永久占用磁盘空间。
 *
 * 3. 封面生成与上传（无封面时）
 * 触发条件：uploadVideo.getHasCover() == false（用户未上传封面）。
 * 封面截取：使用 ScreenExtractor 从视频第 1000ms 处截取一帧画面，保存为 JPG 格式封面文件。
 * 封面上传与同步：
 * 通过 VideoClient.uploadVideoCover(...) 将封面上传至 MinIO，生成封面 URL（如 https://labilibili.com/video-cover/xxx.jpg）。
 * 更新 Video 表的 cover 字段及 Dynamic 表的 videoCover 字段（动态列表展示封面）。
 *
 * 4. 视频时长计算与同步
 * 提取时长：通过 multimediaObject.getInfo().getDuration() 获取视频原始时长（毫秒），转换为秒后格式化为 MM:SS 字符串（如 01:23）。
 * 数据同步：
 * 更新 Video 表的 length 字段（视频时长）。
 * 调用 sendDBChangeService.sendDBChangeNotice(...) 发送数据库变更通知，确保 Elasticsearch 等搜索引擎同步最新时长信息。
 * 5. 视频格式转码（H.264 标准化）
 * 触发条件：视频解码器（videoInfo.getDecoder()）非 h264（浏览器兼容的主流编码格式）。
 * 转码流程：
 * 使用 JAVE 库将视频转码为 h264 编码的 MP4 格式（设置 VideoAttributes.setCodec("h264")）。
 * 转码后通过 VideoClient.uploadVideo(...) 将新视频上传至 MinIO，覆盖原文件。
 * 资源清理：删除临时转码文件和原始视频文件，释放磁盘空间。
 */
@Service
@RocketMQMessageListener(
        topic = "video-encode",
        consumerGroup = "video-encode-group",
        consumeMode = ConsumeMode.ORDERLY
)
@Slf4j
@Deprecated
public class VideoEncodeConsumer implements RocketMQListener<MessageExt> {
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    VideoClient videoClient;
    @Autowired
    VideoMapper videoMapper;
    @Autowired
    SendDBChangeServiceImpl sendDBChangeService;
    @Autowired
    DynamicMapper dynamicMapper;
    /**
     *转码视频、如果视频无封面则截取封面、获取视频时长
     */
    @Override
    public void onMessage(MessageExt messageExt) {
        String jsonMessage = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        try {
            UploadVideo uploadVideo = objectMapper.readValue(jsonMessage, UploadVideo.class);
            //远程调用获取已上传minio的视频文件流
            ResponseEntity<Resource> videoInputStream = videoClient.getVideo(uploadVideo);
                        String filePath = Files.createTempDirectory(".tmp").toString();
//            String filePath = "/var/temp";
            Video updateVideo=new Video().setId(uploadVideo.getVideoId());
            String videoFileName="video";
            // 创建临时目录存储视频和封面文件（避免永久占用磁盘）
            File file = new File(filePath, videoFileName);
            String coverFileName = uploadVideo.getVideoName() + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
            File coverFile = new File(filePath, coverFileName);
            Files.copy(videoInputStream.getBody().getInputStream(), Paths.get(file.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
            MultimediaObject multimediaObject = new MultimediaObject(file);
            VideoInfo videoInfo = multimediaObject.getInfo().getVideo();
            //如果视频没有封面
            if (uploadVideo.getHasCover() == false) {
                String contentType = "image/jpeg";
                // 从视频中截取一帧（默认第 1000ms 处画面）作为封面：
                ScreenExtractor screenExtractor = new ScreenExtractor();
                screenExtractor.renderOneImage(multimediaObject, -1, -1, 1000, coverFile, 1);
                // 上传封面到 MinIO 并更新数据库
                CustomMultipartFile coverMultipartFile = new CustomMultipartFile(new FileInputStream(coverFile), coverFileName, contentType);
                // 远程调用上传封面
                videoClient.uploadVideoCover(coverMultipartFile);
                String prefixPath="https://labilibili.com/video-cover/";
                String cover=prefixPath+coverFileName;
                updateVideo.setCover(cover);
                LambdaUpdateWrapper<Dynamic> wrapper=new LambdaUpdateWrapper<>();
                wrapper.set(Dynamic::getVideoCover,cover);
                wrapper.eq(Dynamic::getVideoId,updateVideo.getId());
                dynamicMapper.update(null,wrapper);
            }
            //获取视频时长
            Integer totalLength = Math.toIntExact(multimediaObject.getInfo().getDuration()) / 1000;
            String length;
            Map<String, Object> map = new HashMap<>();
            if (totalLength / 60 < 1) {
                if (totalLength % 60 < 10) {
                    length = "00:0" + totalLength;
                } else {
                    length = "00:" + totalLength;
                }
            } else {
                if (totalLength / 60 < 10) {
                    if(totalLength%60<10){
                        length = "0" + totalLength / 60 + ":0" + totalLength % 60;
                    }else {
                        length = "0" + totalLength / 60 + ":" + totalLength % 60;
                    }
                } else {
                    length = totalLength / 60 + ":" + totalLength % 60;
                }
            }
            // 同步时长到数据库并发送变更通知
            map.put(OPERATION_TYPE, OPERATION_TYPE_UPDATE);
            map.put(TABLE_NAME, VIDEO_TABLE_NAME);
            // 视频时长length
            map.put(VIDEO_LENGTH, length);
            map.put(VIDEO_ID, uploadVideo.getVideoId());
            // 发送数据库变更通知（用于同步到 ES 等）
            sendDBChangeService.sendDBChangeNotice(map);
            videoMapper.updateById(updateVideo.setLength(length));
            String rightFormat = "h264";
            //不符合h.264的mp4文件需要转码否则浏览器中只有声音没有图像
            if (!rightFormat.equals(videoInfo.getDecoder())) {
                String contentType = "video/mp4";
                String outPutForMatType = "mp4";
                VideoAttributes videoAttributes = new VideoAttributes();
                String targetFileName = "target";
                File target = new File(filePath, targetFileName);
                videoAttributes.setCodec(rightFormat);
                AudioAttributes audio = new AudioAttributes();
                EncodingAttributes attrs = new EncodingAttributes();
                attrs.setOutputFormat(outPutForMatType);
                attrs.setAudioAttributes(audio);
                attrs.setVideoAttributes(videoAttributes);
                Encoder encoder = new Encoder();
                encoder.encode(multimediaObject, target, attrs);
                FileInputStream inputStream = new FileInputStream(target);
                CustomMultipartFile customMultipartFile = new CustomMultipartFile(inputStream, uploadVideo.getUrl().substring(uploadVideo.getUrl().lastIndexOf("/") + 1)
                        , contentType);
                log.info("转码成功");
                // 将新视频上传到MinIO存储，删除临时文件
                videoClient.uploadVideo(customMultipartFile);
                log.info("上传新视频成功");
                target.delete();
                file.delete();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
