package ljl.bilibili.client.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UploadVideo {
    private Integer videoId;
    /**
     * 视频url
     */
    private String url;
    /**
     * 视频名称
     */
    private String videoName;
    /**
     * 是否有封面
     */
    private Boolean hasCover;
}
