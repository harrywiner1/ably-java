package io.ably.lib.debug;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

import io.ably.lib.http.HttpCore;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;
import io.ably.lib.types.ProtocolMessage;

public class DebugOptions extends ClientOptions {
	public interface RawProtocolListener {
		public void onRawConnect(String url);
		public void onRawMessageSend(ProtocolMessage message);
		public void onRawMessageRecv(ProtocolMessage message);
	}

	public interface RawHttpListener {
		public void onRawHttpRequest(String id, HttpURLConnection conn, String method, String authHeader, Map<String, List<String>> requestHeaders, HttpCore.RequestBody requestBody);
		public void onRawHttpResponse(String id, HttpCore.Response response);
		public void onRawHttpException(String id, Throwable t);
	}

	public DebugOptions(String key) throws AblyException { super(key); }

	public RawProtocolListener protocolListener;
	public RawHttpListener httpListener;
}
