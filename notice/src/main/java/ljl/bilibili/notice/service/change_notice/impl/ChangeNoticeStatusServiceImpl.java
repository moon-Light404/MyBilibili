package ljl.bilibili.notice.service.change_notice.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.toolkit.JoinWrappers;
import com.github.yulichang.wrapper.UpdateJoinWrapper;
import ljl.bilibili.entity.notice.comment.CommentNotice;
import ljl.bilibili.entity.notice.dynamic.DynamicToUser;
import ljl.bilibili.entity.notice.like.LikeNotice;
import ljl.bilibili.entity.video.audience_reactions.comment.Comment;
import ljl.bilibili.entity.video.video_production.upload.Video;
import ljl.bilibili.mapper.notice.dynamic.DynamicToUserMapper;
import ljl.bilibili.mapper.video.audience_reactions.comment.CommentMapper;
import ljl.bilibili.mapper.video.video_production.upload.VideoMapper;
import ljl.bilibili.notice.service.change_notice.ChangeNoticeStatusService;
import ljl.bilibili.util.Result;
import ljl.bilibili.notice.vo.request.change_notice.CommentNoticeStatusChangeRequest;
import ljl.bilibili.notice.vo.request.change_notice.DynamicVideoStatusChangeRequest;
import ljl.bilibili.notice.vo.request.change_notice.LikeNoticeStatusChangeRequest;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
//消息状态更新
@Service
public class ChangeNoticeStatusServiceImpl implements ChangeNoticeStatusService {
    @Resource
    DynamicToUserMapper dynamicToUserMapper;
    @Resource
    VideoMapper videoMapper;
    @Resource
    CommentMapper commentMapper;
    /**
     *修改点赞消息状态
     */
@Override
    public Result<Boolean> changeLikeNoticeStatus(LikeNoticeStatusChangeRequest likeNoticeStatusChangeRequest) {
    //修改对视频的点赞消息为已读
        UpdateJoinWrapper<Video> videoUpdateJoinWrapper = JoinWrappers.update(Video.class);
        // 条件1：目标用户 ID（确保只更新当前用户的通知）
        videoUpdateJoinWrapper.eq(Video::getUserId, likeNoticeStatusChangeRequest.getUserId());
        // 关联查询：通过视频 ID 关联点赞通知表（LikeNotice）
        videoUpdateJoinWrapper.leftJoin(LikeNotice.class, LikeNotice::getVideoId, Video::getId);
        // 条件2：点赞类型为“视频点赞”（CommentId 为空表示不是对评论的点赞）
        videoUpdateJoinWrapper.isNull(LikeNotice::getCommentId);
        // 条件3：通知状态为“未读”（status=0）
        videoUpdateJoinWrapper.eq(LikeNotice::getStatus, 0);
        // 更新操作：将未读状态（0）改为已读（1）
        videoUpdateJoinWrapper.set(LikeNotice::getStatus, 1);
        // 执行联表更新（通过 VideoMapper 调用 MyBatis-Plus-Join 插件的联表更新能力）
        videoMapper.updateJoin(null, videoUpdateJoinWrapper);
        //修改对评论的点赞消息为已读
        UpdateJoinWrapper<Comment> commentUpdateJoinWrapper=JoinWrappers.update(Comment.class);
        commentUpdateJoinWrapper.eq(Comment::getUserId,likeNoticeStatusChangeRequest.getUserId());
        commentUpdateJoinWrapper.leftJoin(LikeNotice.class,LikeNotice::getCommentId,Comment::getId);
        commentUpdateJoinWrapper.eq(LikeNotice::getStatus,0);
        commentUpdateJoinWrapper.set(LikeNotice::getStatus,1);
        commentMapper.updateJoin(null,commentUpdateJoinWrapper);
        return Result.success(true);
    }
    /**
     *修改评论消息状态
     */
    @Override
    public Result<Boolean> changeCommentNoticeStatus(CommentNoticeStatusChangeRequest commentNoticeStatusChangeRequest) {
        //修改对视频的评论的消息已读
        UpdateJoinWrapper<Video> videoUpdateJoinWrapper=JoinWrappers.update(Video.class);
        videoUpdateJoinWrapper.eq(Video::getUserId,commentNoticeStatusChangeRequest.getUserId());
        videoUpdateJoinWrapper.leftJoin(CommentNotice.class,CommentNotice::getVideoId,Video::getId);
        videoUpdateJoinWrapper.isNull(CommentNotice::getReceiverId);
        videoUpdateJoinWrapper.eq(CommentNotice::getStatus,0);
        videoUpdateJoinWrapper.set(CommentNotice::getStatus,1);
        videoMapper.updateJoin(null,videoUpdateJoinWrapper);
        //修改对评论的评论的消息已读
        UpdateJoinWrapper<Comment> commentUpdateJoinWrapper=JoinWrappers.update(Comment.class);
        commentUpdateJoinWrapper.eq(Comment::getUserId,commentNoticeStatusChangeRequest.getUserId());
        commentUpdateJoinWrapper.leftJoin(CommentNotice.class,CommentNotice::getReceiverId,Comment::getId);
        commentUpdateJoinWrapper.eq(CommentNotice::getStatus,0);
        commentUpdateJoinWrapper.set(CommentNotice::getStatus,1);
        commentMapper.updateJoin(null,commentUpdateJoinWrapper);
        return Result.success(true);
    }
    /**
     *修改推送动态状态
     */
    @Override
    public Result<Boolean> changeDynamicVideoStatus(DynamicVideoStatusChangeRequest videoStatusChangeRequest) {
        LambdaUpdateWrapper<DynamicToUser> updateWrapper = Wrappers.lambdaUpdate();
        updateWrapper.eq(DynamicToUser::getUserId, videoStatusChangeRequest.getUserId());
        updateWrapper.set(DynamicToUser::getStatus, 1);
        dynamicToUserMapper.update(null, updateWrapper);
        return Result.success(true);
    }
}
