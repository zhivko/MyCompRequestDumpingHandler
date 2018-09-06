package si.mycomp.requestDumpingHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.xnio.ChannelListener;
import org.xnio.IoUtils;
import org.xnio.channels.StreamSourceChannel;

import io.undertow.UndertowLogger;
import io.undertow.connector.PooledByteBuffer;
import io.undertow.server.Connectors;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.protocol.http.HttpContinue;

public class DfsRequestDumpingHandler implements HttpHandler {

	private static final UndertowLogger log = UndertowLogger.REQUEST_LOGGER;

	private HttpHandler next;
	private String param1;

	private String timeLimit;
	float responseTimeLimit;

	String requestStr;
	int maxBuffers = 1024;

	public DfsRequestDumpingHandler(HttpHandler next) {
		this.next = next;
	}

	@Override
	public void handleRequest(final HttpServerExchange exchange) throws Exception {
		StringBuilder builder=new StringBuilder();

		//if (!exchange.isRequestComplete() && !HttpContinue.requiresContinueResponse(exchange.getRequestHeaders())) {
			final StreamSourceChannel channel = exchange.getRequestChannel();
			int readBuffers = 0;
			final PooledByteBuffer[] bufferedData = new PooledByteBuffer[maxBuffers];
			PooledByteBuffer buffer = exchange.getConnection().getByteBufferPool().allocate();
			System.out.println("1");
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
							System.out.println("2");
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
												System.out.println("3");
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
											System.out.println("4");
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
										UndertowLogger.REQUEST_IO_LOGGER.handleUnexpectedFailure(t);
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
				
				System.out.println("length: " + readBuffers);
				for(int i=0;i<=readBuffers;i++)
				{
					byte[] bytes;
					System.out.println("i:" + i);
					System.out.println(bufferedData[i].getBuffer());
			    if(bufferedData[i].getBuffer().hasArray()) {
			        bytes = bufferedData[i].getBuffer().array();
			    } else {
			        bytes = new byte[bufferedData[i].getBuffer().remaining()];
			        bufferedData[i].getBuffer().get(bytes);
			    }
					String str = new String(bytes, StandardCharsets.UTF_8);
					builder.append(str);
					builder.append(System.lineSeparator());					
				}							
				
				Connectors.ungetRequestBytes(exchange, bufferedData);
				Connectors.resetRequestChannel(exchange);

			} catch (Exception | Error e) {
				for (int i = 0; i < bufferedData.length; ++i) {
					IoUtils.safeClose(bufferedData[i]);
				}
				if (buffer != null && buffer.isOpen()) {
					IoUtils.safeClose(buffer);
				}
				throw e;
			}

	
			
			PostExchangeListener mylistener = new PostExchangeListener(builder.toString(), System.nanoTime());
			exchange.addExchangeCompleteListener(mylistener);
		//	}

	
		next.handleRequest(exchange);
	}

	public void settimeLimit(String s) {
		this.timeLimit = s;
	}

	private class PostExchangeListener implements ExchangeCompletionListener {

		String reqString = "";
		long reqStartNanos = 0;

		public PostExchangeListener(String reqStr, long reqStartNanos) {
			this.reqString = reqStr;
			this.reqStartNanos = reqStartNanos;
		}

		public void setRequest(String string) {
			// TODO Auto-generated method stub
			this.reqString = string;
		}

		@Override
		public void exchangeEvent(final HttpServerExchange exchange, NextListener nextListener) {

			try {
				float duration = (System.nanoTime() - exchange.getRequestStartTime()) / 1000000.0f;
				if (duration > Float.valueOf(timeLimit)) {
					log.info("  duration: " + String.format("%.3f", duration) + " ms");
					log.info("Reply from: " + exchange.getSourceAddress().getHostName());
					log.info("Request:\n" + reqString);
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
}