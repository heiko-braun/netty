/*
 * Copyright 2010 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.http.websocketx.sslserver;

import static io.netty.handler.codec.http.HttpHeaders.*;
import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpMethod.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.*;

import io.netty.buffer.ChannelBuffer;
import io.netty.buffer.ChannelBuffers;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ExceptionEvent;
import io.netty.channel.MessageEvent;
import io.netty.channel.SimpleChannelUpstreamHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;
import io.netty.util.CharsetUtil;

/**
 * Handles handshakes and messages
 */
public class WebSocketSslServerHandler extends SimpleChannelUpstreamHandler {
	private static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketSslServerHandler.class);

	private static final String WEBSOCKET_PATH = "/websocket";

	private WebSocketServerHandshaker handshaker = null;

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
		Object msg = e.getMessage();
		if (msg instanceof HttpRequest) {
			handleHttpRequest(ctx, (HttpRequest) msg);
		} else if (msg instanceof WebSocketFrame) {
			handleWebSocketFrame(ctx, (WebSocketFrame) msg);
		}
	}

	private void handleHttpRequest(ChannelHandlerContext ctx, HttpRequest req) throws Exception {
		// Allow only GET methods.
		if (req.getMethod() != GET) {
			sendHttpResponse(ctx, req, new DefaultHttpResponse(HTTP_1_1, FORBIDDEN));
			return;
		}

		// Send the demo page and favicon.ico
		if (req.getUri().equals("/")) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, OK);

			ChannelBuffer content = WebSocketSslServerIndexPage.getContent(getWebSocketLocation(req));

			res.setHeader(CONTENT_TYPE, "text/html; charset=UTF-8");
			setContentLength(res, content.readableBytes());

			res.setContent(content);
			sendHttpResponse(ctx, req, res);
			return;
		} else if (req.getUri().equals("/favicon.ico")) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, NOT_FOUND);
			sendHttpResponse(ctx, req, res);
			return;
		}

		// Handshake
		WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
				this.getWebSocketLocation(req), null, false);
		this.handshaker = wsFactory.newHandshaker(ctx, req);
		if (this.handshaker == null) {
			wsFactory.sendUnsupportedWebSocketVersionResponse(ctx);
		} else {
			this.handshaker.executeOpeningHandshake(ctx, req);
		}
	}

	private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {

		// Check for closing frame
		if (frame instanceof CloseWebSocketFrame) {
			this.handshaker.executeClosingHandshake(ctx, (CloseWebSocketFrame) frame);
			return;
		} else if (frame instanceof PingWebSocketFrame) {
			ctx.getChannel().write(new PongWebSocketFrame(frame.getBinaryData()));
			return;
		} else if (!(frame instanceof TextWebSocketFrame)) {
			throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass()
					.getName()));
		}

		// Send the uppercase string back.
		String request = ((TextWebSocketFrame) frame).getText();
		logger.debug(String.format("Channel %s received %s", ctx.getChannel().getId(), request));
		ctx.getChannel().write(new TextWebSocketFrame(request.toUpperCase()));
	}

	private void sendHttpResponse(ChannelHandlerContext ctx, HttpRequest req, HttpResponse res) {
		// Generate an error page if response status code is not OK (200).
		if (res.getStatus().getCode() != 200) {
			res.setContent(ChannelBuffers.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8));
			setContentLength(res, res.getContent().readableBytes());
		}

		// Send the response and close the connection if necessary.
		ChannelFuture f = ctx.getChannel().write(res);
		if (!isKeepAlive(req) || res.getStatus().getCode() != 200) {
			f.addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
		e.getCause().printStackTrace();
		e.getChannel().close();
	}

	private String getWebSocketLocation(HttpRequest req) {
		return "wss://" + req.getHeader(HttpHeaders.Names.HOST) + WEBSOCKET_PATH;
	}
}
