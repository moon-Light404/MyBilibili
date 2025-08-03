package ljl.bilibili.search.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.entity.user_center.user_relationships.IdCount;
import ljl.bilibili.entity.video.video_production.upload.Video;
import ljl.bilibili.entity.video.video_production.upload.VideoData;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.mapper.user_center.user_relationships.FollowMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoDataMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoMapper;
import ljl.bilibili.search.constant.Constant;
import ljl.bilibili.search.entity.UserEntity;
import ljl.bilibili.search.service.MysqlToEsService;
import ljl.bilibili.search.vo.response.VideoKeywordSearchResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 *MySQL同步数据到ES
 */
@Service
public class MysqlToEsServiceImpl implements MysqlToEsService {
    @Resource
    VideoMapper videoMapper;
    @Resource
    UserMapper userMapper;
    @Resource
    ObjectMapper objectMapper;
    @Resource
    FollowMapper followMapper;
    @Resource
    VideoDataMapper videoDataMapper;
    @Resource
    RestHighLevelClient client;
    /**
     *用户全量同步
     */
@Override
    public Boolean userMysqlToEs() throws IOException {
    //查询出所有用户并转换成map插入es
        MPJLambdaWrapper<User> wrapper = new MPJLambdaWrapper<>();
        List<Map<String, Object>> userMap = new ArrayList<>();
        wrapper.select(User::getCover, User::getNickname, User::getId, User::getIntro);
        List<UserEntity> userList = userMapper.selectJoinList(UserEntity.class, wrapper);
        for (UserEntity user : userList) {
            userMap.add(objectMapper.convertValue(user,Map.class));
        }
        for (Map<String, Object> map : userMap) {
            IndexRequest indexRequest = new IndexRequest(Constant.USER_INDEX_NAME);
            Integer id = (Integer) map.get(Constant.INDEX_ID);
            indexRequest.id(id.toString());
            indexRequest.source(map, XContentType.JSON);
            client.index(indexRequest, RequestOptions.DEFAULT);
        }
        return true;
    }
    /**
     *视频全量同步
     */
    /**
     * 视频数据全量同步至Elasticsearch
     * 功能：从MySQL关联查询视频基础信息、用户信息及视频统计数据，转换为ES文档格式并批量写入视频索引
     * @return 同步是否成功（true表示成功）
     * @throws IOException ES客户端操作可能抛出的IO异常
     */
    @Override
    public Boolean videoMysqlToEs() throws IOException {
        // 构建多表关联查询条件：用于从MySQL关联查询视频相关数据
        MPJLambdaWrapper<Video> wrapper = new MPJLambdaWrapper<>();
        // 关联用户表：获取视频作者信息（如昵称）
        wrapper.leftJoin(User.class, User::getId, Video::getUserId);
        // 关联视频数据表：获取视频统计数据（如播放量、弹幕数）
        wrapper.leftJoin(VideoData.class, VideoData::getVideoId, Video::getId);

        // 筛选需要同步的字段：仅选择ES索引所需的字段，减少数据传输量
        // 视频基础字段：封面、简介、创建时间、时长、播放地址
        wrapper.select(Video::getCover, Video::getIntro, Video::getCreateTime, Video::getLength, Video::getUrl);
        // 视频统计字段：弹幕数、播放量（来自VideoData表）
        wrapper.select(VideoData::getDanmakuCount, VideoData::getPlayCount);
        // 字段别名映射：将MySQL字段名转换为ES文档中的字段名（与VideoKeywordSearchResponse对应）
        wrapper.selectAs(Video::getName, VideoKeywordSearchResponse::getVideoName);       // 视频名称 -> video_name
        wrapper.selectAs(User::getNickname, VideoKeywordSearchResponse::getAuthorName);  // 作者昵称 -> author_name
        wrapper.selectAs(Video::getId, VideoKeywordSearchResponse::getVideoId);         // 视频ID -> video_id
        wrapper.selectAs(User::getId, VideoKeywordSearchResponse::getAuthorId);         // 作者ID -> author_id

        // 执行关联查询：获取满足条件的视频数据列表（封装为VideoKeywordSearchResponse对象）
        List<VideoKeywordSearchResponse> list = videoMapper.selectJoinList(VideoKeywordSearchResponse.class, wrapper);

        // 转换数据格式：将Java对象列表转换为Map列表，便于ES文档构建
        List<Map<String, Object>> mapList = new ArrayList<>();
        for (VideoKeywordSearchResponse response : list) {
            mapList.add(objectMapper.convertValue(response, Map.class));
        }

        // 批量写入ES：遍历Map列表，为每个视频数据创建ES索引请求并执行
        for (Map<String, Object> map : mapList) {
            // 创建ES索引请求：指定视频索引名称（VIDEO_INDEX_NAME）
            IndexRequest indexRequest = new IndexRequest(Constant.VIDEO_INDEX_NAME);
            // 设置文档ID：使用视频ID作为ES文档ID（确保唯一性）
            indexRequest.id(String.valueOf(map.get(Constant.VIDEO_INDEX_ID)));
            // 设置文档内容：将Map数据转换为JSON格式写入ES
            indexRequest.source(map, XContentType.JSON);
            // 执行索引操作：发送请求到ES服务端
            client.index(indexRequest, RequestOptions.DEFAULT);
        }

        // 同步用户数据：视频同步完成后，触发用户相关数据（如作品数）的更新
        updateUserData();

        return true;
    }
    /**
     *视频数据全量同步
     */
    @Override
    public Boolean updateVideoData() throws IOException {
        //查询出所有视频绑定的相关数据比如播放数评论数弹幕数然后插入es
        BulkRequest bulkRequest = new BulkRequest();
        List<VideoData> videoDataList = videoDataMapper.selectList(null);
        List<Map<String, Object>> mapList = new ArrayList<>();
        for (VideoData videoData : videoDataList) {
            Map<String,Object> map=objectMapper.convertValue(videoData,Map.class);
            map.remove(Constant.INDEX_ID);
            map.remove(Constant.VIDEO_INDEX_REMOVE_COMMENT_COUNT);
            map.remove(Constant.VIDEO_INDEX_REMOVE_LIKE_COUNT);
            mapList.add(map);
        }
        for (Map<String, Object> map : mapList) {
            int intId = (Integer) map.get(Constant.VIDEO_INDEX_ID);
            String id = String.valueOf(intId);
            bulkRequest.add(new UpdateRequest(Constant.VIDEO_INDEX_NAME, id).doc(map));
        }
        client.bulk(bulkRequest, RequestOptions.DEFAULT);
        return true;
    }
    /**
     *用户关注数、作品数全量同步
     */
    @Override
    public Boolean updateUserData() throws IOException {
        //查询出所有用户的相关数据比如关注数粉丝数然后插入es
        BulkRequest bulkRequest = new BulkRequest();
        List<User> userList = userMapper.selectList(null);
        List<Integer> ids = new ArrayList<>();
        for (User user : userList) {
            ids.add(user.getId());
        }
        Map<Integer, Integer> fansCountMap = new HashMap<>();
        Map<Integer, Integer> videoCountMap = new HashMap<>();
        List<IdCount> idCountList = followMapper.getIdolCount(ids);
        List<IdCount> videoCountList=followMapper.getVideoCount(ids);
        for (IdCount idCount : idCountList) {
            fansCountMap.put(idCount.getId(), idCount.getCount());
        }
        for(IdCount idCount : videoCountList){
            videoCountMap.put(idCount.getId(),idCount.getCount());
        }
        for (Integer id : ids) {
            Map<String, Object> map = new HashMap<>();
            map.put(Constant.INDEX_ID, id.toString());
            map.put(Constant.USER_INDEX_PUT_FANS_COUNT, fansCountMap.getOrDefault(id, 0));
            map.put(Constant.USER_INDEX_PUT_VIDEO_COUNT, videoCountMap.getOrDefault(id, 0));
            bulkRequest.add(new UpdateRequest(Constant.USER_INDEX_NAME, id.toString()).doc(map));
        }
        client.bulk(bulkRequest, RequestOptions.DEFAULT);
        return true;
    }
}
