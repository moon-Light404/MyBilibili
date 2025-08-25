package ljl.bilibili.client.pojo;

import ljl.bilibili.entity.notice.like.LikeNotice;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class LikeNoticeAddOrDelete {

    private Integer id;
    /**
     * 点赞用户id
     */
    private Integer senderId;
    /**
     * 点赞所在的视频ID
     */
    private Integer videoId;
    /**
     * 被点赞的评论id
     */
    private Integer commentId;
    /**
     * 消息生成时间
     */
    private LocalDateTime createTime;
    /**
     * 未读已读
     */
    private Integer status;
    /**
     * 增加/删除
     */
    private Integer type;
    public LikeNotice toNotice(){
        LikeNotice likeNotice=new LikeNotice();
        BeanUtils.copyProperties(this,likeNotice);
        return likeNotice;
    }
}
