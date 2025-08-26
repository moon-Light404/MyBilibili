package ljl.bilibili.search.handler;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.xxl.job.core.handler.annotation.XxlJob;
import ljl.bilibili.search.constant.Constant;
import ljl.bilibili.search.service.MysqlToEsService;
import ljl.bilibili.search.service.impl.MysqlToEsServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 *MySQL到ES数据同步的定时任务执行器
 */
@Component
@Slf4j
public class MysqlToEsHandler {
    @Resource
    RedisTemplate objectRedisTemplate;
    @Resource
    RestHighLevelClient client;
    @Resource
    MysqlToEsService mysqlToEsService;
    private static Boolean hasSynchronousVideo = false;
    private static Boolean hasSynchronousUser = false;
    BloomFilter<Integer> videoFilter = BloomFilter.create(
            Funnels.integerFunnel(),
            1000,
            0.01);
    BloomFilter<Integer> userFilter = BloomFilter.create(
            Funnels.integerFunnel(),
            1000,
            0.01);
    /**
     *同步MySQL数据到ES
     */
    /**
     * XXL-Job定时任务处理器，负责协调MySQL到Elasticsearch的数据同步流程
     * 包含全量同步初始化和增量同步处理逻辑
     */
    @XxlJob("mysqlToEs")
    public void mysqlToEsHandler() throws Exception {
        // 首次执行时全量同步视频数据：初始化ES视频索引并加载历史数据
        if (hasSynchronousVideo == false) {
            mysqlToEsService.videoMysqlToEs();  // 全量同步视频基础数据
            mysqlToEsService.updateVideoData(); // 补充视频关联数据（如标签、分类等）
            hasSynchronousVideo = true;         // 标记视频数据已完成首次全量同步
        }

        // 首次执行时全量同步用户数据：初始化ES用户索引并加载历史数据
        if (hasSynchronousUser == false) {
            mysqlToEsService.userMysqlToEs();   // 全量同步用户基础数据
            mysqlToEsService.updateUserData();  // 补充用户关联数据（如关注关系、权限等）
            hasSynchronousUser = true;          // 标记用户数据已完成首次全量同步
        }

        // 从Redis获取增量同步数据：这些列表由MysqlToEsConsumer消费者实时写入
        List<HashMap<String, Object>> videoAddList = objectRedisTemplate.opsForList().range(Constant.VIDEO_ADD_KEY, 0, -1);    // 视频新增记录
        List<HashMap<String, Object>> videoDeleteList = objectRedisTemplate.opsForList().range(Constant.VIDEO_DELETE_KEY, 0, -1); // 视频删除记录
        List<HashMap<String, Object>> videoUpDateList = objectRedisTemplate.opsForList().range(Constant.VIDEO_UPDATE_KEY, 0, -1); // 视频更新记录
        List<HashMap<String, Object>> userAddList = objectRedisTemplate.opsForList().range(Constant.USER_ADD_KEY, 0, -1);      // 用户新增记录
        List<HashMap<String, Object>> userUpDateList = objectRedisTemplate.opsForList().range(Constant.USER_UPDATE_KEY, 0, -1);  // 用户更新记录

        // 同步顺序控制：先执行新增/删除操作，再执行更新操作
        // 避免更新时目标文档尚未创建或已被删除导致的无效操作
        if (!videoAddList.isEmpty()) {
            mysqlAddToEs(Constant.OPERATION_ADD, videoAddList, Constant.VIDEO_INDEX_NAME);  // 批量处理视频新增
        }
        if (!videoDeleteList.isEmpty()) {
            mysqlAddToEs(Constant.OPERATION_DELETE, videoAddList, Constant.VIDEO_INDEX_NAME);  // 批量处理视频删除
        }

        if (!userAddList.isEmpty()) {
            mysqlAddToEs(Constant.OPERATION_ADD, userAddList, Constant.USER_INDEX_NAME);    // 批量处理用户新增
        }

        // 加载ES现有文档ID到布隆过滤器：用于更新操作前的存在性校验
        // 视频索引文档ID加载
        SearchRequest videoSearchRequest = new SearchRequest(Constant.VIDEO_INDEX_NAME);
        videoSearchRequest.source(new SearchSourceBuilder().query(QueryBuilders.matchAllQuery()).size(10000)); // 查询所有视频文档
        SearchResponse videoSearchResponse = client.search(videoSearchRequest, RequestOptions.DEFAULT);
        for (SearchHit searchHit : videoSearchResponse.getHits().getHits()) {
            videoFilter.put(Integer.valueOf(searchHit.getId()));  // 将视频ID存入布隆过滤器
        }

        // 用户索引文档ID加载
        SearchRequest userSearchRequest = new SearchRequest(Constant.USER_INDEX_NAME);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().size(10000); // 查询所有用户文档（最多10000条）
        userSearchRequest.source(sourceBuilder.query(QueryBuilders.matchAllQuery()));
        SearchResponse userSearchResponse = client.search(userSearchRequest, RequestOptions.DEFAULT);
        for (SearchHit searchHit : userSearchResponse.getHits().getHits()) {
            int id = Integer.valueOf(searchHit.getId());
            userFilter.put(id);  // 将用户ID存入布隆过滤器
        }

        // 执行更新操作：基于布隆过滤器验证文档存在性后再更新
        if (videoUpDateList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_UPDATE, videoUpDateList, Constant.VIDEO_INDEX_NAME);  // 批量处理视频更新
        }
        if (userUpDateList.size() > 0) {
            mysqlAddToEs(Constant.OPERATION_UPDATE, userUpDateList, Constant.USER_INDEX_NAME);    // 批量处理用户更新
        }

        // 清理Redis增量同步数据：删除已处理的操作记录，避免重复同步
        objectRedisTemplate.delete(Constant.VIDEO_ADD_KEY);
        objectRedisTemplate.delete(Constant.VIDEO_DELETE_KEY);
        objectRedisTemplate.delete(Constant.VIDEO_UPDATE_KEY);
        objectRedisTemplate.delete(Constant.USER_UPDATE_KEY);
        objectRedisTemplate.delete(Constant.USER_ADD_KEY);
    }
    /**
     *根据索引名和原始请求批量添加请求和保存原始请求
     */

    public Boolean mysqlAddToEs(String requestType, List<HashMap<String, Object>> list, String indexName) throws IOException {
        //批量添加请求
        BulkRequest bulkRequest;
        List<DocWriteRequest> docWriteRequestList = new ArrayList<>();
        // 新增
        if (requestType.equals("add")) {
            bulkRequest = new BulkRequest();
            for (Map<String, Object> document : list) {
                int intId;
                if (Constant.VIDEO_INDEX_NAME.equals(indexName)) {
                    intId = (Integer) document.get(Constant.VIDEO_INDEX_ID);
                } else {
                    intId = (Integer) document.get(Constant.INDEX_ID);
                }
                String id = String.valueOf(intId);
                IndexRequest indexRequest = new IndexRequest(indexName);
                indexRequest.id(id).source(document, XContentType.JSON);
                docWriteRequestList.add(indexRequest);
                bulkRequest.add(indexRequest);
            }

        } else if (requestType.equals("update")) {
            bulkRequest = new BulkRequest();
            if (Constant.VIDEO_INDEX_NAME.equals(indexName)) {
                for (Map<String, Object> map : list) {
                    int intId = (Integer) map.get(Constant.VIDEO_INDEX_ID);
                    //利用布隆过滤器判定存在可能误判，判定不存在则一定不存在的特性反向百分百找到一定不会出错的文档id并添加到递归同步的请求集合中
                    if (!videoFilter.mightContain(intId)) {

                    } else {
                        UpdateRequest updateRequest = new UpdateRequest(indexName, String.valueOf(intId)).doc(map);
                        bulkRequest.add(updateRequest);
                        docWriteRequestList.add(updateRequest);
                    }
                }
            } else {
                for (Map<String, Object> map : list) {
                    int intId = (Integer) map.get(Constant.INDEX_ID);
                    if (!userFilter.mightContain(intId)) {

                    } else {
                        UpdateRequest updateRequest = new UpdateRequest(indexName, String.valueOf(intId)).doc(map);
                        bulkRequest.add(updateRequest);
                        docWriteRequestList.add(updateRequest);
                    }
                }
            }
            // 删除
        } else {
            bulkRequest = new BulkRequest();
            for (Map<String, Object> document : list) {
                int intId;
                    intId = (Integer) document.get(Constant.VIDEO_INDEX_ID);
                String id = String.valueOf(intId);
                DeleteRequest deleteRequest = new DeleteRequest(indexName, id);
                bulkRequest.add(deleteRequest);
                docWriteRequestList.add(deleteRequest);
            }
        }
        //递归调用同步方法
        bulkOpreateUntilAllSucess(bulkRequest, docWriteRequestList, 10);
        return true;
    }
    /**
     *递归批量同步操作
     */
    public Boolean bulkOpreateUntilAllSucess(BulkRequest bulkRequest, List<DocWriteRequest> list, int maxRetry) throws IOException {
        BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);
        //递归将收集到的失败操作统一递归再调用自己方法最大程度减少失败请求次数，同时设定次数为10防止递归过多堆栈溢出
        if (bulkResponse.hasFailures() && maxRetry > 0) {
            BulkRequest bulkRequest1 = new BulkRequest();
            // 遍历bulkResponse，找到失败的操作并添加到新的批量请求中
            for (int i = 0; i < bulkResponse.getItems().length; i++) {
                BulkItemResponse itemResponse = bulkResponse.getItems()[i];
                if (itemResponse.isFailed()) {
                    bulkRequest1.add(list.get(i));
                }
            }
            return bulkOpreateUntilAllSucess(bulkRequest1, list, maxRetry - 1);
        }
        // 没有失败的操作返回true
        else {
            return true;
        }
    }
}
