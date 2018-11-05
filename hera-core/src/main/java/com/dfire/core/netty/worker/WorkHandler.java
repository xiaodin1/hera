package com.dfire.core.netty.worker;

import com.dfire.core.netty.listener.ResponseListener;
import com.dfire.core.netty.worker.request.WorkExecuteJob;
import com.dfire.core.netty.worker.request.WorkHandleCancel;
import com.dfire.logs.SocketLog;
import com.dfire.protocol.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;

/**
 * @author: <a href="mailto:lingxiao@2dfire.com">凌霄</a>
 * @time: Created in 1:32 2018/1/4
 * @desc SocketMessage为RPC消息体
 */
public class WorkHandler extends SimpleChannelInboundHandler<RpcSocketMessage.SocketMessage> {

    private CompletionService<RpcResponse.Response> completionService = new ExecutorCompletionService<>(Executors.newCachedThreadPool());
    private WorkContext workContext;

    public WorkHandler(final WorkContext workContext) {
        this.workContext = workContext;
        workContext.setHandler(this);
        Executor executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            while (true) {
                try {
                    Future<RpcResponse.Response> future = completionService.take();
                    RpcResponse.Response response = future.get();
                    if (workContext.getServerChannel() != null) {
                        workContext.getServerChannel().writeAndFlush(wrapper(response));
                    }
                    SocketLog.info("worker get response thread success");
                } catch (Exception e) {
                    SocketLog.error("worker handler take future exception");
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private List<ResponseListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ResponseListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ResponseListener listener) {
        listeners.add(listener);
    }

    public RpcSocketMessage.SocketMessage wrapper(RpcResponse.Response response) {
        return RpcSocketMessage.SocketMessage
                .newBuilder()
                .setKind(RpcSocketMessage.SocketMessage.Kind.RESPONSE)
                .setBody(response.toByteString()).build();
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcSocketMessage.SocketMessage socketMessage) throws Exception {
        switch (socketMessage.getKind()) {
            case REQUEST:
                final RpcRequest.Request request = RpcRequest.Request.newBuilder().mergeFrom(socketMessage.getBody()).build();
                RpcOperate.Operate operate = request.getOperate();
                if (operate == RpcOperate.Operate.Schedule || operate == RpcOperate.Operate.Manual || operate == RpcOperate.Operate.Debug) {
                    completionService.submit(() ->
                            new WorkExecuteJob().execute(workContext, request).get());
                } else if (operate == RpcOperate.Operate.Cancel) {
                    completionService.submit(() ->
                            new WorkHandleCancel().handleCancel(workContext, request).get());
                }
                break;
            case RESPONSE:
                final RpcResponse.Response response = RpcResponse.Response.newBuilder().mergeFrom(socketMessage.getBody()).build();
                for (ResponseListener listener : listeners) {
                    listener.onResponse(response);
                }
                break;
            case WEB_RESPONSE:
                final RpcWebResponse.WebResponse webResponse = RpcWebResponse.WebResponse.newBuilder().mergeFrom(socketMessage.getBody()).build();
                for (ResponseListener listener : listeners) {
                    listener.onWebResponse(webResponse);
                }
                break;
            default:
                SocketLog.error("can not recognition ");
                break;

        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketLog.info("客户端与服务端连接开启");
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocketLog.warn("客户端与服务端连接关闭");
        workContext.setServerChannel(null);
        ctx.fireChannelInactive();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        SocketLog.info("worker complete read message ");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        SocketLog.error("work exception: {}, {}", ctx.channel().remoteAddress(), cause.toString());
        super.exceptionCaught(ctx, cause);
    }

}
