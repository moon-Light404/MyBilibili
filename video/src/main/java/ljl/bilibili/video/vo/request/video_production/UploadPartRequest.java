package ljl.bilibili.video.vo.request.video_production;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class UploadPartRequest {
    /**
     * 分片文件
     */
    MultipartFile file;
    /**
     * 分片总数
     */
    Integer resumableTotalChunks;
    /**
     * 文件标识符。属于哪个文件的分片
     */
    String resumableIdentifier;
    /**
     * 分片序号
     */
    Integer resumableChunkNumber;
}
