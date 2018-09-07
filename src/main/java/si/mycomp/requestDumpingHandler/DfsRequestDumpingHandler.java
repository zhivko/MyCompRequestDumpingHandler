package si.mycomp.requestDumpingHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSourceChannel;

import io.undertow.UndertowLogger;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.security.api.SecurityContext;
import io.undertow.server.Connectors;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.builder.HandlerBuilder;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.protocol.http.HttpContinue;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.LocaleUtils;

public class DfsRequestDumpingHandler implements HttpHandler {

	private static SimpleDateFormat sdf = new SimpleDateFormat();
	static {
		sdf.setTimeZone(TimeZone.getTimeZone("Europe/Ljubljana"));
		sdf.applyPattern("dd.MM.yyyy HH:mm:ss:SSS");
	}
	private static final UndertowLogger log = UndertowLogger.REQUEST_LOGGER;

	private HttpHandler next;

	private String timeLimit;
	float responseTimeLimit;

	String requestStr;
	int maxBuffers = 1024;

	public int getMaxBuffers() {
		return maxBuffers;
	}

	public void setMaxBuffers(int maxBuffers) {
		this.maxBuffers = maxBuffers;
	}

	public DfsRequestDumpingHandler(HttpHandler next) {
		this.next = next;
	}

	@Override
	public void handleRequest(final HttpServerExchange exchange) throws Exception {
		final StringBuilder sb = new StringBuilder();
		final SecurityContext sc = exchange.getSecurityContext();
		if (!exchange.isRequestComplete() && !HttpContinue.requiresContinueResponse(exchange.getRequestHeaders())) {
			final StreamSourceChannel channel = exchange.getRequestChannel();
			int readBuffers = 0;
			final PooledByteBuffer[] bufferedData = new PooledByteBuffer[maxBuffers];
			PooledByteBuffer buffer = exchange.getConnection().getByteBufferPool().allocate();
			try {
				do {
					int r;
					ByteBuffer b = buffer.getBuffer();
					r = channel.read(b);
					if (r == -1) {
						if (b.position() == 0) {
							buffer.close();
						} else {
							b.flip();
							bufferedData[readBuffers] = buffer;
						}
						break;
					} else if (r == 0) {
						final PooledByteBuffer finalBuffer = buffer;
						final int finalReadBuffers = readBuffers;
						channel.getReadSetter().set(new ChannelListener<StreamSourceChannel>() {

							PooledByteBuffer buffer = finalBuffer;
							int readBuffers = finalReadBuffers;

							@Override
							public void handleEvent(StreamSourceChannel channel) {
								try {
									do {
										int r;
										ByteBuffer b = buffer.getBuffer();
										r = channel.read(b);
										if (r == -1) {
											if (b.position() == 0) {
												buffer.close();
											} else {
												b.flip();
												bufferedData[readBuffers] = buffer;
											}
											Connectors.ungetRequestBytes(exchange, bufferedData);
											Connectors.resetRequestChannel(exchange);
											channel.getReadSetter().set(null);
											channel.suspendReads();
											Connectors.executeRootHandler(next, exchange);
											return;
										} else if (r == 0) {
											return;
										} else if (!b.hasRemaining()) {
											b.flip();
											bufferedData[readBuffers++] = buffer;
											if (readBuffers == maxBuffers) {
												Connectors.ungetRequestBytes(exchange, bufferedData);
												Connectors.resetRequestChannel(exchange);
												channel.getReadSetter().set(null);
												channel.suspendReads();
												Connectors.executeRootHandler(next, exchange);
												return;
											}
											buffer = exchange.getConnection().getByteBufferPool().allocate();
										}
									} while (true);
								} catch (Throwable t) {
									if (t instanceof IOException) {
										UndertowLogger.REQUEST_IO_LOGGER.ioException((IOException) t);
									} else {
										UndertowLogger.REQUEST_IO_LOGGER.undertowRequestFailed(t, exchange);
									}
									for (int i = 0; i < bufferedData.length; ++i) {
										IoUtils.safeClose(bufferedData[i]);
									}
									if (buffer != null && buffer.isOpen()) {
										IoUtils.safeClose(buffer);
									}
									exchange.endExchange();
								}
							}
						});
						channel.resumeReads();
						return;
					} else if (!b.hasRemaining()) {
						b.flip();
						bufferedData[readBuffers++] = buffer;
						if (readBuffers == maxBuffers) {
							break;
						}
						buffer = exchange.getConnection().getByteBufferPool().allocate();
					}

				} while (true);

				Connectors.ungetRequestBytes(exchange, bufferedData);
				Connectors.resetRequestChannel(exchange);

				sb.append("\n----------------------------REQUEST---------------------------\n");
				sb.append("             start=" + sdf.format(new Date()) + "\n");
				sb.append("               URI=" + exchange.getRequestURI() + "\n");
				sb.append(" characterEncoding=" + exchange.getRequestHeaders().get(Headers.CONTENT_ENCODING) + "\n");
				sb.append("     contentLength=" + exchange.getRequestContentLength() + "\n");
				sb.append("       contentType=" + exchange.getRequestHeaders().get(Headers.CONTENT_TYPE) + "\n");
				// sb.append(" contextPath=" + exchange.getContextPath());
				if (sc != null) {
					if (sc.isAuthenticated()) {
						sb.append("          authType=" + sc.getMechanismName() + "\n");
						sb.append("         principle=" + sc.getAuthenticatedAccount().getPrincipal() + "\n");
					} else {
						sb.append("          authType=none" + "\n");
					}
				}

				Map<String, Cookie> cookies = exchange.getRequestCookies();
				if (cookies != null) {
					for (Map.Entry<String, Cookie> entry : cookies.entrySet()) {
						Cookie cookie = entry.getValue();
						sb.append("            cookie=" + cookie.getName() + "=" + cookie.getValue() + "\n");
					}
				}
				for (HeaderValues header : exchange.getRequestHeaders()) {
					for (String value : header) {
						sb.append("            header=" + header.getHeaderName() + "=" + value + "\n");
					}
				}
				sb.append("            locale=" + LocaleUtils.getLocalesFromHeader(exchange.getRequestHeaders().get(Headers.ACCEPT_LANGUAGE)) + "\n");
				sb.append("            method=" + exchange.getRequestMethod() + "\n");

				Map<String, Deque<String>> pnames = exchange.getQueryParameters();
				for (Map.Entry<String, Deque<String>> entry : pnames.entrySet()) {
					String pname = entry.getKey();
					Iterator<String> pvalues = entry.getValue().iterator();
					sb.append("         parameter=");
					sb.append(pname);
					sb.append('=');
					while (pvalues.hasNext()) {
						sb.append(pvalues.next());
						if (pvalues.hasNext()) {
							sb.append(", ");
						}
					}
					sb.append("\n");
				}
				// sb.append(" pathInfo=" + exchange.getPathInfo());
				sb.append("          protocol=" + exchange.getProtocol() + "\n");
				sb.append("       queryString=" + exchange.getQueryString() + "\n");
				sb.append("        remoteAddr=" + exchange.getSourceAddress() + "\n");
				sb.append("        remoteHost=" + exchange.getSourceAddress().getHostName() + "\n");
				// sb.append("requestedSessionId=" + exchange.getRequestedSessionId());
				sb.append("            scheme=" + exchange.getRequestScheme() + "\n");
				sb.append("              host=" + exchange.getRequestHeaders().getFirst(Headers.HOST) + "\n");
				sb.append("        serverPort=" + exchange.getDestinationAddress().getPort() + "\n");
				// sb.append(" servletPath=" + exchange.getServletPath());
				// sb.append(" isSecure=" + exchange.isSecure() + "\n");
				sb.append("       readbuffers=" + readBuffers);
				sb.append("           request=\n");
				
				for (int i = 0; i <= readBuffers; i++) {
					byte[] bytes;
					ByteBuffer byteBuffer = bufferedData[i].getBuffer().duplicate();
					if (byteBuffer.hasArray()) {
						bytes = byteBuffer.array();
					} else {
						bytes = new byte[byteBuffer.remaining()];
						byteBuffer.get(bytes);
					}
					String str = new String(bytes, StandardCharsets.UTF_8);
					sb.append(str);
					sb.append(System.lineSeparator());
				}
			} catch (Exception | Error e) {
				for (int i = 0; i < bufferedData.length; ++i) {
					IoUtils.safeClose(bufferedData[i]);
				}
				if (buffer != null && buffer.isOpen()) {
					IoUtils.safeClose(buffer);
				}
				//throw e;
				sb.append(e.getMessage() + "\n" + e.getStackTrace()[0]);
			}
			// log.info("Request:\n" + builder.toString());

			PostExchangeListener mylistener = new PostExchangeListener(sb, sc, System.nanoTime());
			exchange.addExchangeCompleteListener(mylistener);
		}
		next.handleRequest(exchange);
	}

	public void settimeLimit(String s) {
		this.timeLimit = s;
		this.responseTimeLimit = Float.valueOf(s) / 1000.0f;
	}

	private class PostExchangeListener implements ExchangeCompletionListener {

		String reqString = "";
		long reqStartNanos = 0;
		StringBuilder sb;
		SecurityContext sc;

		public PostExchangeListener(StringBuilder sb, SecurityContext sc, long reqStartNanos) {
			this.sb = sb;
			this.sc = sc;
			this.reqStartNanos = reqStartNanos;
		}

		public void setRequest(String string) {
			// TODO Auto-generated method stub
			this.reqString = string;
		}

		@Override
		public void exchangeEvent(final HttpServerExchange exchange, NextListener nextListener) {

			try {
				float duration = (System.nanoTime() - reqStartNanos) / 1000000.0f;
				if (duration > responseTimeLimit) {
					// log.info(" start: " + reqStartNanos + " ns");
					// log.info(" duration: " + String.format("%.3f", duration) + " ms");
					// log.info("Reply from: " +
					// exchange.getSourceAddress().getHostName());
					// log.info("Request:\n" + reqString);

					dumpRequestBody(exchange, sb);

					// Log post-service information
					sb.append("--------------------------RESPONSE--------------------------\n");
					sb.append("            end=" + sdf.format(new Date()) + "\n");
					sb.append("       duration= " + String.format("%.3f", duration) + " ms\n");

					if (sc != null) {
						if (sc.isAuthenticated()) {
							sb.append("          authType=" + sc.getMechanismName() + "\n");
							sb.append("         principle=" + sc.getAuthenticatedAccount().getPrincipal() + "\n");
						} else {
							sb.append("          authType=none" + "\n");
						}
					}
					sb.append("     contentLength=" + exchange.getResponseContentLength() + "\n");
					sb.append("       contentType=" + exchange.getResponseHeaders().getFirst(Headers.CONTENT_TYPE) + "\n");
					Map<String, Cookie> cookies = exchange.getResponseCookies();
					if (cookies != null) {
						for (Cookie cookie : cookies.values()) {
							sb.append("            cookie=" + cookie.getName() + "=" + cookie.getValue() + "; domain=" + cookie.getDomain() + "; path="
									+ cookie.getPath() + "\n");
						}
					}
					for (HeaderValues header : exchange.getResponseHeaders()) {
						for (String value : header) {
							sb.append("            header=" + header.getHeaderName() + "=" + value + "\n");
						}
					}
					sb.append("            status=" + exchange.getStatusCode() + "\n");

					sb.append("\n==============================================================");

					log.info(sb.toString());
				}

			} finally {
				if (nextListener != null) {
					nextListener.proceed();
				}
			}
		}

	}

	private String toString(InputStream is) throws IOException {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			return br.lines().collect(Collectors.joining(System.lineSeparator()));
		}
	}

	public static final class Wrapper implements HandlerWrapper {

		private final int maxBuffers;

		public Wrapper() {
			this.maxBuffers = 255;
		}

		public Wrapper(int maxBuffers) {
			this.maxBuffers = maxBuffers;
		}

		@Override
		public HttpHandler wrap(HttpHandler handler) {
			return new DfsRequestDumpingHandler(handler);
		}
	}

	public static final class Builder implements HandlerBuilder {

		@Override
		public String name() {
			return "buffer-request";
		}

		@Override
		public Map<String, Class<?>> parameters() {
			return Collections.<String, Class<?>> singletonMap("buffers", Integer.class);
		}

		@Override
		public Set<String> requiredParameters() {
			return Collections.singleton("buffers");
		}

		@Override
		public String defaultParameter() {
			return "buffers";
		}

		@Override
		public HandlerWrapper build(Map<String, Object> config) {
			return new Wrapper((Integer) config.get("buffers"));
		}
	}

	private void dumpRequestBody(HttpServerExchange exchange, StringBuilder sb) {
		try {

			FormData formData = exchange.getAttachment(FormDataParser.FORM_DATA);
			if (formData != null) {
				sb.append("body=\n");

				for (String formField : formData) {
					Deque<FormData.FormValue> formValues = formData.get(formField);

					sb.append(formField).append("=");
					for (FormData.FormValue formValue : formValues) {
						sb.append(formValue.isFile() ? "[file-content]" : formValue.getValue());
						sb.append("\n");

						if (formValue.getHeaders() != null) {
							sb.append("headers=\n");
							for (HeaderValues header : formValue.getHeaders()) {
								sb.append("\t").append(header.getHeaderName()).append("=").append(header.getFirst()).append("\n");

							}
						}
					}
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}