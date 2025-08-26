package ljl.bilibili.entity.notice.dynamic;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 视频动态表：记录用户发布视频的动态
 */
@TableName("dynamic")
@Data
@Accessors(chain = true)
public class Dynamic {
    @TableId(type = IdType.AUTO)
    Integer id;
    @TableField("video_name")
    String videoName;
    @TableField(value = "create_time",fill = FieldFill.INSERT)
    LocalDateTime createTime;
    @TableField("video_cover")
    String videoCover;
    @TableField("video_id")
    Integer videoId;
    @TableField("author_id")
    Integer authorId;
}
