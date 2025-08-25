package ljl.bilibili.video.pojo;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class UploadPart {
    /**
     * 分片序号-分片名称（分片文件名）
     */
    Map<Integer,String> partMap=new HashMap<>();
    /**
     * 已上传分片数
     */
    Integer totalCount=0;
    /**
     * 是否已经切分封面
     */
    Boolean hasCutImg=false;
    /**
     * 封面的base64编码
     */
    String cover="";
}
