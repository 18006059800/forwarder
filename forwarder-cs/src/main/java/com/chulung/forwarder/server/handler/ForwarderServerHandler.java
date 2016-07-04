package com.chulung.forwarder.server.handler;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chulung.forwarder.common.ClientType;
import com.chulung.forwarder.common.DataType;
import com.chulung.forwarder.wrapper.DataWrapper;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 转发服务端handler
 * 
 * @author ChuKai
 *
 */
public class ForwarderServerHandler extends SimpleChannelInboundHandler<DataWrapper> {
	private static final Logger LOGGER = LoggerFactory.getLogger(ForwarderServerHandler.class);
	private ChannelHandlerContext serverProxyCtx;
	private Map<ChannelId, ChannelHandlerContext> clientProxyCtxMap = new HashMap<>();

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		cause.printStackTrace();
		// 服务器代理异常，通知所有客户端代理
		if (ctx == serverProxyCtx) {
			LOGGER.error("服务器代理异常，通知所有客户端代理", cause);
			clientProxyCtxMap.values().forEach(ctxv -> {
				ctxv.writeAndFlush(new DataWrapper(DataType.Server_PROXY_ERROR));
			});
		} else {
			LOGGER.error("客户端代理通道异常，通知服务端代理", cause);
			// 客户端代理异常则通知服务器代理
			this.clientProxyCtxMap.remove(ctx.channel().id());
			if (serverProxyCtx!=null) {
				serverProxyCtx.writeAndFlush(new DataWrapper(DataType.CLIENT_CLOSE, ctx.channel().id()));
			}
		}
		ctx.close();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, DataWrapper msg) throws Exception {
		if (msg == null || msg.getClientType() == null) {
			ctx.close();
			// 远程客户端代理数据
		} else if (msg.getClientType() == ClientType.ClientProxy) {
			ClientProxyRead(ctx, msg);
		} else if (msg.getClientType() == ClientType.ServerProxy) {
			ServerProxyRead(ctx, msg);
		}
	}

	/**
	 * 读取服务端代理数据
	 * 
	 * @param ctx
	 * @param msg
	 */
	private void ServerProxyRead(ChannelHandlerContext ctx, DataWrapper msg) {
		// 远程服务端代理数据
		// 远程服务端代理已存在，不允许多个
		if (this.serverProxyCtx != null) {
			ctx.writeAndFlush(new DataWrapper(DataType.SERVER_PROXY_EXIST));
			LOGGER.error("服务端代理已存在 address={}", this.serverProxyCtx.channel().remoteAddress());
			return;
		} else {
			this.serverProxyCtx = ctx;
		}
		ChannelHandlerContext clientCtx = this.clientProxyCtxMap.get(msg.getClientId());
		if (clientCtx != null) {
			clientCtx.writeAndFlush(msg);
		} else {
			// 远程客户端丢失，通知远程服务端代理抛弃对应通道
			LOGGER.error("远程客户端丢失 ClientId={}", msg.getClientId());
			this.serverProxyCtx.write(new DataWrapper(DataType.CLIENT_CLOSE, msg.getClientId()));
		}
	}

	/**
	 * 读取客户端代理数据
	 * 
	 * @param ctx
	 * @param msg
	 */
	private void ClientProxyRead(ChannelHandlerContext ctx, DataWrapper msg) {
		if (this.serverProxyCtx == null) {
			LOGGER.error("服务端代理不存在");
			// 服务端代理不存在
			ctx.writeAndFlush(new DataWrapper(DataType.SERVER_PROXY_NOT_FOUNED));
		} else if (msg.getDataType() == DataType.CLIENT_CONNECTING) {
			LOGGER.info("客户端代理已连接 addr={}", ctx.channel().remoteAddress());
			this.clientProxyCtxMap.put(ctx.channel().id(), ctx);
		} else if (msg.getDataType() == DataType.DATA) {
			msg.setClientId(ctx.channel().id());
			this.serverProxyCtx.writeAndFlush(msg);
		}
	}

}
