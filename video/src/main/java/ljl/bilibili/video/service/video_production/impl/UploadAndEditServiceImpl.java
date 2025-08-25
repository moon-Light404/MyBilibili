package ljl.bilibili.video.service.video_production.impl;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.core.io.IoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.minio.errors.*;
import ljl.bilibili.client.file.CustomMultipartFile;
import ljl.bilibili.client.notice.SendNoticeClient;
import ljl.bilibili.client.pojo.UploadVideo;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.entity.video.video_production.upload.Video;
import ljl.bilibili.entity.video.video_production.upload.VideoData;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoDataMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoMapper;
import ljl.bilibili.util.Result;
import ljl.bilibili.video.pojo.UploadPart;
import ljl.bilibili.video.service.video_production.UploadAndEditService;
import ljl.bilibili.video.vo.request.video_production.DeleteVideoRequest;
import ljl.bilibili.video.vo.request.video_production.EditVideoRequest;
import ljl.bilibili.video.vo.request.video_production.UploadPartRequest;
import ljl.bilibili.video.vo.request.video_production.UploadVideoRequest;
import ljl.bilibili.video.vo.response.video_production.UploadProcessorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.ScreenExtractor;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static ljl.bilibili.video.constant.Constant.*;

@Slf4j
@Service
public class UploadAndEditServiceImpl implements UploadAndEditService {
    @Resource
    MinioServiceImpl minioService;
    @Resource
    VideoMapper videoMapper;

    @Resource
    UserMapper userMapper;

    @Resource
    SendNoticeClient client;
    @Resource
    VideoDataMapper videoDataMapper;
    @Resource
    RedisTemplate objectRedisTemplate;

    /**
     * 判断是否恶意文件、上传视频到minio、新增视频与视频数据记录、推送视频动态、发送数据同步消息
     */
    @Override
    @Transactional
    public Result<Boolean> uploadTotal(UploadVideoRequest uploadVideoRequest) {
        try {
            Video video = uploadVideoRequest.toEntity();
            LambdaQueryWrapper<Video> queryWrapper = new LambdaQueryWrapper<>();
            String coverFile = uploadVideoRequest.getVideoCover();
            String url = "http://localhost:9000/video/" + uploadVideoRequest.getUrl();
            video.setUrl(url);
            // 如果视频有封面
            if (coverFile != null && coverFile != "") {
//                hasCover=true;
                String prefixPath = "http://localhost:9000/video-cover/";
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(coverFile);
//                ByteArrayInputStream bis = new ByteArrayInputStream(decodedBytes);
                // 创建ImageInputStream
//                ImageInputStream iis = ImageIO.createImageInputStream(bis);
                // 读取图片文件格式
//                String imgContentType = getImageFormat(iis);
//                log.info(imgContentType);
                String imgContentType = "image/jpeg";
                String coverFileName = video.getName() + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
                video.setCover(prefixPath + coverFileName);
                videoMapper.insert(video);
                videoDataMapper.insert(new VideoData().setVideoId(video.getId()));
                CustomMultipartFile coverMultipartFile = new CustomMultipartFile(decodedBytes, coverFileName, imgContentType);
                queryWrapper.eq(Video::getCover, UUID.randomUUID().toString().substring(0, 8) + coverFileName);
                minioService.uploadImgFile(coverFileName, coverMultipartFile.getInputStream(), imgContentType);
                // 视频上传消息发送到队列
                client.sendUploadNotice(new UploadVideo().setVideoId(video.getId()).setVideoName(video.getName()).setUrl(url).setHasCover(true));
                User user = userMapper.selectById(uploadVideoRequest.getUserId());
                // 视频动态推送到队列
                client.dynamicNotice(uploadVideoRequest.toCoverDynamic(user, video));
                // 视频无封面
            } else {
                videoMapper.insert(video);
                videoDataMapper.insert(new VideoData().setVideoId(video.getId()));
                // 视频上传消息发送至队列
                client.sendUploadNotice(new UploadVideo().setVideoId(video.getId()).setVideoName(video.getName()).setUrl(url).setHasCover(false));
                User user = userMapper.selectById(uploadVideoRequest.getUserId());
                // 视频动态推送到队列
                client.dynamicNotice(uploadVideoRequest.toNoCoverDynamic(user, video));
            }

            CompletableFuture<Void> sendDBChangeNotice = CompletableFuture.runAsync(() -> {
                ObjectMapper objectMapper = new ObjectMapper();
                JavaTimeModule module = new JavaTimeModule();
                // 设置LocalDateTime的序列化方式
                LocalDateTimeSerializer localDateTimeSerializer = new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                module.addSerializer(LocalDateTime.class, localDateTimeSerializer);
                objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                objectMapper.registerModule(module);
                Map<String, Object> map = objectMapper.convertValue(video, Map.class);
                map.put(TABLE_NAME, VIDEO_TABLE_NAME);
                map.put(OPERATION_TYPE, OPERATION_TYPE_ADD);
                map.put(VIDEO_ID, map.get(TABLE_ID));
                map.remove(TABLE_ID);
                client.sendDBChangeNotice(map);
            });

//            CompletableFuture.allOf(uploadVideoFuture, sendNoticeFuture).join();
//            if (!uploadVideoSuccess.get()) {
//                log.error("lose");
//            } else {
//                // 所有任务成功
//                log.info("ok");
//            }
            return Result.success(true);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("寄");
        }
    }

    /**
     * 编辑视频并发送数据同步消息
     */
    @Override
    public Result<Boolean> edit(EditVideoRequest editVideoRequest) {
        try {
            Map<String, Object> map = editVideoRequest.toMap();
            MultipartFile videoFile = editVideoRequest.getFile();
            ObjectMapper objectMapper = new ObjectMapper();
            JavaTimeModule module = new JavaTimeModule();
            LocalDateTimeSerializer localDateTimeSerializer = new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            module.addSerializer(LocalDateTime.class, localDateTimeSerializer);
            objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            objectMapper.registerModule(module);
            map.put(TABLE_NAME, VIDEO_TABLE_NAME);
            map.put(OPERATION_TYPE, OPERATION_TYPE_UPDATE);
            Video video = editVideoRequest.toEntity();
            if (videoFile != null) {
                String videoUrl = UUID.randomUUID().toString().substring(0, 10) + editVideoRequest.getName();
                video.setUrl(videoUrl);
                map.put(VIDEO_URL, videoUrl);
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        minioService.uploadVideoFile(videoUrl, videoFile.getInputStream(), videoFile.getContentType());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                });
                CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                    while (true) {
                        String key = "encode-count";
                        Integer count = (Integer) objectRedisTemplate.opsForValue().get(key);
                        if (count < 3) {
                            client.sendUploadNotice(new UploadVideo().setVideoId(video.getId()).setUrl(videoUrl).setVideoName(video.getName()));
                            count++;
                            objectRedisTemplate.opsForValue().set(key, count);
                            break;
                        } else {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }

                    }
                });
            }
            MultipartFile coverFile = editVideoRequest.getCover();
            if (coverFile != null) {
                CompletableFuture<Void> completableFuture = CompletableFuture.runAsync(() -> {
                    String coverUrl = UUID.randomUUID().toString().substring(0, 10) + coverFile.getOriginalFilename();
                    map.put(VIDEO_COVER, coverUrl);
                    try {
                        minioService.uploadImgFile(coverUrl, coverFile.getInputStream(), coverFile.getContentType());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    video.setCover(coverUrl);
                });
            }
            CompletableFuture<Void> completableFuture = CompletableFuture.runAsync(() -> client.sendDBChangeNotice(map));
            videoMapper.updateById(video);
            return Result.success(true);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("寄");
        }
    }

    /**
     * 删除视频并发送数据同步消息
     */
    @Override
    @Transactional
    public Result<Boolean> delete(DeleteVideoRequest deleteVideoRequest) {
        LambdaQueryWrapper<Video> deleteVideoWrapper = new LambdaQueryWrapper<>();
        LambdaQueryWrapper<VideoData> deleteVideoDataWrapper = new LambdaQueryWrapper<>();
        deleteVideoWrapper.eq(Video::getUserId, deleteVideoRequest.getUserId());
        deleteVideoWrapper.eq(Video::getId, deleteVideoRequest.getVideoId());
        deleteVideoDataWrapper.eq(VideoData::getVideoId, deleteVideoRequest.getVideoId());
        CompletableFuture<Void> sendDBChangeNotice = CompletableFuture.runAsync(() -> {
            ObjectMapper objectMapper = new ObjectMapper();
            JavaTimeModule module = new JavaTimeModule();
            LocalDateTimeSerializer localDateTimeSerializer = new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            module.addSerializer(LocalDateTime.class, localDateTimeSerializer);
            objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
            objectMapper.registerModule(module);
            Map<String, Object> map = new HashMap<>(3);
            map.put(TABLE_NAME, VIDEO_TABLE_NAME);
            map.put(OPERATION_TYPE, OPERATION_TYPE_DELETE);
            map.put(TABLE_ID, deleteVideoRequest.getVideoId());
            client.sendDBChangeNotice(map);
        });
        videoMapper.delete(deleteVideoWrapper);
        videoDataMapper.delete(deleteVideoDataWrapper);
        return Result.success(true);
    }

    /**
     * 上传视频时获取视频封面
     */
    /**
     * 处理视频分片上传并生成封面
     * 功能：接收视频分片，验证标识符，在首次处理时生成视频封面，上传分片到存储，并在所有分片上传完成后合并文件
     * @param uploadPartRequest 分片上传请求对象，包含分片信息、文件流等
     * @return 包含最终视频名称和封面Base64编码的结果列表
     * @throws（IO/编码/存储服务等相关），具体参见方法声明
     */
    @Override
    public Result<List<String>> uploadPart(UploadPartRequest uploadPartRequest) throws IOException, EncoderException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        // 处理resumableIdentifier，截取逗号前的有效标识符部分
        int commaIndex = uploadPartRequest.getResumableIdentifier().indexOf(',');
        uploadPartRequest.setResumableIdentifier(uploadPartRequest.getResumableIdentifier().substring(0, commaIndex));
        String resumableIdentifier = uploadPartRequest.getResumableIdentifier();

        // 最终视频名称和封面Base64编码（用于返回结果）
        String videoName = "";
        String videoCover = "";

        // 【已注释】原意图：校验第一个分片是否为MP4文件，防止恶意文件上传
//        if(uploadPartRequest.getResumableChunkNumber()==1){
//            String path = Files.createTempDirectory(".tmp").toString();
//            File file = new File(path, "test");
//            InputStream videoFileInputStream = uploadPartRequest.getFile().getInputStream();
//            byte[] bytes=IoUtil.readBytes(videoFileInputStream);
//            ByteArrayInputStream byteArrayInputStream=new ByteArrayInputStream(bytes);
//            Files.copy(byteArrayInputStream, Paths.get(file.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);
//            if (!FileTypeUtil.getType(file).equals("mp4")) {
//                file.delete();
//                return Result.error("上传恶意文件");
//            } else {
//                file.delete();
//                log.info("文件无问题");
//            }
//        }

        // 如果当前上传记录不存在，或尚未生成封面，则执行封面生成逻辑
        if (uploadPartMap.get(resumableIdentifier) == null || uploadPartMap.get(resumableIdentifier).getHasCutImg() == false) {
            // 读取当前分片的文件流
            InputStream videoFileInputStream = uploadPartRequest.getFile().getInputStream();
            byte[] bytes = IoUtil.readBytes(videoFileInputStream);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

            // 创建临时目录和文件（用于提取封面）
            String filePath = Files.createTempDirectory(".tmp").toString();
            String coverFileName = UUID.randomUUID().toString().substring(0, 10) + ".jpg"; // 封面文件名（带随机前缀）
            String videoFileName = "video"; // 临时视频文件名
            File directory = new File(filePath);
            File videoFile = new File(filePath, videoFileName); // 临时视频文件
            File coverFile = new File(directory, coverFileName); // 临时封面文件

            // 将分片文件流写入临时视频文件
            Files.copy(byteArrayInputStream, Paths.get(videoFile.getAbsolutePath()), StandardCopyOption.REPLACE_EXISTING);

            // 使用JAVE库提取视频第一帧作为封面
            ScreenExtractor screenExtractor = new ScreenExtractor();
            MultimediaObject multimediaObject = new MultimediaObject(videoFile);
            screenExtractor.renderOneImage(multimediaObject, -1, -1, 1000, coverFile, 1); // 参数：视频对象、宽(-1保持原宽)、高(-1保持原高)、提取时间(ms)、输出文件、质量

            // 若封面文件生成成功，编码为Base64并更新上传状态
            if (coverFile.exists()) {
                log.info("封面文件生成成功");
                InputStream inputStream = new FileInputStream(coverFile);
                String cover = Base64.encode(IoUtil.readBytes(inputStream)); // 封面图片Base64编码
                log.info("封面Base64编码前20位：" + cover.substring(0, 20));

                // 清理临时文件
                videoFile.delete();
                coverFile.delete();

                // 更新上传状态：标记已生成封面，并存储封面编码
                UploadPart uploadPart = uploadPartMap.getOrDefault(resumableIdentifier, new UploadPart());
                uploadPart.setHasCutImg(true);
                uploadPart.setCover(cover);
                uploadPartMap.put(resumableIdentifier, uploadPart);
            }
        }
        /**
         * uploadPartMap---> UploadPart---> partMap
         *  1. uploadPartMap---> UploadPart
         *  文件名/标识符----> 分片集合（已上传分片数、是否截取封面、封面的base64图像编码、partMap）
         *  2. UploadPart---> partMap
         *  partMap: 分片序号---> 分片文件名
         */
        // 生成当前分片的唯一名称并上传到MinIO
        String name = resumableIdentifier + UUID.randomUUID().toString().substring(0, 10); // 分片名称（标识符+随机串）
        // 上传分片文件到minio （分片文件名、分片文件流、文件类型）
        minioService.uploadVideoFile(name, uploadPartRequest.getFile().getInputStream(), VIDEO_TYPE);

        // 更新分片上传状态：记录当前分片编号与分片名称的映射关系
        Map<Integer, String> newUploadPartMap = uploadPartMap.getOrDefault(resumableIdentifier, new UploadPart()).getPartMap();
        // 存入分片序号--分片文件名
        newUploadPartMap.put(uploadPartRequest.getResumableChunkNumber(), name);
        UploadPart uploadPart = uploadPartMap.getOrDefault(resumableIdentifier, new UploadPart());
        // 更新各个分片的状态
        uploadPart.setPartMap(newUploadPartMap);
        uploadPartMap.put(resumableIdentifier, uploadPart);

        // 累加已上传分片数量，判断是否所有分片均已上传完成
        uploadPartMap.get(resumableIdentifier).setTotalCount(uploadPartMap.get(resumableIdentifier).getTotalCount() + 1);
        if (uploadPartMap.get(resumableIdentifier).getTotalCount().equals(uploadPartRequest.getResumableTotalChunks())) {
            log.info("所有分片上传完成，开始合并文件");
            // 生成最终视频名称，获取封面编码
            videoName = resumableIdentifier + UUID.randomUUID().toString().substring(0, 10);
            videoCover = uploadPartMap.get(resumableIdentifier).getCover();
            // 调用MinIO服务合并分片文件
            minioService.composePart(resumableIdentifier, videoName);
        }

        // 封装结果：视频名称和封面编码
        // 只有最终合并所有分片文件后，才返回视频名称和封面编码，否则为null
        List<String> list = new ArrayList<>();
        list.add(videoName);
        list.add(videoCover);
        return Result.data(list);
    }

    @Override
    public ResponseEntity<Result<Boolean>> getProcessor(String resumableIdentifier, Integer resumableChunkNumber) {
        log.info("id" + resumableChunkNumber.toString());
        log.info("uploadMap" + uploadPartMap.toString());
        log.info(resumableIdentifier);
        log.info(uploadPartMap.getOrDefault(resumableIdentifier, new UploadPart()).toString());
        for (Map.Entry<Integer, String> entry : uploadPartMap.getOrDefault(resumableIdentifier, new UploadPart()).getPartMap().entrySet()) {
            log.info("键值对" + entry.toString());
            log.info(entry.getKey().intValue() + "和" + resumableChunkNumber.intValue());
            if (entry.getKey().intValue() == (resumableChunkNumber.intValue())) {
                log.info("200");
                return ResponseEntity.ok(Result.data(true));
            }
        }

        return ResponseEntity.noContent().build();
    }

    private static String getImageFormat(ImageInputStream iis) throws IOException {
        Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(iis);

        if (imageReaders.hasNext()) {
            ImageReader reader = imageReaders.next();
            return reader.getFormatName();
        }

        return null;
    }
}
