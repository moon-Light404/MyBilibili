package ljl.bilibili.entity.user_center.user_relationships;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow")
public class Follow {
    @TableId(type = IdType.AUTO)
    private Integer id;
    // 粉丝
    @TableField("fans_id")
    Integer fansId;
    // 被关注的人
    @TableField("idol_id")
    Integer idolId;
    @TableField(value = "create_time",fill = FieldFill.INSERT)
    LocalDateTime createTime;
}
